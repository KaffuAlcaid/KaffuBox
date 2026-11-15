// 使用 Android Camera2 预览帧本地识码，按页面生命周期释放相机，不自动访问识别结果
package io.kaffubox.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.kaffubox.core.CodeReader
import io.kaffubox.core.ToolFailure
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

@Composable
fun TransferScanCapture(controller: AppController, onCode: (String) -> Unit) {
    val context = LocalContext.current
    var opened by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) opened = true else controller.message = "相机权限未开启，仍可选择二维码图片识别"
    }
    Button(onClick = {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) opened = true
        else permission.launch(Manifest.permission.CAMERA)
    }) { Text("相机扫码") }
    if (opened) Dialog(onDismissRequest = { opened = false }) {
        val currentOnCode by rememberUpdatedState(onCode)
        var error by remember { mutableStateOf<String?>(null) }
        var hasFlash by remember { mutableStateOf(false) }
        var torch by remember { mutableStateOf(false) }
        val scanner = remember { AndroidQrCamera(context, { value -> opened = false; currentOnCode(value) }, { error = it }, { hasFlash = it }) }
        val owner = LocalLifecycleOwner.current
        DisposableEffect(scanner, owner) {
            val observer = LifecycleEventObserver { _, event -> when (event) { Lifecycle.Event.ON_RESUME -> scanner.resume(); Lifecycle.Event.ON_PAUSE -> scanner.pause(); else -> Unit } }
            owner.lifecycle.addObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) scanner.resume()
            onDispose { owner.lifecycle.removeObserver(observer); scanner.close() }
        }
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("扫描二维码", style = MaterialTheme.typography.titleLarge)
                Text("将二维码完整放入画面，识别后先显示地址，不自动访问", style = MaterialTheme.typography.bodySmall)
                AndroidView(factory = { TextureView(it).also(scanner::attach) }, modifier = Modifier.fillMaxWidth().height(340.dp))
                InlineError(error)
                Row {
                    if (hasFlash) TextButton(onClick = { torch = !torch; scanner.setTorch(torch) }) { Text(if (torch) "关闭补光" else "打开补光") }
                    TextButton(onClick = { opened = false }) { Text("关闭相机") }
                }
            }
        }
    }
}

private class AndroidQrCamera(context: Context, private val onCode: (String) -> Unit, private val onError: (String) -> Unit, private val onReady: (Boolean) -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("KaffuQrCamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val found = AtomicBoolean()
    private var texture: TextureView? = null
    @Volatile private var resumed = false
    @Volatile private var disposed = false
    private var generation = 0
    private var opening = false
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var surface: Surface? = null
    private var request: CaptureRequest.Builder? = null
    private var torch = false
    private var lastFrame = 0L

    fun attach(view: TextureView) {
        texture = view
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) = start()
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) { pause(); resume() }
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean { pause(); return true }
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
        }
        start()
    }
    fun resume() { resumed = true; start() }
    @Synchronized fun pause() {
        resumed = false; generation++; opening = false
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        runCatching { reader?.close() }; reader = null
        runCatching { surface?.release() }; surface = null; request = null
    }
    fun setTorch(value: Boolean) { torch = value; handler.post { repeatRequest() } }
    private fun repeatRequest() {
        runCatching {
            request?.let { it.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF); session?.setRepeatingRequest(it.build(), null, handler) }
        }.onFailure { report("无法调整相机补光") }
    }
    private fun report(text: String) { main.post { if (!disposed) onError(text) } }
    @android.annotation.SuppressLint("MissingPermission")
    @Synchronized private fun start() {
        val view = texture ?: return
        val target = view.surfaceTexture ?: return
        if (!resumed || disposed || !view.isAvailable || opening || camera != null) return
        opening = true
        val revision = ++generation
        try {
            val id = manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: manager.cameraIdList.firstOrNull() ?: error("cameraUnavailable")
            val properties = manager.getCameraCharacteristics(id)
            val config = properties.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("cameraUnavailable")
            val previews = config.getOutputSizes(SurfaceTexture::class.java).toSet()
            val sizes = config.getOutputSizes(ImageFormat.YUV_420_888).filter { it in previews }
            val size = sizes.filter { it.width <= 1280 && it.height <= 1280 }.minByOrNull { abs(it.width * it.height - 640 * 480) } ?: sizes.minByOrNull { it.width * it.height } ?: error("cameraUnavailable")
            target.setDefaultBufferSize(size.width, size.height)
            val displayDegrees = when (view.display?.rotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
            val rotation = ((properties.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90) - displayDegrees + 360) % 360
            val bufferWidth = if (rotation % 180 == 0) size.width else size.height
            val bufferHeight = if (rotation % 180 == 0) size.height else size.width
            val scale = max(view.width.toFloat() / bufferWidth, view.height.toFloat() / bufferHeight)
            view.setTransform(Matrix().apply { setScale(bufferWidth * scale / view.width, bufferHeight * scale / view.height, view.width / 2f, view.height / 2f) })
            surface = Surface(target)
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { source -> source.setOnImageAvailableListener({ images ->
                val image = runCatching { images.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                try {
                    if (!resumed || found.get() || SystemClock.elapsedRealtime() - lastFrame < 250) return@setOnImageAvailableListener
                    lastFrame = SystemClock.elapsedRealtime()
                    val plane = image.planes[0]; val buffer = plane.buffer; val pixels = ByteArray(image.width * image.height)
                    for (y in 0 until image.height) for (x in 0 until image.width) pixels[y * image.width + x] = buffer.get(y * plane.rowStride + x * plane.pixelStride)
                    val code = CodeReader.readLuminance(pixels, image.width, image.height).getValue("text").jsonPrimitive.content
                    if (found.compareAndSet(false, true)) main.post { if (!disposed && resumed) onCode(code) else found.set(false) }
                } catch (_: ToolFailure) { }
                catch (_: IllegalStateException) { }
                finally { image.close() }
            }, handler) }
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) = synchronized(this@AndroidQrCamera) {
                    if (disposed || !resumed || revision != generation) { device.close(); return }
                    camera = device; opening = false
                    val preview = surface ?: return
                    val frames = reader?.surface ?: return
                    @Suppress("DEPRECATION")
                    device.createCaptureSession(listOf(preview, frames), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(capture: CameraCaptureSession): Unit = synchronized(this@AndroidQrCamera) {
                            if (disposed || !resumed || revision != generation) { capture.close(); return }
                            session = capture
                            request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(preview); addTarget(frames)
                                if (properties.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true) set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            }
                            repeatRequest(); main.post { if (!disposed) onReady(properties.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) }
                            Unit
                        }
                        override fun onConfigureFailed(capture: CameraCaptureSession) { capture.close(); report("相机预览无法启动，请关闭相机后重试或选择二维码图片") }
                    }, handler)
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); report("相机连接已断开，请关闭后重试") }
                override fun onError(device: CameraDevice, error: Int) { device.close(); report("相机暂不可用，可能被其他应用占用，请关闭后重试") }
            }, handler)
        } catch (_: Exception) { opening = false; report("无法启动相机，请检查相机权限或选择二维码图片") }
    }
    override fun close() { disposed = true; pause(); texture?.surfaceTextureListener = null; thread.quitSafely() }
}
