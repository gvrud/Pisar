package ru.pisar.app.audio

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val dataOffset: Long,
    val dataSize: Long,
    val audioFormat: Int = FORMAT_PCM,
) {
    companion object {
        const val FORMAT_PCM = 1
        const val FORMAT_FLOAT = 3
        const val FORMAT_EXTENSIBLE = 0xFFFE
    }
}

object Wav {

    private const val HEADER_SIZE = 44

    fun info(file: File): WavInfo? = runCatching { readInfo(file) }.getOrNull()

    private fun readInfo(file: File): WavInfo? {
        if (!file.exists() || file.length() < HEADER_SIZE) return null
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val header = ByteArray(12)
            raf.readFully(header)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            if (String(header, 8, 4, Charsets.US_ASCII) != "WAVE") return null

            var audioFormat = WavInfo.FORMAT_PCM
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var dataOffset = -1L
            var dataSize = -1L

            val chunk = ByteArray(8)
            while (raf.filePointer + 8 <= length) {
                val chunkStart = raf.filePointer
                raf.readFully(chunk)
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = leInt(chunk, 4).toLong() and 0xFFFFFFFFL
                val payloadStart = chunkStart + 8

                when (id) {
                    "fmt " -> {
                        val available = (length - payloadStart).coerceAtLeast(0L)
                        val toRead = minOf(size, 40L, available).toInt()
                        if (toRead >= 16) {
                            val fmt = ByteArray(toRead)
                            raf.seek(payloadStart)
                            raf.readFully(fmt)
                            audioFormat = leShort(fmt, 0)
                            channels = leShort(fmt, 2)
                            sampleRate = leInt(fmt, 4)
                            bits = leShort(fmt, 14)
                        }
                    }

                    "data" -> {
                        dataOffset = payloadStart
                        dataSize = if (size == 0L || size == 0xFFFFFFFFL) {
                            length - payloadStart
                        } else {
                            minOf(size, length - payloadStart)
                        }
                    }
                }

                if (dataOffset >= 0 && sampleRate > 0) break

                val next = payloadStart + size + (size % 2)
                if (next <= chunkStart || next >= length) break
                raf.seek(next)
            }

            if (sampleRate <= 0 || channels <= 0 || dataOffset < 0 || dataSize <= 0) return null
            return WavInfo(sampleRate, channels, bits, dataOffset, dataSize, audioFormat)
        }
    }

    fun durationMs(file: File): Long {
        val info = info(file) ?: return 0
        val bytesPerSecond = info.sampleRate.toLong() * info.channels * (info.bitsPerSample / 8).coerceAtLeast(1)
        if (bytesPerSecond <= 0) return 0
        return info.dataSize * 1000L / bytesPerSecond
    }

    internal fun leShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    internal fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    internal const val HEADER_SIZE_BYTES = 44
}

class WavWriter(
    private val file: File,
    private val sampleRate: Int = 16000,
    private val channels: Int = 1,
) : Closeable {

    private val raf = RandomAccessFile(file, "rw")
    private val buffer = ByteArray(16 * 1024)
    private var bufferPos = 0
    private var dataBytes = 0L
    private var closed = false

    init {
        raf.setLength(0)
        writeHeader(0)
        raf.seek(HEADER_SIZE.toLong())
    }

    val bytesWritten: Long get() = dataBytes

    fun write(samples: ShortArray, count: Int) {
        var i = 0
        while (i < count) {
            if (bufferPos + 2 > buffer.size) flush()
            val value = samples[i].toInt()
            buffer[bufferPos++] = (value and 0xFF).toByte()
            buffer[bufferPos++] = ((value shr 8) and 0xFF).toByte()
            i++
        }
        dataBytes += count * 2L
    }

    private fun flush() {
        if (bufferPos <= 0) return
        raf.write(buffer, 0, bufferPos)
        bufferPos = 0
    }

    private fun writeHeader(dataSize: Long) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * channels * 2
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataSize).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort((channels * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize.toInt())

        raf.seek(0)
        raf.write(header.array())
        raf.seek(HEADER_SIZE + dataSize)
        raf.setLength(HEADER_SIZE + dataSize)
    }

    override fun close() {
        if (closed) return
        closed = true
        flush()
        writeHeader(dataBytes)
        raf.close()
    }

    private companion object {
        const val HEADER_SIZE = 44
    }
}

class WavReader(val file: File) : Closeable {

    val info: WavInfo = Wav.info(file)
        ?: throw IllegalArgumentException("Не удалось прочитать аудиофайл: ${file.name}")

    private val raf = RandomAccessFile(file, "r").also { it.seek(info.dataOffset) }
    private var remainingBytes = info.dataSize

    val sampleRate: Int get() = info.sampleRate
    val channels: Int get() = info.channels
    val bitsPerSample: Int get() = info.bitsPerSample

    fun read(destination: ShortArray, maxSamples: Int): Int {
        if (remainingBytes < 2) return -1
        val toRead = minOf(maxSamples, (remainingBytes / 2).toInt(), destination.size)
        if (toRead <= 0) return -1
        val bytes = ByteArray(toRead * 2)
        raf.readFully(bytes)
        remainingBytes -= bytes.size
        var i = 0
        var j = 0
        while (i < toRead) {
            destination[i] = ((bytes[j].toInt() and 0xFF) or (bytes[j + 1].toInt() shl 8)).toShort()
            i++
            j += 2
        }
        return toRead
    }

    override fun close() {
        raf.close()
    }
}
