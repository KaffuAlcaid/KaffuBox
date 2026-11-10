// 定义脚本宿主调用协议，并以无 Java 互操作的 Rhino 环境执行有预算的 JavaScript
package io.kaffubox.core

import kotlinx.serialization.json.*
import org.mozilla.javascript.*
import java.util.concurrent.CancellationException

fun interface ScriptHost {
    fun call(toolId: String, arguments: JsonObject): JsonElement
}

fun interface ScriptRuntimePort {
    suspend fun execute(request: ScriptRequest, context: ToolContext): JsonElement
}

class JavaScriptEvaluator {
    fun execute(request: ScriptRequest, host: ScriptHost): JsonElement {
        requireArgument(request.source.length <= 2_000_000, "scriptSize")
        requireArgument(request.function.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}")), "function")
        requireArgument(request.limits.timeoutMillis in 1..300_000 && request.limits.maxInstructions in 1..100_000_000, "limits")
        val started = System.nanoTime()
        var instructions = 0L; var hostCalls = 0; var hostNanos = 0L
        val factory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                languageVersion = Context.VERSION_ES6
                isInterpretedMode = true
                instructionObserverThreshold = 10_000
                maximumInterpreterStackDepth = 128
                setClassShutter { false }
            }
            override fun observeInstructionCount(context: Context, count: Int) {
                instructions += count
                if (Thread.currentThread().isInterrupted) throw CancellationException("scriptCancelled")
                if (instructions > request.limits.maxInstructions || (System.nanoTime() - started - hostNanos) / 1_000_000 > request.limits.timeoutMillis) throw ScriptBudgetExceeded()
            }
        }
        try {
            return factory.call { engine ->
                val scope = engine.initSafeStandardObjects(null, false)
                listOf("Packages", "java", "javax", "org", "com", "edu", "net", "JavaAdapter", "JavaImporter", "getClass").forEach { ScriptableObject.deleteProperty(scope, it) }
                val api = engine.newObject(scope)
                val call = object : BaseFunction() {
                    override fun call(cx: Context, callScope: Scriptable, thisObj: Scriptable, values: Array<out Any?>): Any {
                        if (++hostCalls > request.limits.maxHostCalls) throw ScriptBudgetExceeded()
                        if (values.size != 2 || values[0] !is String) throw ToolFailure("hostCallInvalid")
                        val encoded = NativeJSON.stringify(cx, scope, values[1], null, null).toString()
                        val arguments = CoreJson.parseToJsonElement(encoded) as? JsonObject ?: throw ToolFailure("hostArgumentsInvalid")
                        val hostStart = System.nanoTime()
                        val result = try { host.call(values[0] as String, arguments).toString() } finally { hostNanos += System.nanoTime() - hostStart }
                        if (result.length > request.limits.maxResultChars) throw ToolFailure("scriptResultTooLarge")
                        return NativeJSON.parse(cx, scope, result) { _, _, _, callbackArgs -> callbackArgs[1] }
                    }
                }
                ScriptableObject.putProperty(api, "call", call)
                ScriptableObject.defineProperty(scope, "kaffu", api, ScriptableObject.READONLY or ScriptableObject.PERMANENT)
                engine.evaluateString(scope, request.source, "Plugin", 1, null)
                val function = ScriptableObject.getProperty(scope, request.function) as? org.mozilla.javascript.Function ?: throw ToolFailure("pluginFunctionMissing")
                val input = NativeJSON.parse(engine, scope, request.arguments.toString()) { _, _, _, values -> values[1] }
                val result = function.call(engine, scope, scope, arrayOf(input))
                if (result === Undefined.instance) throw ToolFailure("pluginResultMissing")
                val encoded = NativeJSON.stringify(engine, scope, result, null, null).toString()
                if (encoded.length > request.limits.maxResultChars) throw ToolFailure("scriptResultTooLarge")
                CoreJson.parseToJsonElement(encoded)
            }
        } catch (_: ScriptBudgetExceeded) {
            throw ToolFailure("scriptBudgetExceeded")
        } catch (exception: WrappedException) {
            when (val cause = exception.wrappedException) {
                is ToolFailure -> throw cause
                is ScriptBudgetExceeded -> throw ToolFailure("scriptBudgetExceeded")
                is CancellationException -> throw cause
                else -> throw ToolFailure("scriptHostFailed")
            }
        } catch (exception: RhinoException) {
            throw ToolFailure("scriptFailed", jsonObject("line" to exception.lineNumber(), "column" to exception.columnNumber()))
        }
    }
}

private class ScriptBudgetExceeded : Error()
