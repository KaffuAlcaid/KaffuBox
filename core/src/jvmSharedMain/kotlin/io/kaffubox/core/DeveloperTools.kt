// 实现 JSON、编码、哈希、JWT、UUID、进制、时间戳与二维码的共享开发工具
package io.kaffubox.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.serialization.json.*
import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

object DeveloperTools {
    private val secureRandom = SecureRandom()
    private val prettyJson = Json { prettyPrint = true }
    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("developer.json", Workbench.Developer, mapOf("text" to "string", "pretty" to "boolean", "sortKeys" to "boolean"), listOf("text")) { args, _ ->
            val parsed = CoreJson.parseToJsonElement(args.string("text"))
            val value = if (args.flag("sortKeys")) sorted(parsed) else parsed
            jsonObject("text" to (if (args.flag("pretty", true)) prettyJson else CoreJson).encodeToString(JsonElement.serializer(), value))
        },
        defineTool("developer.encoding", Workbench.Developer, mapOf("text" to "string", "format" to "string", "decode" to "boolean"), listOf("text", "format")) { args, _ ->
            val text = args.string("text")
            val decode = args.flag("decode")
            val bytes = text.toByteArray(Charsets.UTF_8)
            val result = when (args.string("format")) {
                "base64" -> if (decode) strictUtf8(Base64.getDecoder().decode(text)) else Base64.getEncoder().encodeToString(bytes)
                "base64Url" -> if (decode) strictUtf8(Base64.getUrlDecoder().decode(text)) else Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                "hex" -> if (decode) strictUtf8(decodeHex(text)) else bytes.toHex()
                "url" -> if (decode) URLDecoder.decode(text.replace("+", "%2B"), "UTF-8") else URLEncoder.encode(text, "UTF-8").replace("+", "%20").replace("%7E", "~")
                "form" -> if (decode) URLDecoder.decode(text, "UTF-8") else URLEncoder.encode(text, "UTF-8")
                "base32" -> if (decode) strictUtf8(decodeBase32(text)) else encodeBase32(bytes)
                else -> throw ToolFailure("encodingInvalid")
            }
            jsonObject("text" to result)
        },
        defineTool("developer.hash", Workbench.Developer, mapOf("text" to "string", "algorithm" to "string"), listOf("text")) { args, _ ->
            val algorithm = args.string("algorithm", "SHA-256")
            requireArgument(algorithm in setOf("SHA-256", "SHA-512", "SHA-1", "MD5"), "algorithm")
            jsonObject("hash" to MessageDigest.getInstance(algorithm).digest(args.string("text").toByteArray(Charsets.UTF_8)).toHex())
        },
        defineTool("developer.jwt", Workbench.Developer, mapOf("token" to "string"), listOf("token")) { args, _ ->
            val parts = args.string("token").split('.')
            requireArgument(parts.size == 3, "token")
            jsonObject("header" to CoreJson.parseToJsonElement(strictUtf8(Base64.getUrlDecoder().decode(parts[0]))), "payload" to CoreJson.parseToJsonElement(strictUtf8(Base64.getUrlDecoder().decode(parts[1]))), "signatureVerified" to false)
        },
        defineTool("developer.uuid", Workbench.Developer, mapOf("count" to "integer")) { args, _ ->
            val count = args.int("count", 1)
            requireArgument(count in 1..10_000, "count")
            jsonObject("values" to List(count) { UUID.randomUUID().toString() })
        },
        defineTool("developer.radix", Workbench.Developer, mapOf("value" to "string", "from" to "integer", "to" to "integer"), listOf("value", "from", "to")) { args, _ ->
            val from = args.int("from"); val to = args.int("to")
            requireArgument(from in 2..36 && to in 2..36, "radix")
            val value = args.string("value"); requireArgument(value.length <= 100_000, "value")
            jsonObject("value" to BigInteger(value, from).toString(to))
        },
        defineTool("developer.timestamp", Workbench.Developer, mapOf("value" to "string", "unit" to "string")) { args, _ ->
            val value = args.optionalString("value")
            val instant = when {
                value == null -> Instant.now()
                value.toLongOrNull() != null -> when (args.string("unit", "milliseconds")) {
                    "seconds" -> Instant.ofEpochSecond(value.toLong())
                    "milliseconds" -> Instant.ofEpochMilli(value.toLong())
                    else -> throw ToolFailure("timeUnitInvalid")
                }
                else -> OffsetDateTime.parse(value).toInstant()
            }
            jsonObject("iso" to DateTimeFormatter.ISO_INSTANT.format(instant), "seconds" to instant.epochSecond, "milliseconds" to instant.toEpochMilli())
        },
        defineTool("developer.password", Workbench.Developer, mapOf("length" to "integer", "alphabet" to "string")) { args, _ ->
            val length = args.int("length", 24)
            val alphabet = args.string("alphabet", "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%+-_=").toCharArray().distinct()
            requireArgument(length in 1..4096 && alphabet.size in 2..1024, "lengthOrAlphabet")
            jsonObject("value" to buildString { repeat(length) { append(alphabet[secureRandom.nextInt(alphabet.size)]) } })
        },
        defineTool("developer.qrCode", Workbench.Developer, mapOf("text" to "string", "size" to "integer"), listOf("text")) { args, _ ->
            val size = args.int("size", 256); requireArgument(size in 64..2048, "size")
            val matrix = MultiFormatWriter().encode(args.string("text"), BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4))
            val svg = buildString {
                append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ${matrix.width} ${matrix.height}\"><path fill=\"white\" d=\"M0 0H${matrix.width}V${matrix.height}H0z\"/><path fill=\"black\" d=\"")
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) if (matrix[x, y]) append("M$x ${y}h1v1h-1z")
                append("\"/></svg>")
            }
            jsonObject("svg" to svg, "width" to matrix.width, "height" to matrix.height)
        }
    )

    private fun sorted(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { sorted(it.value) })
        is JsonArray -> JsonArray(value.map(::sorted))
        else -> value
    }

    private const val Base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private fun encodeBase32(bytes: ByteArray): String = buildString {
        var buffer = 0; var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 255); bits += 8
            while (bits >= 5) { bits -= 5; append(Base32Alphabet[(buffer ushr bits) and 31]) }
        }
        if (bits > 0) append(Base32Alphabet[(buffer shl (5 - bits)) and 31])
        while (length % 8 != 0) append('=')
    }

    private fun decodeBase32(text: String): ByteArray {
        val clean = text.trim().uppercase(java.util.Locale.ROOT)
        requireArgument(clean.matches(Regex("[A-Z2-7]*={0,6}")), "text")
        val body = clean.trimEnd('=')
        requireArgument(body.length % 8 in setOf(0, 2, 4, 5, 7), "text")
        val output = java.io.ByteArrayOutputStream(); var buffer = 0; var bits = 0
        for (character in body) {
            buffer = (buffer shl 5) or Base32Alphabet.indexOf(character); bits += 5
            if (bits >= 8) { bits -= 8; output.write((buffer ushr bits) and 255) }
        }
        requireArgument(bits == 0 || (buffer and ((1 shl bits) - 1)) == 0, "text")
        if ('=' in clean) requireArgument(clean.length % 8 == 0, "text")
        return output.toByteArray()
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
fun decodeHex(text: String): ByteArray {
    requireArgument(text.length % 2 == 0 && text.all { it.digitToIntOrNull(16) != null }, "text")
    return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
