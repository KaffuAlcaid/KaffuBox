// 在独立受内存限制的 JVM 工作进程中执行脚本，并代理受控的宿主工具调用
package io.kaffubox.desktop

import io.kaffubox.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class DesktopScriptRuntime(private val workingRoot: Path) : ScriptRuntimePort {
    init { Files.createDirectories(workingRoot) }
    override suspend fun execute(request: ScriptRequest, context: ToolContext): JsonElement = withContext(Dispatchers.IO) {
        val directory = workingRoot.resolve("Worker" + UUID.randomUUID().toString().replace("-", ""))
        Files.createDirectory(directory)
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString()
        val classpath = System.getProperty("java.class.path").split(File.pathSeparator).joinToString(File.pathSeparator) { File(it).absolutePath }
        val builder = ProcessBuilder(javaExecutable, "-Xmx128m", "-Xss512k", "-XX:-UsePerfData", "-Dfile.encoding=UTF-8", "-cp", classpath, "io.kaffubox.desktop.DesktopMainKt", "--scriptWorker")
            .directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD)
        val environment = builder.environment()
        val systemRoot = environment["SystemRoot"]
        environment.clear()
        if (systemRoot != null) environment["SystemRoot"] = systemRoot
        environment["TEMP"] = directory.toString(); environment["TMP"] = directory.toString()
        val process = builder.start()
        try {
            coroutineScope {
                val hostCallActive = AtomicBoolean()
                val guard = launch(Dispatchers.Default) {
                    try {
                        var activeMillis = 0L
                        while (activeMillis <= request.limits.timeoutMillis + 3000) {
                            delay(50)
                            if (!hostCallActive.get()) activeMillis += 50
                        }
                    } finally { process.destroyForcibly(); process.inputStream.close() }
                }
                try {
                    val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
                    val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
                    ProtocolIo.write(writer, jsonObject("request" to CoreJson.encodeToJsonElement(request)))
                    var calls = 0
                    while (true) {
                        context.checkpoint()
                        val message = ProtocolIo.read(reader) ?: throw ToolFailure("scriptWorkerStopped")
                        when (message.string("kind")) {
                            "result" -> return@coroutineScope message["value"] ?: JsonNull
                            "error" -> throw ToolFailure(message.string("code"), message.objectValue("details"))
                            "hostCall" -> {
                                if (++calls > request.limits.maxHostCalls) throw ToolFailure("scriptHostCallLimit")
                                hostCallActive.set(true)
                                val reply = try {
                                    jsonObject("kind" to "result", "value" to context.invoke(message.string("toolId"), message.objectValue("arguments")))
                                } catch (failure: ToolFailure) { jsonObject("kind" to "error", "code" to failure.code, "details" to failure.details) }
                                finally { hostCallActive.set(false) }
                                ProtocolIo.write(writer, reply)
                            }
                            else -> throw ToolFailure("scriptProtocolInvalid")
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") JsonNull
                } finally { guard.cancel() }
            }
        } finally {
            process.destroyForcibly()
            process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            process.inputStream.close()
            process.outputStream.close()
            process.errorStream.close()
            deletePrivateTree(workingRoot, directory)
        }
    }
}

fun runScriptWorker() {
    val reader = System.`in`.bufferedReader(Charsets.UTF_8)
    val writer = System.out.bufferedWriter(Charsets.UTF_8)
    try {
        val message = ProtocolIo.read(reader) ?: return
        val request = CoreJson.decodeFromJsonElement(ScriptRequest.serializer(), message.getValue("request"))
        val result = JavaScriptEvaluator().execute(request) { toolId, arguments ->
            ProtocolIo.write(writer, jsonObject("kind" to "hostCall", "toolId" to toolId, "arguments" to arguments))
            val reply = ProtocolIo.read(reader) ?: throw ToolFailure("scriptHostClosed")
            if (reply.string("kind") == "error") throw ToolFailure(reply.string("code"), reply.objectValue("details"))
            reply["value"] ?: JsonNull
        }
        ProtocolIo.write(writer, jsonObject("kind" to "result", "value" to result))
    } catch (failure: ToolFailure) { ProtocolIo.write(writer, jsonObject("kind" to "error", "code" to failure.code, "details" to failure.details)) }
    catch (failure: Exception) { ProtocolIo.write(writer, jsonObject("kind" to "error", "code" to "scriptFailed")) }
}
