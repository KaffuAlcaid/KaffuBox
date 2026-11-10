// 提供统一 JSON 编解码、严格参数读取和可供宿主识别的错误代码
package io.kaffubox.core

import kotlinx.serialization.json.*

val CoreJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = false
}

class ToolFailure(val code: String, val details: JsonObject = JsonObject(emptyMap())) : RuntimeException(code)

fun JsonObject.string(key: String, default: String? = null): String {
    if (!containsKey(key)) return default ?: throw ToolFailure("argumentRequired", jsonObject("key" to key))
    return (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw ToolFailure("stringRequired", jsonObject("key" to key))
}

fun JsonObject.optionalString(key: String): String? =
    if (!containsKey(key) || get(key) == JsonNull) null else string(key)

fun JsonObject.long(key: String, default: Long? = null): Long {
    if (!containsKey(key)) return default ?: throw ToolFailure("integerRequired", jsonObject("key" to key))
    return (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw ToolFailure("integerRequired", jsonObject("key" to key))
}

fun JsonObject.int(key: String, default: Int? = null): Int {
    val value = long(key, default?.toLong())
    if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw ToolFailure("integerOutOfRange", jsonObject("key" to key))
    return value.toInt()
}

fun JsonObject.flag(key: String, default: Boolean = false): Boolean {
    if (!containsKey(key)) return default
    return (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: throw ToolFailure("booleanRequired", jsonObject("key" to key))
}

fun JsonObject.number(key: String, default: Double? = null): Double {
    val value = if (!containsKey(key)) default ?: throw ToolFailure("numberRequired", jsonObject("key" to key))
        else (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: throw ToolFailure("numberRequired", jsonObject("key" to key))
    if (!value.isFinite()) throw ToolFailure("finiteNumberRequired", jsonObject("key" to key))
    return value
}

fun JsonObject.strings(key: String): List<String> = (get(key) as? JsonArray)?.map {
    (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: throw ToolFailure("stringArrayRequired", jsonObject("key" to key))
} ?: throw ToolFailure("arrayRequired", jsonObject("key" to key))

fun JsonObject.objectValue(key: String, default: JsonObject = JsonObject(emptyMap())): JsonObject =
    get(key)?.let { it as? JsonObject ?: throw ToolFailure("objectRequired", jsonObject("key" to key)) } ?: default

fun jsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to jsonValue(it.value) })
    is Iterable<*> -> JsonArray(value.map(::jsonValue))
    is Array<*> -> JsonArray(value.map(::jsonValue))
    else -> JsonPrimitive(value.toString())
}

fun jsonObject(vararg values: Pair<String, Any?>): JsonObject =
    JsonObject(values.associate { it.first to jsonValue(it.second) })

fun requireArgument(condition: Boolean, key: String) {
    if (!condition) throw ToolFailure("argumentInvalid", jsonObject("key" to key))
}
