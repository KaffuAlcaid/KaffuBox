// 校验与安装插件包，管理签名信任、权限、版本回退和等待任务释放的热卸载
package io.kaffubox.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.io.InputStream
import java.nio.file.*
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipInputStream

class PluginManager(
    private val root: Path,
    private val platform: PlatformInfo,
    private val registry: ToolRegistry,
    private val runtime: ScriptRuntimePort,
    private val allowUnsigned: Boolean = false
) : AutoCloseable {
    private val packageRoot = root.resolve("Packages")
    private val stagingRoot = root.resolve("Staging")
    private val store = AtomicStore(root)
    private val records = linkedMapOf<String, InstalledPlugin>()
    private val trustedKeys = linkedMapOf<String, String>()
    private val activeProviders = linkedMapOf<String, String>()
    private val pendingDeletes = mutableSetOf<String>()
    private val lock = Any()

    init {
        Files.createDirectories(packageRoot); Files.createDirectories(stagingRoot)
        store.read("TrustedKeys.json")?.let { trustedKeys.putAll(CoreJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it)) }
        store.read("InstalledPlugins.json")?.let { text ->
            CoreJson.decodeFromString(ListSerializer(InstalledPlugin.serializer()), text).forEach { record ->
                validateManifest(record.manifest)
                records[record.manifest.id] = record
            }
        }
        var recovered = false
        records.values.toList().filter { it.enabled }.forEach { record ->
            try { verifyInstalled(record) }
            catch (failure: ToolFailure) { records[record.manifest.id] = record.copy(enabled = false, lastError = failure.code); recovered = true }
        }
        do {
            var disabled = false
            records.values.toList().filter { it.enabled }.forEach { record ->
                try { checkDependencies(record.manifest) }
                catch (failure: ToolFailure) { records[record.manifest.id] = record.copy(enabled = false, lastError = failure.code); disabled = true; recovered = true }
            }
        } while (disabled)
        records.values.filter { it.enabled }.forEach(::activate)
        if (recovered) save()
        Files.list(stagingRoot).use { entries -> entries.forEach { deletePrivateTree(root, it) } }
    }

    fun list(): List<InstalledPlugin> = synchronized(lock) { records.values.toList() }

    fun trustedSigners(): List<String> = synchronized(lock) { trustedKeys.keys.sorted() }

    fun versions(id: String): List<String> = synchronized(lock) {
        if (id !in records) throw ToolFailure("pluginNotFound")
        Files.list(packageRoot.resolve(id)).use { paths ->
            paths.filter { Files.isDirectory(it) }.map { it.fileName.toString() }.filter { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
                .iterator().asSequence().toList().sortedWith { first, second -> compareVersions(second, first) }
        }
    }

    fun trust(keyId: String, publicKey: String) = synchronized(lock) {
        requireArgument(keyId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")), "keyId")
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        if ((key as java.security.interfaces.RSAPublicKey).modulus.bitLength() < 2048) throw ToolFailure("signatureKeyTooSmall")
        trustedKeys[keyId] = publicKey
        store.write("TrustedKeys.json", CoreJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), trustedKeys))
    }

    fun install(openPackage: () -> InputStream): InstalledPlugin = synchronized(lock) {
        val staging = stagingRoot.resolve("Install" + UUID.randomUUID().toString().replace("-", ""))
        Files.createDirectory(staging)
        try {
            openPackage().use { source ->
                    ZipInputStream(source).use { zip ->
                        var count = 0; var total = 0L; val names = mutableSetOf<String>()
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            if (++count > 2048) throw ToolFailure("pluginEntryLimit")
                            val name = safeEntry(entry.name)
                            if (!names.add(name.lowercase(java.util.Locale.ROOT))) throw ToolFailure("pluginEntryDuplicate")
                            val target = staging.resolve(name).normalize()
                            if (!target.startsWith(staging)) throw ToolFailure("pluginPathInvalid")
                            if (entry.isDirectory) { Files.createDirectories(target); zip.closeEntry(); continue }
                            Files.createDirectories(target.parent)
                            Files.newOutputStream(target, StandardOpenOption.CREATE_NEW).use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val bytes = zip.read(buffer); if (bytes < 0) break
                                    total += bytes; if (total > 64L * 1024 * 1024) throw ToolFailure("pluginSizeLimit")
                                    output.write(buffer, 0, bytes)
                                }
                            }
                            zip.closeEntry()
                        }
                    }
            }
            val manifestPath = staging.resolve("manifest.json")
            if (!Files.isRegularFile(manifestPath) || Files.size(manifestPath) > 256_000) throw ToolFailure("pluginManifestMissing")
            val manifest = CoreJson.decodeFromString(PluginManifest.serializer(), String(Files.readAllBytes(manifestPath), Charsets.UTF_8))
            validateManifest(manifest)
            val entry = staging.resolve(safeEntry(manifest.entry))
            if (!Files.isRegularFile(entry) || Files.size(entry) > 2_000_000) throw ToolFailure("pluginEntryMissing")
            if (manifest.runtime != PluginRuntime.JavaScript) throw ToolFailure("pluginRuntimeUnavailable")
            manifest.ui?.let { if (!Files.isRegularFile(staging.resolve(safeEntry(it)))) throw ToolFailure("pluginUiMissing") }
            val signer = verifySignature(staging)
            val contentHash = contentDigest(staging)
            val old = records[manifest.id]
            if (old != null && old.manifest.version == manifest.version) {
                if (old.packageHash != contentHash) throw ToolFailure("pluginVersionConflict")
                return@synchronized old
            }
            if (old != null && compareVersions(manifest.version, old.manifest.version) <= 0) throw ToolFailure("pluginUpgradeRequired")
            if (old != null && !old.granted.containsAll(manifest.permissions) && old.enabled) throw ToolFailure("pluginNewPermissions")
            if (old?.enabled == true) checkDependencies(manifest)
            val versionRoot = packageRoot.resolve(manifest.id).resolve(manifest.version)
            if (Files.exists(versionRoot)) throw ToolFailure("pluginVersionExists")
            Files.createDirectories(versionRoot.parent)
            Files.move(staging, versionRoot)
            val record = InstalledPlugin(manifest, old?.enabled ?: false, old?.granted.orEmpty().intersect(manifest.permissions), signer, System.currentTimeMillis(), contentHash)
            records[manifest.id] = record
            try {
                save()
                if (record.enabled) activate(record)
            } catch (failure: Exception) {
                if (old == null) records.remove(manifest.id) else records[manifest.id] = old
                save(); deletePrivateTree(root, versionRoot); throw failure
            }
            pendingDeletes.remove(manifest.id)
            record
        } finally { deletePrivateTree(root, staging) }
    }

    fun enable(id: String, granted: Set<Capability>): InstalledPlugin = synchronized(lock) {
        val current = records[id] ?: throw ToolFailure("pluginNotFound")
        if (!granted.containsAll(current.manifest.permissions)) throw ToolFailure("permissionDenied")
        verifyInstalled(current)
        checkDependencies(current.manifest)
        val updated = current.copy(enabled = true, granted = granted.intersect(current.manifest.permissions), lastError = null)
        records[id] = updated
        try { save(); activate(updated) } catch (failure: Exception) { records[id] = current; save(); throw failure }
        updated
    }

    fun disable(id: String): InstalledPlugin = synchronized(lock) {
        val current = records[id] ?: throw ToolFailure("pluginNotFound")
        ensureNoDependents(id)
        val updated = current.copy(enabled = false)
        records[id] = updated; save(); registry.remove(id); updated
    }

    fun uninstall(id: String) = synchronized(lock) {
        val previous = records[id] ?: throw ToolFailure("pluginNotFound")
        ensureNoDependents(id)
        records.remove(id)
        try { save() } catch (failure: Exception) { records[id] = previous; throw failure }
        pendingDeletes += id
        registry.remove(id)
        collect(id)
    }

    fun rollback(id: String, version: String): InstalledPlugin = synchronized(lock) {
        val current = records[id] ?: throw ToolFailure("pluginNotFound")
        parseVersion(version)
        val target = packageRoot.resolve(id).resolve(version)
        if (!Files.isDirectory(target)) throw ToolFailure("pluginVersionMissing")
        val manifest = CoreJson.decodeFromString(PluginManifest.serializer(), String(Files.readAllBytes(target.resolve("manifest.json")), Charsets.UTF_8))
        validateManifest(manifest); requireArgument(manifest.id == id && manifest.version == version, "manifestIdentity")
        if (!current.granted.containsAll(manifest.permissions)) throw ToolFailure("permissionDenied")
        if (records.values.any { it.enabled && it.manifest.dependencies[id]?.let { minimum -> compareVersions(version, minimum) < 0 } == true }) throw ToolFailure("pluginHasDependents")
        if (current.enabled) checkDependencies(manifest)
        val updated = current.copy(manifest = manifest, signedBy = verifySignature(target), packageHash = contentDigest(target))
        records[id] = updated
        try { save(); if (updated.enabled) activate(updated) } catch (failure: Exception) { records[id] = current; save(); throw failure }
        updated
    }

    private fun activate(record: InstalledPlugin) {
        val manifest = record.manifest
        val entry = packageRoot.resolve(manifest.id).resolve(manifest.version).resolve(safeEntry(manifest.entry))
        val source = String(Files.readAllBytes(entry), Charsets.UTF_8)
        val token = UUID.randomUUID().toString()
        val tools = manifest.tools.map { tool ->
            ToolDescriptor(tool.id, tool.workbench, manifest.permissions, tool.inputSchema, manifest.id, manifest.version, tool.name, tool.description) to ToolOperation { arguments, context ->
                runtime.execute(ScriptRequest(source, tool.function, arguments), context.restricted(record.granted))
            }
        }
        activeProviders[token] = manifest.id
        val provider = ToolProvider(manifest.id, manifest.version, tools) {
            synchronized(lock) { activeProviders.remove(token); collect(manifest.id) }
        }
        try { registry.install(provider) } catch (failure: Exception) { activeProviders.remove(token); throw failure }
    }

    private fun collect(id: String) {
        if (id in pendingDeletes && activeProviders.values.none { it == id }) {
            deletePrivateTree(root, packageRoot.resolve(id)); pendingDeletes.remove(id)
        }
    }

    private fun checkDependencies(manifest: PluginManifest) {
        for ((id, version) in manifest.dependencies) {
            val dependency = records[id]
            if (dependency?.enabled != true || compareVersions(dependency.manifest.version, version) < 0) throw ToolFailure("pluginDependencyMissing", jsonObject("id" to id))
        }
        fun visit(id: String, path: Set<String>) {
            if (id in path) throw ToolFailure("pluginDependencyCycle")
            val item = if (id == manifest.id) manifest else records[id]?.manifest ?: return
            item.dependencies.keys.forEach { visit(it, path + id) }
        }
        visit(manifest.id, emptySet())
    }

    private fun verifyInstalled(record: InstalledPlugin) {
        val directory = packageRoot.resolve(record.manifest.id).resolve(record.manifest.version)
        if (!Files.isRegularFile(directory.resolve("manifest.json"))) throw ToolFailure("pluginManifestMissing")
        val manifest = try { CoreJson.decodeFromString(PluginManifest.serializer(), String(Files.readAllBytes(directory.resolve("manifest.json")), Charsets.UTF_8)) }
        catch (_: kotlinx.serialization.SerializationException) { throw ToolFailure("pluginManifestInvalid") }
        if (manifest != record.manifest || contentDigest(directory) != record.packageHash) throw ToolFailure("pluginContentMismatch")
        verifySignature(directory)
    }

    private fun ensureNoDependents(id: String) {
        if (records.values.any { it.enabled && id in it.manifest.dependencies }) throw ToolFailure("pluginHasDependents")
    }

    private fun save() { store.write("InstalledPlugins.json", CoreJson.encodeToString(ListSerializer(InstalledPlugin.serializer()), records.values.toList())) }

    private fun validateManifest(manifest: PluginManifest) {
        if (manifest.schemaVersion != 1 || manifest.apiVersion != 1) throw ToolFailure("pluginApiIncompatible")
        requireArgument(manifest.id.matches(Regex("[a-z][a-zA-Z0-9]*(?:\\.[a-z][a-zA-Z0-9]*)+")) && manifest.id.length <= 128, "pluginId")
        parseVersion(manifest.version)
        if (platform.id !in manifest.platforms) throw ToolFailure("pluginPlatformUnsupported")
        requireArgument(manifest.tools.size in 1..128 && manifest.tools.map { it.id }.distinct().size == manifest.tools.size, "tools")
        manifest.tools.forEach {
            requireArgument(it.id.startsWith(manifest.id + ".") && it.id.matches(Regex("[A-Za-z][A-Za-z0-9.]*")), "toolId")
            requireArgument(it.function.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}")), "function")
        }
        manifest.dependencies.forEach { (id, version) -> requireArgument(id != manifest.id, "dependency"); parseVersion(version) }
    }

    private fun verifySignature(directory: Path): String? {
        val signaturePath = directory.resolve("signature.json")
        if (!Files.exists(signaturePath)) {
            if (!allowUnsigned) throw ToolFailure("pluginSignatureRequired")
            return null
        }
        val signature = CoreJson.decodeFromString(PluginSignature.serializer(), String(Files.readAllBytes(signaturePath), Charsets.UTF_8))
        if (signature.algorithm != "SHA256withRSA") throw ToolFailure("pluginSignatureAlgorithm")
        val publicKey = trustedKeys[signature.keyId] ?: throw ToolFailure("pluginSignerUntrusted")
        val hashes = hashFiles(directory)
        if (hashes != signature.hashes) throw ToolFailure("pluginContentMismatch")
        val payload = hashes.toSortedMap().entries.joinToString("\n") { it.key + ":" + it.value }.toByteArray(Charsets.UTF_8)
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)))
        val verifier = Signature.getInstance("SHA256withRSA").apply { initVerify(key); update(payload) }
        if (!verifier.verify(Base64.getDecoder().decode(signature.signature))) throw ToolFailure("pluginSignatureInvalid")
        return signature.keyId
    }

    private fun hashFiles(directory: Path): Map<String, String> = Files.walk(directory).use { paths ->
        val result = sortedMapOf<String, String>()
        paths.filter { Files.isRegularFile(it) }.forEach { path ->
            val name = directory.relativize(path).toString().replace('\\', '/')
            if (name != "signature.json") {
                val digest = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(path).use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
                result[name] = digest.digest().toHex()
            }
        }
        result
    }

    private fun contentDigest(directory: Path): String = MessageDigest.getInstance("SHA-256").digest(hashFiles(directory).entries.joinToString("\n") { it.key + ":" + it.value }.toByteArray(Charsets.UTF_8)).toHex()

    private fun safeEntry(value: String): String {
        requireArgument(value.isNotEmpty() && value.length <= 512 && !value.startsWith('/') && '\\' !in value, "entry")
        val parts = value.trimEnd('/').split('/')
        parts.forEach(::safeFileName)
        return parts.joinToString("/")
    }

    override fun close() = synchronized(lock) { records.keys.toList().forEach(registry::remove) }
}

fun parseVersion(version: String): List<Int> {
    requireArgument(version.matches(Regex("(?:0|[1-9][0-9]{0,7})\\.(?:0|[1-9][0-9]{0,7})\\.(?:0|[1-9][0-9]{0,7})")), "version")
    return version.split('.').map(String::toInt)
}

fun compareVersions(first: String, second: String): Int {
    val a = parseVersion(first); val b = parseVersion(second)
    for (index in a.indices) { val difference = a[index].compareTo(b[index]); if (difference != 0) return difference }
    return 0
}
