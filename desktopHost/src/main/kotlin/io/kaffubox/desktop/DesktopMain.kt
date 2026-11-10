// 通过 JSON 行协议提供桌面核心调用和脚本工作进程入口
package io.kaffubox.desktop

import io.kaffubox.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

fun main(arguments: Array<String>) {
    if (arguments.contentEquals(arrayOf("--scriptWorker"))) { runScriptWorker(); return }
    val options = mutableMapOf<String, String>()
    var index = 0
    while (index < arguments.size) {
        val key = arguments[index++]
        if (key == "--allowUnsignedPlugins") options[key] = "true"
        else {
            require(key in setOf("--dataDir", "--outputDir", "--ffmpeg", "--ffprobe") && index < arguments.size)
            options[key] = arguments[index++]
        }
    }
    val dataRoot = Path.of(options["--dataDir"] ?: throw IllegalArgumentException("dataDirRequired")).toAbsolutePath().normalize()
    Files.createDirectories(dataRoot)
    val files = DesktopFileBroker(Path.of(options["--outputDir"] ?: dataRoot.resolve("Outputs").toString()))
    val platform = PlatformInfo(if (System.getProperty("os.name").startsWith("Windows")) "windows" else "desktop", System.getProperty("os.version"), System.getProperty("os.arch"))
    val runtime = DesktopScriptRuntime(dataRoot.resolve("Workers"))
    val tools = DesktopDeviceTools.definitions() + DesktopMediaTools(files, options["--ffmpeg"], options["--ffprobe"]).definitions() + ImageTools(DesktopImageCodec()).definitions()
    KaffuHost(files, platform, dataRoot, runtime, options["--allowUnsignedPlugins"] == "true", tools).use { host ->
        val writer = System.out.bufferedWriter(Charsets.UTF_8)
        val reader = System.`in`.bufferedReader(Charsets.UTF_8)
        runBlocking {
            supervisorScope {
                while (true) {
                    val request = try { withContext(Dispatchers.IO) { ProtocolIo.read(reader) } }
                    catch (failure: Exception) { ProtocolIo.write(writer, jsonObject("id" to null, "error" to jsonObject("code" to "requestInvalid"))); continue }
                    if (request == null) break
                    launch {
                        val id = request["id"] ?: JsonNull
                        try {
                            val method = request.string("method"); val params = request.objectValue("params")
                            val result = when (method) {
                                "files.register" -> CoreJson.encodeToJsonElement(files.register(Path.of(params.string("path"))))
                                else -> host.dispatch(method, params)
                            }
                            ProtocolIo.write(writer, jsonObject("id" to id, "result" to result))
                        } catch (failure: CancellationException) { throw failure }
                        catch (failure: ToolFailure) { ProtocolIo.write(writer, jsonObject("id" to id, "error" to jsonObject("code" to failure.code, "details" to failure.details))) }
                        catch (failure: Exception) { ProtocolIo.write(writer, jsonObject("id" to id, "error" to jsonObject("code" to "requestFailed", "type" to failure.javaClass.simpleName))) }
                    }
                }
            }
        }
    }
}
