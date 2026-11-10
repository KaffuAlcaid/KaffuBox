// 实现共享文本统计、行整理、替换、Unicode 转换与有界差异比较
package io.kaffubox.core

import kotlinx.serialization.json.*
import java.text.Normalizer
import java.util.Locale

object TextTools {
    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("text.statistics", Workbench.Text, mapOf("text" to "string"), listOf("text")) { args, _ ->
            val text = args.string("text")
            val words = Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value.lowercase(Locale.ROOT) }.toList()
            jsonObject(
                "codePoints" to text.codePointCount(0, text.length), "utf16Units" to text.length,
                "utf8Bytes" to text.toByteArray(Charsets.UTF_8).size,
                "lines" to if (text.isEmpty()) 0 else text.split(Regex("\\r\\n|\\r|\\n")).size,
                "tokens" to words.size,
                "frequency" to words.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(1000).associate { it.key to it.value }
            )
        },
        defineTool("text.lines", Workbench.Text, mapOf("text" to "string", "trim" to "boolean", "removeEmpty" to "boolean", "deduplicate" to "boolean", "sort" to "string"), listOf("text")) { args, _ ->
            var lines = args.string("text").split(Regex("\\r\\n|\\r|\\n"))
            if (args.flag("trim")) lines = lines.map(String::trim)
            if (args.flag("removeEmpty")) lines = lines.filter(String::isNotBlank)
            if (args.flag("deduplicate")) lines = lines.distinct()
            lines = when (args.string("sort", "none")) {
                "none" -> lines
                "ascending" -> lines.sorted()
                "descending" -> lines.sortedDescending()
                "numeric" -> lines.sortedWith(compareBy<String> { it.toBigDecimalOrNull() == null }.thenBy { it.toBigDecimalOrNull() }.thenBy { it })
                else -> throw ToolFailure("sortInvalid")
            }
            jsonObject("text" to lines.joinToString(args.string("separator", "\n")), "count" to lines.size)
        },
        defineTool("text.replace", Workbench.Text, mapOf("text" to "string", "find" to "string", "replacement" to "string"), listOf("text", "find")) { args, _ ->
            val find = args.string("find")
            requireArgument(find.isNotEmpty(), "find")
            jsonObject("text" to args.string("text").replace(find, args.string("replacement", ""), args.flag("ignoreCase")))
        },
        defineTool("text.unicode", Workbench.Text, mapOf("text" to "string", "mode" to "string"), listOf("text")) { args, _ ->
            val text = args.string("text")
            when (val mode = args.string("mode", "codePoints")) {
                "codePoints" -> jsonObject("values" to text.codePoints().toArray().map { "U+" + it.toString(16).uppercase(Locale.ROOT).padStart(4, '0') })
                "escape" -> jsonObject("text" to text.map { "\\u" + it.code.toString(16).padStart(4, '0') }.joinToString(""))
                "unescape" -> jsonObject("text" to Regex("\\\\u([0-9a-fA-F]{4})").replace(text) { it.groupValues[1].toInt(16).toChar().toString() })
                "NFC", "NFD", "NFKC", "NFKD" -> jsonObject("text" to Normalizer.normalize(text, Normalizer.Form.valueOf(mode)))
                else -> throw ToolFailure("unicodeModeInvalid")
            }
        },
        defineTool("text.diff", Workbench.Text, mapOf("left" to "string", "right" to "string"), listOf("left", "right")) { args, context ->
            val left = args.string("left").split('\n')
            val right = args.string("right").split('\n')
            requireArgument(left.size.toLong() * right.size <= 4_000_000, "lineCount")
            val lengths = Array(left.size + 1) { IntArray(right.size + 1) }
            for (i in left.lastIndex downTo 0) {
                context.checkpoint()
                for (j in right.lastIndex downTo 0) lengths[i][j] = if (left[i] == right[j]) lengths[i + 1][j + 1] + 1 else maxOf(lengths[i + 1][j], lengths[i][j + 1])
            }
            val changes = mutableListOf<JsonObject>()
            var i = 0; var j = 0
            while (i < left.size || j < right.size) {
                when {
                    i < left.size && j < right.size && left[i] == right[j] -> { changes += jsonObject("kind" to "equal", "leftLine" to i + 1, "rightLine" to j + 1, "text" to left[i]); i++; j++ }
                    j < right.size && (i == left.size || lengths[i][j + 1] >= lengths[i + 1][j]) -> { changes += jsonObject("kind" to "insert", "rightLine" to j + 1, "text" to right[j]); j++ }
                    else -> { changes += jsonObject("kind" to "delete", "leftLine" to i + 1, "text" to left[i]); i++ }
                }
            }
            jsonObject("equal" to (left == right), "changes" to changes)
        }
    )
}
