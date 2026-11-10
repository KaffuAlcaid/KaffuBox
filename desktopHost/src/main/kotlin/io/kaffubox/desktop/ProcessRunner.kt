// 执行参数数组形式的桌面引擎命令，限制输出并在取消或超时后终止子进程
package io.kaffubox.desktop

import io.kaffubox.core.*
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.concurrent.TimeUnit

data class ProcessResult(val exitCode: Int, val output: String, val error: String)

object ProcessRunner {
    suspend fun run(arguments: List<String>, timeoutMillis: Long = 60_000, workingDirectory: Path? = null, onLine: ((String) -> Unit)? = null): ProcessResult = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(arguments)
        workingDirectory?.let { builder.directory(it.toFile()) }
        val process = builder.start()
        try {
            coroutineScope {
                val guard = launch(Dispatchers.Default) {
                    try { delay(timeoutMillis) } finally { process.destroyForcibly(); process.inputStream.close(); process.errorStream.close() }
                }
                try {
                    val error = async(Dispatchers.IO) {
                        val tail = StringBuilder()
                        process.errorStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            val buffer = CharArray(4096)
                            while (true) { val count = reader.read(buffer); if (count < 0) break; tail.append(buffer, 0, count); if (tail.length > 64_000) tail.delete(0, tail.length - 64_000) }
                        }
                        tail.toString()
                    }
                    val output = StringBuilder()
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        var line = StringBuilder()
                        while (true) {
                            ensureActive()
                            val character = reader.read(); if (character < 0) break
                            if (output.length >= 8_000_000) throw ToolFailure("engineOutputTooLarge")
                            output.append(character.toChar())
                            if (character == 10) { onLine?.invoke(line.toString().trimEnd('\r')); line = StringBuilder() }
                            else { line.append(character.toChar()); if (line.length > 1_000_000) throw ToolFailure("engineLineTooLarge") }
                        }
                    }
                    val code = process.waitFor()
                    ProcessResult(code, output.toString(), error.await())
                } finally { guard.cancel() }
            }
        } finally { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS) }
    }
}
