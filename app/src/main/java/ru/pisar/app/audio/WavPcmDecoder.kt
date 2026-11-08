package ru.pisar.app.audio

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToInt

object WavPcmDecoder {

    private const val TARGET_RATE = 16000

    fun decodeToWav(
        source: File,
        output: File,
        info: WavInfo,
        onProgress: (Float) -> Unit = {},
    ): Long {
        val bytesPerSample = (info.bitsPerSample + 7) / 8
        if (bytesPerSample !in 1..4 || info.channels <= 0) {
            throw UnsupportedAudioException(
                "WAV ${info.bitsPerSample} бит / ${info.channels} кан. не поддерживается",
            )
        }

        val frameBytes = bytesPerSample * info.channels
        val framesPerChunk = (64 * 1024 / frameBytes).coerceAtLeast(256)
        val buffer = ByteArray(framesPerChunk * frameBytes)

        RandomAccessFile(source, "r").use { raf ->
            raf.seek(info.dataOffset)
            val writer = WavWriter(output, TARGET_RATE, 1)
            try {
                val resampler = Pcm16Resampler(info.sampleRate, TARGET_RATE)
                var remaining = info.dataSize
                val totalData = info.dataSize.coerceAtLeast(1L)

                while (remaining >= frameBytes) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = raf.read(buffer, 0, toRead)
                    if (read < frameBytes) break
                    remaining -= read

                    val frames = read / frameBytes
                    val mono = ShortArray(frames)
                    for (frame in 0 until frames) {
                        var sum = 0f
                        var offset = frame * frameBytes
                        for (channel in 0 until info.channels) {
                            sum += toFloat(buffer, offset, bytesPerSample, info.audioFormat)
                            offset += bytesPerSample
                        }
                        val value = (sum / info.channels * 32767f).roundToInt().coerceIn(-32768, 32767)
                        mono[frame] = value.toShort()
                    }

                    resampler.process(mono, frames) { samples, count -> writer.write(samples, count) }
                    onProgress((1f - remaining.toFloat() / totalData).coerceIn(0f, 0.99f))
                }
                resampler.flush { samples, count -> writer.write(samples, count) }
            } finally {
                writer.close()
            }
        }

        return Wav.durationMs(output)
    }

    private fun toFloat(bytes: ByteArray, offset: Int, bytesPerSample: Int, audioFormat: Int): Float {
        val isFloat = audioFormat == WavInfo.FORMAT_FLOAT
        return when (bytesPerSample) {
            1 -> (((bytes[offset].toInt() and 0xFF) - 128) / 128f)
            2 -> (Wav.leShort(bytes, offset).toShort().toInt() / 32768f)
            3 -> {
                var value = (bytes[offset].toInt() and 0xFF) or
                    ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[offset + 2].toInt() and 0xFF) shl 16)
                if (value and 0x800000 != 0) value = value or -0x1000000
                value / 8388608f
            }

            4 -> {
                val raw = Wav.leInt(bytes, offset)
                if (isFloat) Float.fromBits(raw) else (raw / 2147483648f)
            }

            else -> 0f
        }
    }
}
