// 读取有长度限制的 JSON 行消息，供桌面宿主和隔离工作进程通信
package io.kaffubox.core

import java.io.Reader
import java.io.Writer
import kotlinx.serialization.json.JsonObject

object ProtocolIo {
    fun read(reader: Reader, maximum: Int = 4_000_000): JsonObject? {
        val text = StringBuilder()
        while (true) {
            val character = reader.read()
            if (character < 0) {
                if (text.isEmpty()) return null
                break
            }
            if (character == 10) break
            if (character != 13) text.append(character.toChar())
            if (text.length > maximum) throw ToolFailure("protocolMessageTooLarge")
        }
        return CoreJson.parseToJsonElement(text.toString()) as? JsonObject ?: throw ToolFailure("protocolObjectRequired")
    }

    fun write(writer: Writer, message: JsonObject) {
        val text = message.toString()
        if (text.length > 4_000_000) throw ToolFailure("protocolMessageTooLarge")
        synchronized(writer) { writer.write(text); writer.write("\n"); writer.flush() }
    }
}
