package ru.pisar.app.audio

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

class UnsupportedAudioException(message: String) : IOException(message)

object AudioImporter {

    private const val TARGET_RATE = 16000

    fun prepareFromUri(context: Context, uri: Uri, output: File, onProgress: (Float) -> Unit = {}): Long {
        val displayName = queryDisplayName(context, uri) ?: "audio"
        val extension = displayName.substringAfterLast('.', "")
            .lowercase(Locale.US)
            .filter { it.isLetterOrDigit() }
            .take(8)
        val temp = File(
            context.cacheDir,
            "import-${System.nanoTime()}" + if (extension.isNotEmpty()) ".$extension" else "",
        )
        try {
            copyUri(context, uri, temp) { progress -> onProgress(progress * 0.15f) }
            return prepareFromFile(temp, output) { progress -> onProgress(0.15f + progress * 0.85f) }
        } finally {
            temp.delete()
        }
    }

    fun prepareFromFile(source: File, output: File, onProgress: (Float) -> Unit = {}): Long {
        if (!source.exists() || source.length() == 0L) {
            throw UnsupportedAudioException("файл не найден или пуст")
        }
        if (output.exists()) output.delete()

        val info = Wav.info(source)
        if (info != null) {
            val isPlainPcm16Mono = info.sampleRate == TARGET_RATE &&
                info.channels == 1 &&
                info.bitsPerSample == 16 &&
                info.audioFormat != WavInfo.FORMAT_FLOAT
            if (isPlainPcm16Mono) {
                copyWav(source, output, info)
                onProgress(1f)
            } else {
                WavPcmDecoder.decodeToWav(source, output, info, onProgress)
            }
            return validatedDuration(output)
        }

        try {
            MediaDecoder.decodeToWav(source, output) { progress -> onProgress(progress * 0.98f) }
        } catch (error: Exception) {
            if (OggOpusDecoder.isOggOpus(source)) {
                OggOpusDecoder.decodeToWav(source, output) { progress -> onProgress(progress * 0.98f) }
            } else {
                throw UnsupportedAudioException(
                    error.message?.takeIf { it.isNotBlank() } ?: "не удалось декодировать файл",
                )
            }
        }
        onProgress(1f)
        return validatedDuration(output)
    }

    private fun validatedDuration(wav: File): Long {
        val info = Wav.info(wav)
            ?: throw UnsupportedAudioException("декодер вернул повреждённый WAV")
        if (info.dataSize <= 0) throw UnsupportedAudioException("в файле нет звука")
        if (info.sampleRate != TARGET_RATE || info.channels != 1 || info.bitsPerSample != 16) {
            throw UnsupportedAudioException(
                "декодер вернул ${info.sampleRate} Гц / ${info.channels} кан. / ${info.bitsPerSample} бит",
            )
        }
        return info.dataSize * 1000L / (TARGET_RATE.toLong() * 2)
    }

    private fun copyWav(source: File, output: File, info: WavInfo) {
        RandomAccessFile(source, "r").use { raf ->
            raf.seek(info.dataOffset)
            val writer = WavWriter(output, TARGET_RATE, 1)
            try {
                val bytes = ByteArray(64 * 1024)
                var remaining = info.dataSize
                while (remaining >= 2) {
                    val toRead = minOf(bytes.size.toLong(), remaining).toInt() and 0x7FFFE
                    if (toRead <= 0) break
                    val read = raf.read(bytes, 0, toRead)
                    if (read < 2) break
                    remaining -= read
                    val samples = ShortArray(read / 2)
                    var index = 0
                    while (index < samples.size) {
                        val low = bytes[index * 2].toInt() and 0xFF
                        val high = bytes[index * 2 + 1].toInt()
                        samples[index] = (low or (high shl 8)).toShort()
                        index++
                    }
                    writer.write(samples, samples.size)
                }
            } finally {
                writer.close()
            }
        }
    }

    private fun copyUri(context: Context, uri: Uri, target: File, onProgress: (Float) -> Unit) {
        val total = querySize(context, uri)
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(128 * 1024)
                var copied = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                }
                output.flush()
            }
        } ?: throw IOException("не удалось открыть файл")
    }

    private fun querySize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()
}
