package ru.pisar.app.asr

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

class ModelMissingException : IOException("Модель распознавания не установлена")

object ModelRepository {

    private const val ASSET_ROOT = "vosk/ru"
    private const val PREFS = "pisar_models"
    private const val KEY_ACTIVE = "active"
    private const val LEGACY_DIR = "asr-model"
    private const val BUNDLED_ID = "vosk-model-small-ru"

    data class Entry(val id: String, val dir: File, val sizeBytes: Long)

    fun root(context: Context): File = File(context.filesDir, "asr")

    fun list(context: Context): List<Entry> {
        migrateLegacy(context)
        val dirs = root(context).listFiles()
            ?.filter { it.isDirectory && File(it, "conf/model.conf").isFile }
            ?: emptyList()
        return dirs.sortedBy { it.name }
            .map { Entry(it.name, it, directorySize(it)) }
    }

    fun active(context: Context): Entry? {
        val entries = list(context)
        if (entries.isEmpty()) return null
        val id = prefs(context).getString(KEY_ACTIVE, null)
        return entries.firstOrNull { it.id == id } ?: entries.first()
    }

    fun setActive(context: Context, id: String) {
        prefs(context).edit().putString(KEY_ACTIVE, id).apply()
    }

    fun isInstalled(context: Context): Boolean = active(context) != null

    fun sizeBytes(context: Context): Long = active(context)?.sizeBytes ?: 0L

    fun ensure(context: Context, onProgress: (Float) -> Unit = {}): File {
        active(context)?.let { return it.dir }
        if (hasBundledModel(context)) {
            installFromAssets(context, onProgress)
            active(context)?.let { return it.dir }
            throw IOException("модель в assets повреждена")
        }
        throw ModelMissingException()
    }

    fun delete(context: Context, id: String) {
        File(root(context), id).deleteRecursively()
        if (prefs(context).getString(KEY_ACTIVE, null) == id) {
            prefs(context).edit().remove(KEY_ACTIVE).apply()
        }
    }

    fun deleteActive(context: Context) {
        active(context)?.let { delete(context, it.id) }
    }

    fun importZip(context: Context, uri: Uri, onProgress: (Float) -> Unit = {}): Entry {
        val tempZip = File(context.cacheDir, "model-${System.nanoTime()}.zip")
        val staging = File(context.cacheDir, "model-staging")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempZip.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
            } ?: throw IOException("не удалось открыть архив")

            staging.deleteRecursively()
            staging.mkdirs()
            unzip(tempZip, staging, onProgress)

            val found = findModelRoot(staging)
                ?: throw IOException("в архиве не найдена папка conf/model.conf")

            val id = sanitize(found.name).ifBlank { "model-${System.currentTimeMillis()}" }
            val target = File(root(context), id)
            if (target.exists()) target.deleteRecursively()
            target.parentFile?.mkdirs()
            if (!found.renameTo(target)) {
                found.copyRecursively(target, overwrite = true)
                found.deleteRecursively()
            }
            if (!File(target, "conf/model.conf").isFile) {
                target.deleteRecursively()
                throw IOException("модель распакована не полностью")
            }
            setActive(context, id)
            onProgress(1f)
            return Entry(id, target, directorySize(target))
        } finally {
            tempZip.delete()
            staging.deleteRecursively()
        }
    }

    fun hasBundledModel(context: Context): Boolean = runCatching {
        context.assets.list("$ASSET_ROOT/conf")?.contains("model.conf") == true
    }.getOrDefault(false)

    private fun migrateLegacy(context: Context) {
        val legacy = File(context.filesDir, LEGACY_DIR)
        if (!File(legacy, "conf/model.conf").isFile) return
        val target = File(root(context), BUNDLED_ID)
        if (target.exists()) {
            legacy.deleteRecursively()
            return
        }
        target.parentFile?.mkdirs()
        if (!legacy.renameTo(target)) {
            legacy.copyRecursively(target, overwrite = true)
            legacy.deleteRecursively()
        }
        if (prefs(context).getString(KEY_ACTIVE, null) == null) setActive(context, BUNDLED_ID)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)

    private fun installFromAssets(context: Context, onProgress: (Float) -> Unit) {
        val target = File(root(context), BUNDLED_ID)
        target.deleteRecursively()
        target.mkdirs()
        val files = ArrayList<String>()
        collectAssets(context, ASSET_ROOT, "", files)
        if (files.isEmpty()) throw IOException("в сборке нет файлов модели")
        files.forEachIndexed { index, relative ->
            val out = File(target, relative)
            out.parentFile?.mkdirs()
            context.assets.open("$ASSET_ROOT/$relative").use { input ->
                out.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
            onProgress((index + 1) / files.size.toFloat())
        }
        setActive(context, BUNDLED_ID)
    }

    private fun findModelRoot(dir: File, depth: Int = 0): File? {
        if (depth > 3) return null
        if (File(dir, "conf/model.conf").isFile) return dir
        val children = dir.listFiles() ?: return null
        for (child in children) {
            if (child.isDirectory) {
                val found = findModelRoot(child, depth + 1)
                if (found != null) return found
            }
        }
        return null
    }

    private fun unzip(zip: File, target: File, onProgress: (Float) -> Unit) {
        val total = zip.length().coerceAtLeast(1L)
        val counting = CountingInputStream(zip.inputStream().buffered(64 * 1024))
        ZipInputStream(counting).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val outFile = File(target, entry.name)
                val rootPath = target.canonicalPath
                if (!outFile.canonicalPath.startsWith(rootPath + File.separator)) {
                    throw IOException("архив содержит недопустимые пути")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                input.closeEntry()
                onProgress((counting.count.toFloat() / total).coerceIn(0f, 0.99f))
            }
        }
    }

    private fun collectAssets(context: Context, root: String, prefix: String, out: MutableList<String>) {
        val path = if (prefix.isEmpty()) root else "$root/$prefix"
        val names = context.assets.list(path) ?: return
        for (name in names) {
            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
            val children = context.assets.list("$root/$relative")
            if (children != null && children.isNotEmpty()) {
                collectAssets(context, root, relative, out)
            } else {
                out.add(relative)
            }
        }
    }

    private fun directorySize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        dir.walkTopDown().forEach { file -> if (file.isFile) size += file.length() }
        return size
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var count: Long = 0L
            private set

        override fun read(): Int {
            val value = super.read()
            if (value >= 0) count++
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = super.read(b, off, len)
            if (read > 0) count += read
            return read
        }
    }
}
