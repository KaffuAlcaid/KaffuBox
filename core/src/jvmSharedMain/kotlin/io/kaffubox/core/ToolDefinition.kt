// 构造无界面文案的工具描述与 JSON 参数模式，统一内置工具的注册方式
package io.kaffubox.core

import kotlinx.serialization.json.*

typealias ToolDefinition = Pair<ToolDescriptor, ToolOperation>

fun defineTool(
    id: String,
    workbench: Workbench,
    fields: Map<String, String> = emptyMap(),
    required: List<String> = emptyList(),
    capabilities: Set<Capability> = emptySet(),
    operation: ToolOperation
): ToolDefinition = ToolDescriptor(
    id, workbench, capabilities,
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            fields.forEach { (key, type) -> put(key, buildJsonObject { put("type", type) }) }
        }
        put("required", JsonArray(required.map(::JsonPrimitive)))
        put("additionalProperties", true)
    }
) to operation
