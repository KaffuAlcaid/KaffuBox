// 预览批量名称并执行重命名或创建改名副本，沿用文件引用与授权目录
package io.kaffubox.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import java.util.regex.Pattern

object BatchFileTools {
    fun definitions(): List<ToolDefinition> = listOf(
        defineTool("file.renamePreview", Workbench.File, fields, listOf("files"), setOf(Capability.FileRead)) { args, context -> jsonObject("entries" to plan(args, context)) },
        defineTool("file.rename", Workbench.File, fields + ("mode" to "string"), listOf("files"), setOf(Capability.FileRead, Capability.FileWrite)) { args, context ->
            val plan = plan(args, context)
            val renames = plan.map { it.string("id") to it.string("name") }
            if (args.string("mode", "copy") == "rename") {
                if (Capability.FileManage !in context.granted) throw ToolFailure("permissionDenied")
                val refs = context.files.renameBatch(renames)
                jsonObject("files" to CoreJson.encodeToJsonElement(refs))
            } else {
                val outputs = mutableListOf<FileRef>()
                for ((index, rename) in renames.withIndex()) {
                    context.checkpoint(index.toLong(), renames.size.toLong(), "copying")
                    val source = context.files.resolve(rename.first)
                    val ref = context.files.createOutput(rename.second, source.ref.mimeType)
                    try {
                        var count = 0L
                        source.openInput().use { input -> context.files.resolve(ref.id).openOutput!!.invoke().use { output ->
                            val buffer = ByteArray(65536)
                            while (true) { context.checkpoint(); val read = input.read(buffer); if (read < 0) break; output.write(buffer, 0, read); count += read }
                        } }
                        outputs += ref.copy(size = count)
                    } catch (failure: Throwable) { context.files.discard(ref); throw failure }
                }
                jsonObject("files" to CoreJson.encodeToJsonElement(outputs))
            }
        }
    )
    private val fields = mapOf("files" to "array", "prefix" to "string", "suffix" to "string", "pattern" to "string", "replacement" to "string", "numbering" to "boolean", "start" to "integer", "digits" to "integer", "template" to "string")
    private suspend fun plan(args: JsonObject, context: ToolContext): List<JsonObject> {
        val ids = args.strings("files"); requireArgument(ids.isNotEmpty() && ids.size <= 10000 && ids.distinct().size == ids.size, "files")
        val start = args.int("start", 1); val digits = args.int("digits", 3); requireArgument(start >= 0 && digits in 1..12, "sequence")
        val pattern = args.optionalString("pattern")?.takeIf(String::isNotEmpty)?.let { safePattern(it) }
        val coroutine = currentCoroutineContext()
        val deadline = System.nanoTime() + 2_000_000_000L
        val names = mutableSetOf<String>()
        return ids.mapIndexed { index, id ->
            context.checkpoint()
            val ref = context.files.resolve(id).ref
            val extension = ref.name.substringAfterLast('.', "")
            var stem = if (extension.isEmpty()) ref.name else ref.name.dropLast(extension.length + 1)
            if (pattern != null) stem = try { pattern.matcher(TimedText(stem, deadline) { coroutine.ensureActive() }).replaceAll(args.string("replacement", "")) } catch (_: StackOverflowError) { throw ToolFailure("regexTimeout") }
            val sequence = (start.toLong() + index).toString().padStart(digits, '0')
            val template = args.string("template", "{name}")
            val base = template.replace("{name}", stem).replace("{index}", sequence)
            val result = safeFileName(args.string("prefix", "") + (if (args.flag("numbering") && !template.contains("{index}")) "$sequence-" else "") + base + args.string("suffix", "") + if (extension.isEmpty()) "" else ".$extension")
            requireArgument(names.add(result.lowercase(java.util.Locale.ROOT)), "duplicateOutputNames")
            jsonObject("id" to id, "originalName" to ref.name, "name" to result)
        }
    }
}
