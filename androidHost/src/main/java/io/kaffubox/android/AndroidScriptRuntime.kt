// 绑定 Android 隔离脚本服务并代理权限检查后的工具调用，在取消时关闭管道与服务会话
package io.kaffubox.android

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import io.kaffubox.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class AndroidScriptRuntime(private val applicationContext: Context) : ScriptRuntimePort {
    override suspend fun execute(request: ScriptRequest, context: ToolContext): JsonElement = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val requestPipe = ParcelFileDescriptor.createPipe()
        val responsePipe = ParcelFileDescriptor.createPipe()
        val connected = CompletableDeferred<IBinder>()
        val bound = AtomicBoolean(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { connected.complete(binder) }
            override fun onServiceDisconnected(name: ComponentName) {
                connected.completeExceptionally(ToolFailure("scriptServiceStopped"))
                try { responsePipe[0].close() } catch (_: Exception) { }
            }
            override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
            override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(ToolFailure("scriptServiceUnavailable")) }
        }
        var binder: IBinder? = null
        try {
            val intent = Intent(applicationContext, ScriptSandboxService::class.java)
            val success = withContext(Dispatchers.Main.immediate) {
                if (Build.VERSION.SDK_INT >= 29) applicationContext.bindIsolatedService(intent, Context.BIND_AUTO_CREATE, "Script" + id.replace("-", ""), applicationContext.mainExecutor, connection)
                else applicationContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }
            if (!success) throw ToolFailure("scriptServiceUnavailable")
            bound.set(true)
            binder = withTimeout(10_000) { connected.await() }
            transact(binder, ScriptSandboxService.Start) { data ->
                data.writeString(id); requestPipe[0].writeToParcel(data, 0); responsePipe[1].writeToParcel(data, 0)
            }
            requestPipe[0].close(); responsePipe[1].close()
            coroutineScope {
                val hostCallActive = AtomicBoolean()
                val guard = launch(Dispatchers.Default) {
                    try {
                        var activeMillis = 0L
                        while (activeMillis <= request.limits.timeoutMillis + 3000) {
                            delay(50)
                            if (!hostCallActive.get()) activeMillis += 50
                        }
                    } finally {
                        try { requestPipe[1].close() } catch (_: Exception) { }
                        try { responsePipe[0].close() } catch (_: Exception) { }
                        try { transact(binder, ScriptSandboxService.Cancel) { it.writeString(id) } } catch (_: Exception) { }
                    }
                }
                try {
                    val reader = ParcelFileDescriptor.AutoCloseInputStream(responsePipe[0]).bufferedReader(Charsets.UTF_8)
                    val writer = ParcelFileDescriptor.AutoCloseOutputStream(requestPipe[1]).bufferedWriter(Charsets.UTF_8)
                    ProtocolIo.write(writer, jsonObject("request" to CoreJson.encodeToJsonElement(request)))
                    var calls = 0
                    while (true) {
                        context.checkpoint()
                        val message = ProtocolIo.read(reader) ?: throw ToolFailure("scriptServiceStopped")
                        when (message.string("kind")) {
                            "result" -> return@coroutineScope message["value"] ?: JsonNull
                            "error" -> throw ToolFailure(message.string("code"), message.objectValue("details"))
                            "hostCall" -> {
                                if (++calls > request.limits.maxHostCalls) throw ToolFailure("scriptHostCallLimit")
                                hostCallActive.set(true)
                                val response = try { jsonObject("kind" to "result", "value" to context.invoke(message.string("toolId"), message.objectValue("arguments"))) }
                                catch (failure: ToolFailure) { jsonObject("kind" to "error", "code" to failure.code, "details" to failure.details) }
                                finally { hostCallActive.set(false) }
                                ProtocolIo.write(writer, response)
                            }
                            else -> throw ToolFailure("scriptProtocolInvalid")
                        }
                    }
                    @Suppress("UNREACHABLE_CODE") JsonNull
                } finally { guard.cancel() }
            }
        } finally {
            binder?.let { try { transact(it, ScriptSandboxService.Cancel) { parcel -> parcel.writeString(id) } } catch (_: Exception) { } }
            (requestPipe + responsePipe).forEach { try { it.close() } catch (_: Exception) { } }
            if (bound.get()) withContext(NonCancellable + Dispatchers.Main.immediate) { applicationContext.unbindService(connection) }
        }
    }

    private fun transact(binder: IBinder?, code: Int, write: (Parcel) -> Unit) {
        if (binder == null) return
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ScriptSandboxService.InterfaceName); write(data)
            if (!binder.transact(code, data, reply, 0)) throw ToolFailure("scriptServiceProtocol")
            reply.readException()
        } finally { data.recycle(); reply.recycle() }
    }
}
