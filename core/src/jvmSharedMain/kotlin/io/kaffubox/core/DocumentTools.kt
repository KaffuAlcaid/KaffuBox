// 实现 CSV 与 TSV 的往返转换、Markdown 渲染、HTML 文本提取和结构化格式转换
package io.kaffubox.core

import kotlinx.serialization.json.*
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.tomlj.Toml
import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

object DocumentTools {
    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("document.csvParse", Workbench.Document, mapOf("text" to "string", "delimiter" to "string"), listOf("text")) { args, _ ->
            val delimiter = delimiter(args)
            jsonObject("rows" to parseCsv(args.string("text"), delimiter))
        },
        defineTool("document.csvWrite", Workbench.Document, mapOf("rows" to "array", "delimiter" to "string"), listOf("rows")) { args, _ ->
            val delimiter = delimiter(args)
            val rows = args["rows"] as? JsonArray ?: throw ToolFailure("rowsRequired")
            val text = rows.joinToString("\r\n") { row -> (row as? JsonArray ?: throw ToolFailure("rowInvalid")).joinToString(delimiter.toString()) { value ->
                val cell = if (value == JsonNull) "" else (value as? JsonPrimitive)?.content ?: value.toString()
                if (cell.any { it == delimiter || it in "\r\n\"" }) "\"" + cell.replace("\"", "\"\"") + "\"" else cell
            } }
            jsonObject("text" to text)
        },
        defineTool("document.markdownToHtml", Workbench.Document, mapOf("text" to "string"), listOf("text")) { args, _ ->
            val document = Parser.builder().build().parse(args.string("text"))
            jsonObject("html" to HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build().render(document))
        },
        defineTool("document.htmlText", Workbench.Document, mapOf("html" to "string"), listOf("html")) { args, _ ->
            val html = Jsoup.parse(args.string("html")); html.select("script,style,noscript").remove()
            jsonObject("text" to html.wholeText(), "title" to html.title())
        },
        defineTool("developer.yamlToJson", Workbench.Developer, mapOf("text" to "string"), listOf("text")) { args, _ ->
            val options = LoaderOptions().apply { maxAliasesForCollections = 30; codePointLimit = 4_000_000; nestingDepthLimit = 64 }
            val parsed = Yaml(SafeConstructor(options)).load<Any?>(args.string("text"))
            jsonObject("value" to safeJson(parsed, 0))
        },
        defineTool("developer.jsonToYaml", Workbench.Developer, mapOf("text" to "string"), listOf("text")) { args, _ ->
            jsonObject("text" to Yaml().dump(toJava(CoreJson.parseToJsonElement(args.string("text")))))
        },
        defineTool("developer.tomlToJson", Workbench.Developer, mapOf("text" to "string"), listOf("text")) { args, _ ->
            val parsed = Toml.parse(args.string("text"))
            if (parsed.hasErrors()) throw ToolFailure("tomlInvalid", jsonObject("count" to parsed.errors().size))
            jsonObject("value" to safeJson(parsed.toMap(), 0))
        },
        defineTool("developer.xmlFormat", Workbench.Developer, mapOf("text" to "string"), listOf("text")) { args, _ ->
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true; isExpandEntityReferences = false
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(args.string("text"))))
            val transformerFactory = TransformerFactory.newInstance()
            try { transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) } catch (_: javax.xml.transform.TransformerConfigurationException) { }
            val transformer = transformerFactory.newTransformer().apply { setOutputProperty(OutputKeys.INDENT, "yes"); setOutputProperty(OutputKeys.ENCODING, "UTF-8") }
            val writer = StringWriter(); transformer.transform(DOMSource(document), StreamResult(writer))
            jsonObject("text" to writer.toString())
        }
    )

    private fun delimiter(args: JsonObject): Char {
        val value = args.string("delimiter", ","); requireArgument(value.length == 1 && value[0] !in "\r\n\"", "delimiter"); return value[0]
    }

    private fun parseCsv(text: String, delimiter: Char): List<List<String>> {
        requireArgument(text.length <= 16_000_000, "text")
        if (text.isEmpty()) return emptyList()
        val rows = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val field = StringBuilder()
        var quoted = false; var afterQuote = false; var position = 0
        while (position < text.length) {
            val character = text[position++]
            if (quoted) {
                if (character == '"') {
                    if (position < text.length && text[position] == '"') { field.append('"'); position++ } else { quoted = false; afterQuote = true }
                } else field.append(character)
                continue
            }
            when {
                character == '"' && field.isEmpty() && !afterQuote -> quoted = true
                character == delimiter -> { row += field.toString(); field.setLength(0); afterQuote = false }
                character == '\r' || character == '\n' -> {
                    if (character == '\r' && position < text.length && text[position] == '\n') position++
                    row += field.toString(); rows += row; row = mutableListOf(); field.setLength(0); afterQuote = false
                }
                afterQuote || character == '"' -> throw ToolFailure("csvInvalid", jsonObject("position" to position - 1))
                else -> field.append(character)
            }
            requireArgument(rows.size <= 100_000 && row.size <= 10_000, "tableSize")
        }
        if (quoted) throw ToolFailure("csvUnterminatedQuote")
        if (field.isNotEmpty() || row.isNotEmpty() || afterQuote || text.last() == delimiter) { row += field.toString(); rows += row }
        return rows
    }

    private fun safeJson(value: Any?, depth: Int): JsonElement {
        if (depth > 64) throw ToolFailure("dataDepthLimit")
        return when (value) {
            is Map<*, *> -> JsonObject(value.entries.associate { (key, item) ->
                if (key !is String) throw ToolFailure("stringKeyRequired")
                key to safeJson(item, depth + 1)
            })
            is Iterable<*> -> JsonArray(value.map { safeJson(it, depth + 1) })
            is org.tomlj.TomlTable -> safeJson(value.toMap(), depth + 1)
            is org.tomlj.TomlArray -> safeJson(value.toList(), depth + 1)
            else -> jsonValue(value)
        }
    }

    private fun toJava(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonObject -> value.mapValues { toJava(it.value) }
        is JsonArray -> value.map(::toJava)
        is JsonPrimitive -> if (value.isString) value.content else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: value.content
    }
}
