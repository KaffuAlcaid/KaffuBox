// 管理桌面导航、文件选择、真实工具调用、任务状态、插件授权和本地设置
package io.kaffubox.app

import androidx.compose.runtime.*
import io.kaffubox.core.*
import io.kaffubox.desktop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.*
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipFile
import javax.swing.JFileChooser

class AppController(val dataRoot: Path, arguments: Map<String, String>) : AutoCloseable {
    private val preferenceStore = PreferencesStore(dataRoot)
    var preferences by mutableStateOf(preferenceStore.load())
        private set
    val files = DesktopFileBroker(arguments["--outputDir"]?.let(Path::of) ?: preferences.outputDirectory.takeIf(String::isNotBlank)?.let(Path::of) ?: defaultOutput())
    val ffmpeg = findEngine(arguments["--ffmpeg"], "ffmpeg.exe")
    val ffprobe = findEngine(arguments["--ffprobe"], "ffprobe.exe")
    private val platform = PlatformInfo("windows", System.getProperty("os.version"), System.getProperty("os.arch"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val host = KaffuHost(files, platform, dataRoot, DesktopScriptRuntime(dataRoot.resolve("Workers")), preferences.allowUnsignedPlugins,
        DesktopDeviceTools.definitions() + DesktopMediaTools(files, ffmpeg, ffprobe).definitions() + ImageTools(DesktopImageCodec()).definitions())
    var tools by mutableStateOf(ToolCatalog.resolve(host.registry.list()))
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
    var taskResult by mutableStateOf<TaskSnapshot?>(null)
    var fileToolPicker by mutableStateOf<List<FileRef>>(emptyList())
    var requestExit by mutableStateOf(false)
    var visible by mutableStateOf(true)
    var window: Window? = null
    var notify: ((String) -> Unit)? = null
    val forms = mutableMapOf<String, ToolFormState>()
    val taskArguments = mutableMapOf<String, JsonObject>()
    val calculator = CalculatorState(preferences)
    val matrix = MatrixState()
    val units = UnitState()
    val encoding = EncodingState()
    val timer = CountdownState()
    var onExit: (() -> Unit)? = null

    init {
        scope.launch {
            var previous = emptyMap<String, TaskState>()
            host.tasks.snapshots.collectLatest { snapshots ->
                tasks = snapshots
                if (preferences.notifyCompleted) snapshots.filter { it.state == TaskState.Succeeded && previous[it.id] == TaskState.Running }.forEach { notify?.invoke((findTool(it.toolId)?.title ?: it.toolId) + "已完成") }
                previous = snapshots.associate { it.id to it.state }
            }
        }
    }

    val selectedTool: UiTool? get() = tools.firstOrNull { it.id == selectedToolId }
    fun findTool(id: String): UiTool? = tools.firstOrNull { it.id == id }
    fun form(tool: UiTool): ToolFormState = forms.getOrPut(tool.id) { ToolFormState(tool).apply { if (preferences.rememberParameters) values.putAll(preferences.toolOptions[tool.id].orEmpty()) } }
    fun navigate(destination: AppPage) { page = destination; search = ""; if (destination != AppPage.Tool) selectedToolId = null }
    fun openTool(tool: UiTool, input: List<FileRef> = emptyList()) {
        selectedToolId = tool.id; page = AppPage.Tool; search = ""
        val field = tool.fields.firstOrNull { it.kind in setOf(FieldKind.File, FieldKind.Files) }
        if (input.isNotEmpty() && field != null) form(tool).files[field.key] = if (field.kind == FieldKind.Files || tool.batch) input else input.take(1)
    }
    fun favorite(id: String) {
        updatePreferences(preferences.copy(favorites = if (id in preferences.favorites) preferences.favorites - id else preferences.favorites + id))
    }
    fun updatePreferences(value: AppPreferences) {
        preferences = value
        try { preferenceStore.save(value) } catch (failure: Exception) { report(failure, "保存设置失败") }
    }
    fun rememberCalculator() {
        updatePreferences(preferences.copy(calculatorAngle = calculator.angleMode, calculatorDigits = calculator.displayDigits, calculatorFormat = calculator.displayFormat))
    }
    val supportsFileDrop = true
    val isTouchDevice = false
    val fileLocationActionLabel = "所在文件夹"
    val outputLocationActionLabel = "输出文件夹"
    val outputDescription get() = "输出位置  " + files.outputRoot
    val mediaToolsAvailable get() = ffmpeg != null && ffprobe != null
    fun playTimerSound() { java.awt.Toolkit.getDefaultToolkit().beep() }
    suspend fun execute(toolId: String, arguments: JsonObject): JsonElement = withContext(Dispatchers.Default) {
        ToolContext(files, Capability.entries.toSet(), platform, host.registry).invoke(toolId, arguments)
    }
    suspend fun readImage(ref: FileRef): RasterImage = withContext(Dispatchers.Default) {
        ImageTools(DesktopImageCodec()).readImage(files.resolve(ref.id), ToolContext(files, Capability.entries.toSet(), platform, host.registry))
    }

    fun runForm(tool: UiTool, queued: Boolean = tool.queued) {
        val state = form(tool)
        try {
            val firstFileField = tool.fields.firstOrNull { it.kind == FieldKind.File }
            val batchFiles = firstFileField?.let { state.files[it.key] }.orEmpty()
            val inputs: List<FileRef?> = if (tool.batch && batchFiles.size > 1) batchFiles else listOf(null)
            val requests = inputs.map { input -> arguments(tool, state, firstFileField?.key, input) }
            if (preferences.rememberParameters) {
                val optionKeys = tool.fields.filter { it.kind in setOf(FieldKind.Choice, FieldKind.Toggle, FieldKind.Integer, FieldKind.Number) }.map { it.key }.toSet()
                updatePreferences(preferences.copy(toolOptions = preferences.toolOptions + (tool.id to state.values.filterKeys { it in optionKeys })))
            }
            state.error = null
            state.result = null
            if (queued) {
                requests.forEach { request ->
                    val id = host.tasks.submit(tool.id, request, Capability.entries.toSet())
                    taskArguments[id] = request; state.selectedTask = id
                }
                message = if (requests.size == 1) "已加入处理队列" else "已加入 " + requests.size + " 个任务"
            } else {
                state.job?.cancel()
                state.busy = true
                state.job = scope.launch {
                    try { state.result = execute(tool.id, requests.first()) }
                    catch (_: CancellationException) { state.error = null }
                    catch (failure: Exception) { state.error = UiText.failure(failure); report(failure, tool.title) }
                    finally { state.busy = false }
                }
            }
        } catch (failure: Exception) { state.error = UiText.failure(failure); report(failure, tool.title) }
    }

    fun arguments(tool: UiTool, state: ToolFormState, batchKey: String? = null, batchFile: FileRef? = null): JsonObject = buildToolArguments(tool, state, batchKey, batchFile)

    fun chooseFiles(multiple: Boolean = true, title: String = "选择文件", extension: String? = null): List<Path> {
        val dialog = FileDialog(window as? Frame, title, FileDialog.LOAD)
        return try {
            dialog.isMultipleMode = multiple
            extension?.let { dialog.file = "*.$it" }
            dialog.isVisible = true
            dialog.files.map { it.toPath() }
        } finally { dialog.dispose() }
    }
    fun selectFiles(tool: UiTool, field: ToolField) {
        val paths = chooseFiles(field.kind == FieldKind.Files || tool.batch)
        acceptFiles(paths, tool, field)
    }
    fun acceptFiles(paths: List<Path>, targetTool: UiTool? = selectedTool.takeIf { page == AppPage.Tool }, targetField: ToolField? = targetTool?.fields?.firstOrNull { it.kind in setOf(FieldKind.File, FieldKind.Files) }) {
        if (paths.isEmpty()) return
        importFiles(paths) { refs ->
            if (targetTool != null && targetField != null) {
                val state = form(targetTool)
                val multiple = targetField.kind == FieldKind.Files || targetTool.batch
                state.files[targetField.key] = if (multiple) (state.files[targetField.key].orEmpty() + refs).distinctBy { files.resolve(it.id).localPath?.lowercase(java.util.Locale.ROOT) } else refs.take(1)
                state.resetResult()
                if (!multiple && refs.size > 1) message = "已导入第一个文件，这个工具一次处理一个文件"
            } else fileToolPicker = refs
        }
    }
    private fun importFiles(paths: List<Path>, complete: (List<FileRef>) -> Unit) {
        scope.launch {
            try { val refs = withContext(Dispatchers.IO) { paths.map { if (Files.isDirectory(it)) throw ToolFailure("directoryNotSupported"); files.register(it) } }; complete(refs) }
            catch (failure: Exception) { report(failure, "添加文件失败") }
        }
    }
    fun chooseOutputDirectory() {
        val chooser = JFileChooser(files.outputRoot.toFile()).apply { dialogTitle = "选择输出文件夹"; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
        if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return
        val path = chooser.selectedFile.toPath()
        scope.launch {
            try {
                withContext(Dispatchers.IO) { files.setOutputDirectory(path) }
                updatePreferences(preferences.copy(outputDirectory = files.outputRoot.toString()))
                message = "输出位置已更改"
            } catch (failure: Exception) { report(failure, "无法使用此位置") }
        }
    }
    fun openFile(ref: FileRef) {
        try { Desktop.getDesktop().open(Path.of(files.resolve(ref.id).localPath ?: throw ToolFailure("localFileRequired")).toFile()) }
        catch (failure: Exception) { report(failure, "打开文件失败") }
    }
    fun openOutputDirectory() {
        try { Desktop.getDesktop().open(files.outputRoot.toFile()) }
        catch (failure: Exception) { report(failure, "打开文件夹失败") }
    }
    fun revealFile(ref: FileRef) {
        try {
            val path = Path.of(files.resolve(ref.id).localPath ?: throw ToolFailure("localFileRequired"))
            Desktop.getDesktop().open(path.parent.toFile())
        } catch (failure: Exception) { report(failure, "打开文件夹失败") }
    }
    fun copy(text: String) {
        try { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null); message = "已复制" }
        catch (failure: Exception) { report(failure, "复制失败") }
    }
    fun saveText(text: String, suggestedName: String = "Result.txt") {
        val dialog = FileDialog(window as? Frame, "保存结果", FileDialog.SAVE)
        try {
            dialog.directory = files.outputRoot.toString(); dialog.file = suggestedName; dialog.isVisible = true
            val name = dialog.file ?: return
            val path = Path.of(dialog.directory, name)
            if (Files.exists(path) && javax.swing.JOptionPane.showConfirmDialog(window, "文件已存在，是否替换\n" + path.fileName, "保存结果", javax.swing.JOptionPane.YES_NO_OPTION) != javax.swing.JOptionPane.YES_OPTION) return
            scope.launch {
                try { withContext(Dispatchers.IO) { Files.write(path, text.toByteArray(Charsets.UTF_8)) }; message = "结果已保存" }
                catch (failure: Exception) { report(failure, "保存失败") }
            }
        } finally { dialog.dispose() }
    }
    fun retry(task: TaskSnapshot) {
        val args = taskArguments[task.id] ?: run { message = "任务参数已不可用，请重新打开工具"; return }
        try { val id = host.tasks.submit(task.toolId, args, Capability.entries.toSet()); taskArguments[id] = args }
        catch (failure: Exception) { report(failure, "重新开始失败") }
    }
    fun removeFinishedTasks() {
        tasks.filter { it.state in setOf(TaskState.Succeeded, TaskState.Failed, TaskState.Cancelled) }.forEach { task ->
            try { host.tasks.forget(task.id); taskArguments.remove(task.id) } catch (_: ToolFailure) { }
        }
    }

    fun choosePlugin(updateOf: String? = null) {
        val path = chooseFiles(false, "选择插件安装包", "kbx").firstOrNull() ?: return
        scope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    ZipFile(path.toFile()).use { zip ->
                        val entry = zip.getEntry("manifest.json") ?: throw ToolFailure("pluginManifestMissing")
                        val bytes = zip.getInputStream(entry).use { it.readNBytes(262145) }
                        if (bytes.size > 262144) throw ToolFailure("pluginManifestInvalid")
                        val manifest = CoreJson.decodeFromString(PluginManifest.serializer(), bytes.toString(Charsets.UTF_8))
                        if (updateOf != null && manifest.id != updateOf) throw ToolFailure("pluginIdentityMismatch")
                        val signer = zip.getEntry("signature.json")?.let { signature ->
                            val data = zip.getInputStream(signature).use { it.readNBytes(524289) }
                            if (data.size > 524288) throw ToolFailure("pluginManifestInvalid")
                            CoreJson.decodeFromString(PluginSignature.serializer(), data.toString(Charsets.UTF_8)).keyId
                        }
                        PluginInstallPreview(path, manifest, signer, updateOf)
                    }
                }
                installPreview = preview
            } catch (failure: Exception) { report(failure, "无法读取插件") }
        }
    }
    fun installPlugin(preview: PluginInstallPreview, enable: Boolean) {
        pluginBusy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val record = host.plugins.install { Files.newInputStream(preview.path) }
                    if (enable && !record.enabled) host.plugins.enable(record.manifest.id, record.manifest.permissions)
                }
                installPreview = null; refreshPlugins()
                message = if (preview.updateOf == null) "插件已安装" else "插件已更新，无需重启"
            } catch (failure: Exception) { report(failure, "安装插件失败") }
            finally { pluginBusy = false }
        }
    }
    fun enablePlugin(plugin: InstalledPlugin) = pluginOperation("启用插件失败") { host.plugins.enable(plugin.manifest.id, plugin.manifest.permissions) }
    fun disablePlugin(plugin: InstalledPlugin, cancelRunning: Boolean) = pluginOperation("停用插件失败") {
        host.plugins.disable(plugin.manifest.id)
        if (cancelRunning) host.tasks.cancelProvider(plugin.manifest.id)
    }
    fun uninstallPlugin(plugin: InstalledPlugin, cancelRunning: Boolean) = pluginOperation("卸载插件失败") {
        host.plugins.uninstall(plugin.manifest.id)
        if (cancelRunning) host.tasks.cancelProvider(plugin.manifest.id)
    }
    fun rollbackPlugin(plugin: InstalledPlugin, version: String) = pluginOperation("回退插件失败") { host.plugins.rollback(plugin.manifest.id, version) }
    private fun pluginOperation(title: String, operation: () -> Any) {
        pluginBusy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { operation() }; refreshPlugins() }
            catch (failure: Exception) { report(failure, title) }
            finally { pluginBusy = false }
        }
    }
    fun importPublicKey(keyId: String) {
        val path = chooseFiles(false, "选择开发者公钥").firstOrNull() ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (Files.size(path) > 32768) throw ToolFailure("publicKeyInvalid")
                    val bytes = Files.readAllBytes(path)
                    val text = bytes.toString(Charsets.US_ASCII)
                    val publicKey = if (text.contains("-----BEGIN PUBLIC KEY-----")) text.substringAfter("-----BEGIN PUBLIC KEY-----").substringBefore("-----END PUBLIC KEY-----").filterNot(Char::isWhitespace) else Base64.getEncoder().encodeToString(bytes)
                    host.plugins.trust(keyId, publicKey)
                }
                message = "开发者公钥已导入"; refreshPlugins()
            } catch (failure: Exception) { report(failure, "导入公钥失败") }
        }
    }
    fun refreshPlugins() { plugins = host.plugins.list(); tools = ToolCatalog.resolve(host.registry.list()) }
    fun report(failure: Throwable, title: String) {
        if (failure is CancellationException) return
        val details = if (failure is ToolFailure) failure.code + "\n" + failure.details.toString() else failure.javaClass.simpleName + "\n" + failure.message.orEmpty()
        errorDetails = ErrorDetails(title, UiText.failure(failure), details)
    }
    fun closeRequested() {
        if (tasks.any { it.state in setOf(TaskState.Running, TaskState.Queued, TaskState.Cancelling) }) requestExit = true else onExit?.invoke()
    }
    fun cancelAndExit() { tasks.forEach { host.tasks.cancel(it.id) }; onExit?.invoke() }
    override fun close() { scope.cancel(); forms.values.forEach { it.job?.cancel() }; host.close() }

    companion object {
        fun defaultDataRoot(): Path = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.dir"), "KaffuBox")
        private fun defaultOutput(): Path = Path.of(System.getProperty("user.home"), "Downloads", "KaffuBox")
        private fun findEngine(explicit: String?, name: String): String? {
            val candidates = buildList {
                explicit?.let { add(Path.of(it)) }
                System.getProperty("compose.application.resources.dir")?.let { add(Path.of(it, "Engines", name)) }
                System.getProperty("jpackage.app-path")?.let { add(Path.of(it).parent.resolve("Engines").resolve(name)) }
                add(Path.of(System.getProperty("user.dir"), "Engines", name))
                System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).filter(String::isNotBlank).forEach { add(Path.of(it.trim('"'), name)) }
            }
            return candidates.firstOrNull { Files.isRegularFile(it) }?.toAbsolutePath()?.toString()
        }
    }
}
