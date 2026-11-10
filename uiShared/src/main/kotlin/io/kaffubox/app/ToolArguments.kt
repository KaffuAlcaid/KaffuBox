// 按可见字段生成双端一致的工具参数、批处理请求和默认输出名称
package io.kaffubox.app

import io.kaffubox.core.*
import kotlinx.serialization.json.*

fun buildToolArguments(tool: UiTool, state: ToolFormState, batchKey: String? = null, batchFile: FileRef? = null): JsonObject = buildJsonObject {
        state.values["__rawArguments"]?.let { raw ->
            CoreJson.parseToJsonElement(raw).jsonObject.forEach { (key, value) -> put(key, value) }
            return@buildJsonObject
        }
        for (field in tool.fields) {
            if (!field.sendToTool || !field.isVisible(state.values)) continue
            val value = state.values[field.key].orEmpty()
            when (field.kind) {
                FieldKind.File -> {
                    val ref = if (field.key == batchKey && batchFile != null) batchFile else state.files[field.key]?.firstOrNull()
                    if (ref == null && field.required) throw ToolFailure("argumentRequired", jsonObject("key" to field.label))
                    ref?.let { put(field.key, it.id) }
                }
                FieldKind.Files -> {
                    val refs = state.files[field.key].orEmpty()
                    if (refs.isEmpty() && field.required) throw ToolFailure("argumentRequired", jsonObject("key" to field.label))
                    put(field.key, JsonArray(refs.map { JsonPrimitive(it.id) }))
                }
                FieldKind.Toggle -> put(field.key, value.toBoolean())
                else -> {
                    val empty = if (field.kind in setOf(FieldKind.Text, FieldKind.Paragraph, FieldKind.Choice)) value.isEmpty() else value.isBlank()
                    if (empty) {
                        if (field.kind == FieldKind.Paragraph && field.required) { put(field.key, value); continue }
                        if (field.required) throw ToolFailure("argumentRequired", jsonObject("key" to field.label))
                        continue
                    }
                    when (field.kind) {
                        FieldKind.Integer -> put(field.key, Math.multiplyExact(value.toLong(), field.scale))
                        FieldKind.Number -> put(field.key, JsonPrimitive(value.toBigDecimal().multiply(field.scale.toBigDecimal())))
                        FieldKind.Choice -> if (field.key == "rotate") put(field.key, value.toInt()) else put(field.key, value)
                        FieldKind.Json -> put(field.key, CoreJson.parseToJsonElement(value))
                        FieldKind.Lines -> put(field.key, JsonArray(value.lineSequence().map(String::trim).filter(String::isNotEmpty).map(::JsonPrimitive).toList()))
                        else -> put(field.key, if (field.key == "separator") value.replace("\\n", "\n").replace("\\t", "\t") else value)
                    }
                }
            }
        }
        if (tool.id == "media.videoToGif") put("originalSize", state.values["sizeMode"] == "original")
        if (tool.id in setOf("image.transform", "media.edit")) {
            for (section in if (tool.id == "media.edit") listOf("crop") else listOf("crop", "mosaic", "watermark")) {
                if (state.values[section + "Enabled"] != "true") continue
                put(section, buildJsonObject {
                    if (section == "watermark") {
                        put("text", state.values["watermarkText"].orEmpty())
                        put("x", state.values["watermarkX"]?.toIntOrNull() ?: 0)
                        put("y", state.values["watermarkY"]?.toIntOrNull() ?: 40)
                        put("size", state.values["watermarkSize"]?.toIntOrNull() ?: 24)
                        put("color", state.values["watermarkColor"] ?: "#CCFFFFFF")
                    } else {
                        put("x", state.values[section + "X"]?.toIntOrNull() ?: 0)
                        put("y", state.values[section + "Y"]?.toIntOrNull() ?: 0)
                        put("width", state.values[section + "Width"]?.toIntOrNull() ?: throw ToolFailure("integerRequired"))
                        put("height", state.values[section + "Height"]?.toIntOrNull() ?: throw ToolFailure("integerRequired"))
                        if (section == "mosaic") put("block", state.values["mosaicBlock"]?.toIntOrNull() ?: 12)
                    }
                })
            }
        }
        if (tool.fields.any { it.key == "name" } && state.values["name"].isNullOrBlank()) {
            val input = batchFile ?: state.files.values.flatten().firstOrNull()
            val extension = when (tool.id) {
                "media.videoToGif" -> "gif"; "media.edit" -> "mp4"; "media.extractFrame" -> "png"; "media.extractSubtitles" -> "srt"
                "format.mediaConvert", "media.extractAudio", "image.transform", "image.stitch" -> state.values["format"]?.let { if (it == "jpeg") "jpg" else it } ?: "png"
                else -> null
            }
            if (extension != null) put("name", (input?.name?.substringBeforeLast('.') ?: "Result") + ".$extension")
        }
    }
