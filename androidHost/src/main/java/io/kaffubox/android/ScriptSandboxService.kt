// 在 Android 无应用权限的隔离服务中执行脚本，通过文件描述符与宿主交换受限 JSON 消息
package io.kaffubox.android

import android.app.Service
import android.content.Intent
import android.os.*
import io.kaffubox.core.*
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

class ScriptSandboxService : Service() {
    private val executor = Executors.newFixedThreadPool(2)
    private val sessions = ConcurrentHashMap<String, Session>()
    private class Session(val input: ParcelFileDescriptor, val output: ParcelFileDescriptor) {
        @Volatile var future: Future<*>? = null
        fun close() { future?.cancel(true); try { input.close() } catch (_: Exception) { }; try { output.close() } catch (_: Exception) { } }
    }

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(InterfaceName); return true }
            data.enforceInterface(InterfaceName)
            when (code) {
                Start -> {
                    val id = data.readString() ?: return false
                    val input = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    val output = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                    if (sessions.size >= 8 || sessions.containsKey(id)) { input.close(); output.close(); throw IllegalStateException("scriptCapacityExceeded") }
                    val session = Session(input, output); sessions[id] = session
                    session.future = executor.submit {
                        try { runSession(session) } finally { sessions.remove(id); try { input.close() } catch (_: Exception) { }; try { output.close() } catch (_: Exception) { } }
                    }
                    reply?.writeNoException(); return true
                }
                Cancel -> {
                    data.readString()?.let { sessions.remove(it)?.close() }
                    reply?.writeNoException(); return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() { sessions.values.forEach(Session::close); sessions.clear(); executor.shutdownNow(); super.onDestroy() }

    private fun runSession(session: Session) {
        val reader = ParcelFileDescriptor.AutoCloseInputStream(session.input).bufferedReader(Charsets.UTF_8)
        val writer = ParcelFileDescriptor.AutoCloseOutputStream(session.output).bufferedWriter(Charsets.UTF_8)
        try {
            val message = ProtocolIo.read(reader) ?: return
            val request = CoreJson.decodeFromJsonElement(ScriptRequest.serializer(), message.getValue("request"))
            val result = JavaScriptEvaluator().execute(request) { toolId, arguments ->
                ProtocolIo.write(writer, jsonObject("kind" to "hostCall", "toolId" to toolId, "arguments" to arguments))
                val response = ProtocolIo.read(reader) ?: throw ToolFailure("scriptHostClosed")
                if (response.string("kind") == "error") throw ToolFailure(response.string("code"), response.objectValue("details"))
                response["value"] ?: JsonNull
            }
            ProtocolIo.write(writer, jsonObject("kind" to "result", "value" to result))
        } catch (failure: ToolFailure) { try { ProtocolIo.write(writer, jsonObject("kind" to "error", "code" to failure.code, "details" to failure.details)) } catch (_: Exception) { } }
        catch (_: Exception) { try { ProtocolIo.write(writer, jsonObject("kind" to "error", "code" to "scriptFailed")) } catch (_: Exception) { } }
        finally { try { reader.close() } catch (_: Exception) { }; try { writer.close() } catch (_: Exception) { } }
    }

    companion object {
        const val InterfaceName = "io.kaffubox.android.ScriptSandbox"
        const val Start = IBinder.FIRST_CALL_TRANSACTION
        const val Cancel = IBinder.FIRST_CALL_TRANSACTION + 1
    }
}
