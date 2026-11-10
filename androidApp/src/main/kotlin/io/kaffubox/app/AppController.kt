// 管理移动端工具调用、文件授权与导出、后台任务和插件安装状态
package io.kaffubox.app

import android.content.*
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import io.kaffubox.android.*
import io.kaffubox.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipFile

class AppController(val context: Context) {
    val dataRoot: Path = java.io.File(context.filesDir, "KaffuBox").toPath()
    private val preferenceStore = PreferencesStore(dataRoot)
    var preferences by mutableStateOf(preferenceStore.load())
        private set
    val files = AndroidFileBroker(context, dataRoot.resolve("Outputs").toFile())
    val media = AndroidMediaTools(context, files)
    private val platform = PlatformInfo("android", Build.VERSION.RELEASE, Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val host = KaffuHost(files, platform, dataRoot, AndroidScriptRuntime(context), preferences.allowUnsignedPlugins,
        AndroidDeviceTools.definitions(context) + ImageTools(AndroidImageCodec()).definitions() + media.definitions())
    var tools by mutableStateOf(mobileTools())
        private set
    var tasks by mutableStateOf<List<TaskSnapshot>>(emptyList())
        private set
    var plugins by mutableStateOf(host.plugins.list())
        private set
    var page by mutableStateOf(AppPage.Home)
    var selectedToolId by mutableStateOf<String?>(null)
    var selectedCategory by mutableStateOf<Workbench?>(null)
    var search by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    var errorDetails by mutableStateOf<ErrorDetails?>(null)
    var installPreview by mutableStateOf<PluginInstallPreview?>(null)
    var pluginBusy by mutableStateOf(false)
    var fileToolPicker by mutableStateOf<List<FileRef>>(emptyList())
    val forms = mutableMapOf<String, ToolFormState>()
    val taskArguments = mutableMapOf<String, JsonObject>()
    val calculator = CalculatorState(preferences)
    val matrix = MatrixState()
    val units = UnitState()
    val encoding = EncodingState()
    val timer = CountdownState()
    val supportsFileDrop = false
    val isTouchDevice = true
    val fileLocationActionLabel = "保存到…"
    val outputLocationActionLabel = "导出说明"
    val outputDescription = "结果先保存在应用内，完成后可打开、分享或保存到所选位置"
    val mediaToolsAvailable = true
    var notify: ((String) -> Unit)? = { message = it }
    var launchPicker: ((Array<String>, Boolean) -> Unit)? = null
    var launchSave: ((String) -> Unit)? = null
    var requestNotifications: (() -> Unit)? = null
    private var onPicked: ((List<Uri>) -> Unit)? = null
    private var pendingSave: FileRef? = null

    init { scope.launch {
        var previous = emptyMap<String, TaskState>()
        host.tasks.snapshots.collectLatest { snapshots ->
            tasks = snapshots
            if (preferences.notifyCompleted) snapshots.filter { it.state == TaskState.Succeeded && previous[it.id] != TaskState.Succeeded }.forEach { completed ->
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                manager.createNotificationChannel(android.app.NotificationChannel("Completed", "任务完成", android.app.NotificationManager.IMPORTANCE_DEFAULT))
                val launch = android.app.PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
                if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) manager.notify(completed.id.hashCode(), android.app.Notification.Builder(context, "Completed").setSmallIcon(R.drawable.app_icon).setContentTitle((findTool(completed.toolId)?.title ?: "处理任务") + "已完成").setContentText("点击查看结果").setContentIntent(launch).setAutoCancel(true).build())
            }
            previous = snapshots.associate { it.id to it.state }
        }
    } }
    val selectedTool: UiTool? get() = tools.firstOrNull { it.id == selectedToolId }
    fun findTool(id: String) = tools.firstOrNull { it.id == id }
    fun form(tool: UiTool) = forms.getOrPut(tool.id) { ToolFormState(tool).apply { if (preferences.rememberParameters) values.putAll(preferences.toolOptions[tool.id].orEmpty()) } }
    fun navigate(destination: AppPage) { page = destination; search = ""; if (destination != AppPage.Tool) selectedToolId = null }
    fun openTool(tool: UiTool, input: List<FileRef> = emptyList()) {
        page = AppPage.Tool; selectedToolId = tool.id; search = ""
        val field = tool.fields.firstOrNull { it.kind in setOf(FieldKind.File, FieldKind.Files) }
        if (input.isNotEmpty() && field != null) { val state = form(tool); state.files[field.key] = if (field.kind == FieldKind.Files || tool.batch) input else input.take(1); state.resetResult() }
    }
    fun favorite(id: String) { updatePreferences(preferences.copy(favorites = if (id in preferences.favorites) preferences.favorites - id else preferences.favorites + id)) }
    fun updatePreferences(value: AppPreferences) { preferences = value; try { preferenceStore.save(value) } catch (failure: Exception) { report(failure, "保存设置失败") } }
    fun rememberCalculator() { updatePreferences(preferences.copy(calculatorAngle = calculator.angleMode, calculatorDigits = calculator.displayDigits, calculatorFormat = calculator.displayFormat)) }
    suspend fun execute(toolId: String, arguments: JsonObject): JsonElement = withContext(Dispatchers.Default) { ToolContext(files, Capability.entries.toSet(), platform, host.registry).invoke(toolId, arguments) }
    suspend fun readImage(ref: FileRef): RasterImage = withContext(Dispatchers.Default) { ImageTools(AndroidImageCodec()).readImage(files.resolve(ref.id), ToolContext(files, Capability.entries.toSet(), platform, host.registry)) }
    fun arguments(tool: UiTool, state: ToolFormState, batchKey: String? = null, batchFile: FileRef? = null) = buildToolArguments(tool, state, batchKey, batchFile)
    fun runForm(tool: UiTool, queued: Boolean = tool.queued) {
        val state = form(tool)
        try {
            val first = tool.fields.firstOrNull { it.kind == FieldKind.File }
            val batch = first?.let { state.files[it.key] }.orEmpty()
            val inputs: List<FileRef?> = if (tool.batch && batch.size > 1) batch else listOf(null)
            val requests = inputs.map { arguments(tool, state, first?.key, it) }
            if (preferences.rememberParameters) {
                val keys = tool.fields.filter { it.kind in setOf(FieldKind.Choice, FieldKind.Toggle, FieldKind.Integer, FieldKind.Number) }.map { it.key }.toSet()
                updatePreferences(preferences.copy(toolOptions = preferences.toolOptions + (tool.id to state.values.filterKeys { it in keys })))
            }
            state.error = null; state.result = null
            if (queued) {
                requests.forEach { request -> val id = host.tasks.submit(tool.id, request, Capability.entries.toSet()); taskArguments[id] = request; state.selectedTask = id }
                context.startForegroundService(Intent(context, TaskForegroundService::class.java))
                message = if (requests.size == 1) "已加入处理队列" else "已加入 " + requests.size + " 个任务"
            } else {
                state.job?.cancel(); state.busy = true
                state.job = scope.launch {
                    try { state.result = execute(tool.id, requests.first()) }
                    catch (_: CancellationException) { } catch (failure: Exception) { state.error = UiText.failure(failure); report(failure, tool.title) }
                    finally { state.busy = false }
                }
            }
        } catch (failure: Exception) { state.error = UiText.failure(failure); report(failure, tool.title) }
    }
    fun selectFiles(tool: UiTool, field: ToolField) {
        pick(if (tool.workbench == Workbench.Image) arrayOf("image/*") else arrayOf("*/*"), field.kind == FieldKind.Files || tool.batch) { importUris(it, tool, field) }
    }
    private fun pick(types: Array<String>, multiple: Boolean, complete: (List<Uri>) -> Unit) { onPicked = complete; launchPicker?.invoke(types, multiple) }
    fun finishPick(uris: List<Uri>) { val callback = onPicked; onPicked = null; if (uris.isNotEmpty()) callback?.invoke(uris) }
    fun importUris(uris: List<Uri>, tool: UiTool? = selectedTool.takeIf { page == AppPage.Tool }, field: ToolField? = tool?.fields?.firstOrNull { it.kind in setOf(FieldKind.File, FieldKind.Files) }) {
        if (uris.isEmpty()) return
        scope.launch {
            try {
                val refs = withContext(Dispatchers.IO) { uris.distinct().map { uri ->
                    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    files.register(uri)
                } }
                placeFiles(refs, tool, field)
            } catch (failure: Exception) { report(failure, "无法读取所选文件") }
        }
    }
    fun acceptFiles(paths: List<Path>, tool: UiTool? = selectedTool, field: ToolField? = tool?.fields?.firstOrNull { it.kind in setOf(FieldKind.File, FieldKind.Files) }) {
        scope.launch { try { val refs = withContext(Dispatchers.IO) { paths.map { files.registerFile(it.toFile()) } }; placeFiles(refs, tool, field) } catch (failure: Exception) { report(failure, "添加文件失败") } }
    }
    private fun placeFiles(refs: List<FileRef>, tool: UiTool?, field: ToolField?) {
        if (tool == null || field == null) { fileToolPicker = refs; return }
        val state = form(tool); val multiple = tool.batch || field.kind == FieldKind.Files
        state.files[field.key] = if (multiple) (state.files[field.key].orEmpty() + refs).distinctBy { files.sourceUri(it.id) } else refs.take(1)
        state.resetResult()
        if (!multiple && refs.size > 1) message = "已导入第一个文件，这个工具一次处理一个文件"
    }
    fun copy(text: String) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("KaffuBox", text)); message = "已复制" }
    fun saveText(text: String, suggestedName: String = "Result.txt") {
        scope.launch { try { val ref = withContext(Dispatchers.IO) { files.writeBytes(suggestedName, text.toByteArray(Charsets.UTF_8), if (suggestedName.endsWith(".svg")) "image/svg+xml" else "text/plain") }; revealFile(ref) } catch (failure: Exception) { report(failure, "保存结果失败") } }
    }
    fun revealFile(ref: FileRef) { pendingSave = ref; launchSave?.invoke(ref.name) }
    fun finishSave(uri: Uri?) {
        val ref = pendingSave; pendingSave = null
        if (uri == null || ref == null) return
        scope.launch { try { withContext(Dispatchers.IO) { files.export(ref.id, uri) }; message = "文件已保存" } catch (failure: Exception) { report(failure, "保存失败") } }
    }
    private fun sharingUri(ref: FileRef): Uri {
        val handle = files.resolve(ref.id)
        val path = handle.localPath
        return if (ref.writable && path != null) FileProvider.getUriForFile(context, context.packageName + ".files", java.io.File(path)) else files.sourceUri(ref.id)
    }
    fun openFile(ref: FileRef) {
        try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(sharingUri(ref), ref.mimeType ?: java.net.URLConnection.guessContentTypeFromName(ref.name) ?: "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: android.content.ActivityNotFoundException) { message = "没有能打开此格式的应用，可先保存文件" }
        catch (failure: Exception) { report(failure, "打开文件失败") }
    }
    fun shareFile(ref: FileRef) {
        try { val send = Intent(Intent.ACTION_SEND).setType(ref.mimeType ?: "application/octet-stream").putExtra(Intent.EXTRA_STREAM, sharingUri(ref)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); context.startActivity(Intent.createChooser(send, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (failure: Exception) { report(failure, "分享失败") }
    }
    fun openOutputDirectory() { message = "在任务结果中点击“保存到…”选择文件位置，也可直接打开文件" }
    fun retry(task: TaskSnapshot) {
        val arguments = taskArguments[task.id] ?: run { message = "请重新打开工具并选择文件"; return }
        try { val id = host.tasks.submit(task.toolId, arguments, Capability.entries.toSet()); taskArguments[id] = arguments; context.startForegroundService(Intent(context, TaskForegroundService::class.java)) }
        catch (failure: Exception) { report(failure, "重新开始失败") }
    }
    fun removeFinishedTasks() { tasks.filter { it.state in setOf(TaskState.Succeeded, TaskState.Failed, TaskState.Cancelled) }.forEach { task -> try { host.tasks.forget(task.id); taskArguments.remove(task.id) } catch (_: ToolFailure) { } } }
    fun playTimerSound() { runCatching { android.media.ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 65).also { tone -> tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 400); scope.launch { delay(500); tone.release() } } } }

    fun choosePlugin(updateOf: String? = null) {
        pick(arrayOf("*/*"), false) { uris -> scope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    val path = copyToCache(uris.first(), "Plugin", ".kbx", 64L * 1024 * 1024)
                    try { ZipFile(path.toFile()).use { zip ->
                        val entry = zip.getEntry("manifest.json") ?: throw ToolFailure("pluginManifestMissing")
                        val manifestBytes = zip.getInputStream(entry).use { it.readBounded(262145) }; if (manifestBytes.size > 262144) throw ToolFailure("pluginManifestInvalid")
                        val manifest = CoreJson.decodeFromString(PluginManifest.serializer(), manifestBytes.toString(Charsets.UTF_8))
                        if (updateOf != null && updateOf != manifest.id) throw ToolFailure("pluginIdentityMismatch")
                        val signer = zip.getEntry("signature.json")?.let { signature -> val bytes = zip.getInputStream(signature).use { it.readBounded(524289) }; if (bytes.size > 524288) throw ToolFailure("pluginManifestInvalid"); CoreJson.decodeFromString(PluginSignature.serializer(), bytes.toString(Charsets.UTF_8)).keyId }
                        PluginInstallPreview(path, manifest, signer, updateOf)
                    } } catch (failure: Exception) { Files.deleteIfExists(path); throw failure }
                }
                installPreview = preview
            } catch (failure: Exception) { report(failure, "无法读取插件") }
        } }
    }
    fun installPlugin(preview: PluginInstallPreview, enable: Boolean) {
        pluginBusy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { val installed = host.plugins.install { Files.newInputStream(preview.path) }; if (enable && !installed.enabled) host.plugins.enable(installed.manifest.id, installed.manifest.permissions) }; Files.deleteIfExists(preview.path); installPreview = null; message = "插件已安装" }
            catch (failure: Exception) { report(failure, "安装插件失败") }
            finally { refreshPlugins(); pluginBusy = false }
        }
    }
    fun enablePlugin(plugin: InstalledPlugin) = pluginOperation("启用插件失败") { host.plugins.enable(plugin.manifest.id, plugin.manifest.permissions) }
    fun disablePlugin(plugin: InstalledPlugin, cancelRunning: Boolean) = pluginOperation("停用插件失败") { host.plugins.disable(plugin.manifest.id); if (cancelRunning) host.tasks.cancelProvider(plugin.manifest.id) }
    fun uninstallPlugin(plugin: InstalledPlugin, cancelRunning: Boolean) = pluginOperation("卸载插件失败") { host.plugins.uninstall(plugin.manifest.id); if (cancelRunning) host.tasks.cancelProvider(plugin.manifest.id) }
    fun rollbackPlugin(plugin: InstalledPlugin, version: String) = pluginOperation("回退插件失败") { host.plugins.rollback(plugin.manifest.id, version) }
    private fun pluginOperation(title: String, operation: () -> Any) {
        pluginBusy = true
        scope.launch { try { withContext(Dispatchers.IO) { operation() } } catch (failure: Exception) { report(failure, title) } finally { refreshPlugins(); pluginBusy = false } }
    }
    fun importPublicKey(keyId: String) {
        pick(arrayOf("*/*"), false) { uris -> scope.launch {
            try { withContext(Dispatchers.IO) {
                val bytes = context.contentResolver.openInputStream(uris.first())!!.use { it.readBounded(32769) }; if (bytes.size > 32768) throw ToolFailure("publicKeyInvalid")
                val text = bytes.toString(Charsets.US_ASCII)
                val publicKey = if (text.contains("-----BEGIN PUBLIC KEY-----")) text.substringAfter("-----BEGIN PUBLIC KEY-----").substringBefore("-----END PUBLIC KEY-----").filterNot(Char::isWhitespace) else Base64.getEncoder().encodeToString(bytes)
                host.plugins.trust(keyId, publicKey)
            }; message = "开发者公钥已导入"; refreshPlugins() } catch (failure: Exception) { report(failure, "导入公钥失败") }
        } }
    }
    private fun copyToCache(uri: Uri, prefix: String, extension: String, limit: Long): Path {
        val target = Files.createTempFile(context.cacheDir.toPath(), prefix, extension)
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> Files.newOutputStream(target).use { output -> val buffer = ByteArray(65536); var total = 0L; while (true) { val read = input.read(buffer); if (read < 0) break; total += read; if (total > limit) throw ToolFailure("inputTooLarge"); output.write(buffer, 0, read) } } }
            return target
        } catch (failure: Exception) { Files.deleteIfExists(target); throw failure }
    }
    private fun java.io.InputStream.readBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (output.size() < limit) { val count = read(buffer, 0, minOf(buffer.size, limit - output.size())); if (count < 0) break; output.write(buffer, 0, count) }
        return output.toByteArray()
    }
    fun refreshPlugins() { plugins = host.plugins.list(); tools = mobileTools() }
    fun report(failure: Throwable, title: String) { if (failure is CancellationException) return; errorDetails = ErrorDetails(title, UiText.failure(failure), if (failure is ToolFailure) failure.code + "\n" + failure.details else failure.javaClass.simpleName + "\n" + failure.message.orEmpty()) }
    private fun mobileTools(): List<UiTool> = ToolCatalog.resolve(host.registry.list()).map { tool ->
        when {
            tool.id == "media.extractAudio" -> tool.copy(summary = "将兼容的原音轨直接保存为 M4A，不重新压缩", fields = tool.fields.filter { it.key in setOf("file", "format", "name") }.map { if (it.key == "format") it.copy(initial = "m4a", choices = listOf("m4a" to "M4A 原音轨"), hint = "支持直接保存 AAC、AMR 音轨") else it })
            tool.id.startsWith("image.") -> tool.copy(fields = tool.fields.map { field -> when (field.key) { "format" -> field.copy(choices = listOf("png" to "PNG", "jpeg" to "JPEG", "webp" to "WebP")); "quality" -> field.copy(label = "图片画质", visibleWhen = mapOf("format" to setOf("jpeg", "webp")), hint = "数值越高画质越好、文件通常越大，仅对 JPEG 和 WebP 生效"); else -> field } })
            else -> tool
        }
    }
}
