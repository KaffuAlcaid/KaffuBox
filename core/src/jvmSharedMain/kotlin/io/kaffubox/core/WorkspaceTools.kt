// 提供可组合的网络诊断、受限正则替换、文本规范化、向量与高精度复数计算
package io.kaffubox.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.MathContext
import java.net.URI
import java.nio.charset.Charset
import java.util.Base64
import java.util.regex.Pattern
import ch.obermuhlner.math.big.BigDecimalMath

object WorkspaceTools {
    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("network.diagnose", Workbench.Network, mapOf("target" to "string", "dns" to "boolean", "tcp" to "boolean", "tls" to "boolean", "http" to "boolean", "port" to "integer"), listOf("target"), setOf(Capability.Network)) { args, context ->
            val target = args.string("target").trim()
            val uri = URI(if (target.contains("://")) target else "https://$target")
            requireArgument(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null, "target")
            val port = args.int("port", if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80)
            requireArgument(port in 1..65535, "port")
            val steps = listOf(
                Triple("dns", "network.dns", jsonObject("host" to uri.host)),
                Triple("tcp", "network.tcp", jsonObject("host" to uri.host, "port" to port, "timeoutMillis" to 5000)),
                Triple("tls", "network.tls", jsonObject("host" to uri.host, "port" to port)),
                Triple("http", "network.http", jsonObject("url" to uri.toString(), "timeoutMillis" to 10000))
            ).filter { args.flag(it.first, it.first != "tls" || uri.scheme == "https") }
            requireArgument(steps.isNotEmpty(), "steps")
            val results = mutableListOf<JsonObject>()
            for ((index, step) in steps.withIndex()) {
                context.checkpoint(index.toLong(), steps.size.toLong(), step.first)
                try { results += jsonObject("toolId" to step.second, "result" to context.invoke(step.second, step.third)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { results += jsonObject("toolId" to step.second, "error" to if (failure is ToolFailure) failure.code else failure.javaClass.simpleName, "details" to (failure as? ToolFailure)?.details) }
            }
            context.checkpoint(steps.size.toLong(), steps.size.toLong(), "complete")
            jsonObject("target" to target, "steps" to results)
        },
        defineTool("text.regexReplace", Workbench.Text, mapOf("text" to "string", "pattern" to "string", "replacement" to "string", "ignoreCase" to "boolean", "multiline" to "boolean", "literalReplacement" to "boolean"), listOf("text", "pattern")) { args, _ ->
            val text = args.string("text"); requireArgument(text.length <= 2_000_000, "text")
            val coroutine = currentCoroutineContext()
            val input = TimedText(text, System.nanoTime() + 2_000_000_000L) { coroutine.ensureActive() }
            val pattern = safePattern(args.string("pattern"), (if (args.flag("ignoreCase")) Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE else 0) or (if (args.flag("multiline")) Pattern.MULTILINE else 0))
            val replacement = args.string("replacement", "").let { if (args.flag("literalReplacement")) java.util.regex.Matcher.quoteReplacement(it) else it }
            val matcher = pattern.matcher(input); val output = StringBuffer(); var count = 0
            try { while (matcher.find()) { coroutine.ensureActive(); matcher.appendReplacement(output, replacement); count++; requireArgument(output.length <= 8_000_000, "outputSize") } }
            catch (_: StackOverflowError) { throw ToolFailure("regexTimeout") }
            matcher.appendTail(output)
            jsonObject("text" to output.toString(), "count" to count)
        },
        defineTool("text.normalize", Workbench.Text, mapOf("text" to "string", "newline" to "string", "trimLines" to "boolean", "trimEnd" to "boolean", "tabs" to "integer", "finalNewline" to "boolean"), listOf("text")) { args, _ ->
            val separator = when (args.string("newline", "lf")) { "lf" -> "\n"; "crlf" -> "\r\n"; "cr" -> "\r"; else -> throw ToolFailure("argumentInvalid") }
            val tabs = args.int("tabs", 0); requireArgument(tabs in 0..16, "tabs")
            var text = args.string("text").replace("\r\n", "\n").replace('\r', '\n').split('\n').joinToString(separator) { line ->
                var value = if (args.flag("trimLines")) line.trim() else if (args.flag("trimEnd", true)) line.trimEnd() else line
                if (tabs > 0) value = value.replace("\t", " ".repeat(tabs))
                value
            }
            if (args.flag("finalNewline") && !text.endsWith(separator)) text += separator
            jsonObject("text" to text)
        },
        defineTool("text.charset", Workbench.Text, mapOf("text" to "string", "charset" to "string", "mode" to "string"), listOf("text")) { args, _ ->
            val charset = Charset.forName(args.string("charset", "UTF-8"))
            val value = if (args.string("mode", "encode") == "decode") charset.newDecoder().decode(java.nio.ByteBuffer.wrap(Base64.getDecoder().decode(args.string("text")))).toString()
            else {
                val buffer = charset.newEncoder().encode(java.nio.CharBuffer.wrap(args.string("text")))
                Base64.getEncoder().encodeToString(ByteArray(buffer.remaining()).also { buffer.get(it) })
            }
            jsonObject("text" to value)
        },
        defineTool("science.vector", Workbench.Science, mapOf("left" to "array", "right" to "array", "operation" to "string", "precision" to "integer"), listOf("left", "operation")) { args, _ ->
            val math = precision(args)
            fun vector(key: String) = (args[key] as? JsonArray)?.map { decimalArgument(it, key) } ?: throw ToolFailure("argumentRequired")
            val a = vector("left"); requireArgument(a.size in 1..256, "dimensions")
            val operation = args.string("operation")
            val b = if (operation in setOf("norm", "normalize")) a else vector("right").also { requireArgument(it.size == a.size, "dimensions") }
            fun dot(x: List<BigDecimal>, y: List<BigDecimal>) = x.indices.fold(BigDecimal.ZERO) { sum, i -> sum.add(x[i].multiply(y[i], math), math) }
            fun norm(x: List<BigDecimal>) = BigDecimalMath.sqrt(dot(x, x), math)
            val result: Any = when (operation) {
                "add" -> a.indices.map { a[it].add(b[it], math) }; "subtract" -> a.indices.map { a[it].subtract(b[it], math) }
                "dot" -> dot(a, b); "norm" -> norm(a)
                "normalize" -> { val length = norm(a); requireArgument(length.signum() != 0, "zeroVector"); a.map { it.divide(length, math) } }
                "cross" -> { requireArgument(a.size == 3, "dimensions"); (0..2).map { i -> a[(i + 1) % 3].multiply(b[(i + 2) % 3], math).subtract(a[(i + 2) % 3].multiply(b[(i + 1) % 3], math), math) } }
                else -> throw ToolFailure("argumentInvalid")
            }
            jsonObject("value" to result)
        },
        defineTool("science.complex", Workbench.Science, mapOf("realA" to "number", "imaginaryA" to "number", "realB" to "number", "imaginaryB" to "number", "operation" to "string", "precision" to "integer"), listOf("realA", "imaginaryA", "operation")) { args, _ ->
            val m = precision(args)
            val a = decimalArgument(args["realA"], "realA"); val b = decimalArgument(args["imaginaryA"], "imaginaryA")
            val c = decimalArgument(args["realB"] ?: JsonPrimitive(0), "realB"); val d = decimalArgument(args["imaginaryB"] ?: JsonPrimitive(0), "imaginaryB")
            val (real, imaginary) = when (args.string("operation")) {
                "add" -> a.add(c, m) to b.add(d, m); "subtract" -> a.subtract(c, m) to b.subtract(d, m)
                "multiply" -> a.multiply(c, m).subtract(b.multiply(d, m), m) to a.multiply(d, m).add(b.multiply(c, m), m)
                "divide" -> { val denominator = c.multiply(c, m).add(d.multiply(d, m), m); if (denominator.signum() == 0) throw ToolFailure("mathDivideByZero"); a.multiply(c, m).add(b.multiply(d, m), m).divide(denominator, m) to b.multiply(c, m).subtract(a.multiply(d, m), m).divide(denominator, m) }
                "conjugate" -> a to b.negate(); "modulus" -> BigDecimalMath.sqrt(a.multiply(a, m).add(b.multiply(b, m), m), m) to BigDecimal.ZERO
                else -> throw ToolFailure("argumentInvalid")
            }
            jsonObject("real" to real, "imaginary" to imaginary, "text" to (real.stripTrailingZeros().toPlainString() + (if (imaginary.signum() < 0) " − " else " + ") + imaginary.abs().stripTrailingZeros().toPlainString() + "i"))
        }
    )
    private fun precision(args: JsonObject) = MathContext(args.int("precision", 34).also { requireArgument(it in 8..200, "precision") })
}

internal class TimedText(private val text: String, private val deadline: Long, private val checkpoint: () -> Unit) : CharSequence {
    private var reads = 0
    override val length: Int get() = text.length
    override fun get(index: Int): Char { if (++reads % 256 == 0) { checkpoint(); if (System.nanoTime() > deadline) throw ToolFailure("regexTimeout") }; return text[index] }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = TimedText(text.substring(startIndex, endIndex), deadline, checkpoint)
    override fun toString() = text
}

internal fun safePattern(pattern: String, flags: Int = 0): Pattern {
    requireArgument(pattern.length <= 65536, "patternLength")
    return try { Pattern.compile(pattern, flags) } catch (_: StackOverflowError) { throw ToolFailure("regexTimeout") }
}
