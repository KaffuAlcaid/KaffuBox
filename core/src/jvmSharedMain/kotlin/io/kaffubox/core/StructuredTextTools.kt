// 在 JSON、YAML、TOML 与保留 XML 节点顺序的 JSON 表示之间转换，不解析外部实体
package io.kaffubox.core

import kotlinx.serialization.json.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter

object StructuredTextTools {
    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("document.convert", Workbench.Document, mapOf("text" to "string", "from" to "string", "to" to "string"), listOf("text", "from", "to")) { args, context ->
            val text = args.string("text"); requireArgument(text.length <= 4_000_000, "text")
            val value = when (args.string("from")) {
                "json" -> CoreJson.parseToJsonElement(text)
                "yaml" -> context.invoke("developer.yamlToJson", jsonObject("text" to text)).jsonObject.getValue("value")
                "toml" -> context.invoke("developer.tomlToJson", jsonObject("text" to text)).jsonObject.getValue("value")
                "xml" -> readXml(factory().newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement, 0)
                else -> throw ToolFailure("argumentInvalid")
            }
            val output = when (args.string("to")) {
                "json" -> Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), value)
                "yaml" -> context.invoke("developer.jsonToYaml", jsonObject("text" to value.toString())).jsonObject.string("text")
                "toml" -> { val obj = value as? JsonObject ?: throw ToolFailure("tomlRootObjectRequired"); obj.entries.joinToString("\n") { "${JsonPrimitive(it.key)} = ${toml(it.value, 0)}" }.also { requireArgument(!org.tomlj.Toml.parse(it).hasErrors(), "tomlValueUnsupported") } }
                "xml" -> writeXml(value)
                else -> throw ToolFailure("argumentInvalid")
            }
            jsonObject("text" to output, "structure" to value, "format" to args.string("to"))
        }
    )
    private fun factory() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = false; isExpandEntityReferences = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }
    private fun readXml(element: Element, depth: Int): JsonObject {
        requireArgument(depth <= 64, "dataDepthLimit")
        return jsonObject("tag" to element.tagName, "attributes" to (0 until element.attributes.length).associate { element.attributes.item(it).nodeName to element.attributes.item(it).nodeValue }, "content" to (0 until element.childNodes.length).mapNotNull { index ->
            val child = element.childNodes.item(index)
            when (child.nodeType) { Node.ELEMENT_NODE -> readXml(child as Element, depth + 1); Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> JsonPrimitive(child.nodeValue); else -> null }
        })
    }
    private fun writeXml(value: JsonElement): String {
        val document = factory().newDocumentBuilder().newDocument()
        fun node(value: JsonElement, depth: Int): Node {
            requireArgument(depth <= 64, "dataDepthLimit")
            if (value is JsonPrimitive && value.isString) return document.createTextNode(value.content)
            val obj = value as? JsonObject ?: throw ToolFailure("xmlStructureRequired")
            val name = (obj["tag"] as? JsonPrimitive)?.content ?: throw ToolFailure("xmlStructureRequired")
            val element = document.createElement(name)
            (obj["attributes"] as? JsonObject).orEmpty().forEach { (key, item) -> element.setAttribute(key, item.jsonPrimitive.content) }
            (obj["content"] as? JsonArray).orEmpty().forEach { element.appendChild(node(it, depth + 1)) }
            return element
        }
        document.appendChild(node(value, 0))
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.ENCODING, "UTF-8") }.transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }
    private fun toml(value: JsonElement, depth: Int): String {
        requireArgument(depth <= 64 && value != JsonNull, "tomlValueUnsupported")
        return when (value) {
            is JsonObject -> value.entries.joinToString(", ", "{", "}") { "${JsonPrimitive(it.key)} = ${toml(it.value, depth + 1)}" }
            is JsonArray -> value.joinToString(", ", "[", "]") { toml(it, depth + 1) }
            else -> value.toString()
        }
    }
}
