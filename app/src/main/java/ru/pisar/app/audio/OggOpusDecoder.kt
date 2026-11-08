package ru.pisar.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

object OggOpusDecoder {

    private const val TARGET_RATE = 16000
    private val OPUS_HEAD = "OpusHead".toByteArray(Charsets.US_ASCII)

    fun isOggOpus(file: File): Boolean {
        if (!file.exists() || file.length() < 32) return false
        file.inputStream().buffered(2048).use { input ->
            val head = ByteArray(4)
            if (input.read(head) != 4) return false
            if (String(head, Charsets.US_ASCII) != "OggS") return false
            val probe = ByteArray(2048)
            val read = input.read(probe)
            if (read <= 0) return false
            return indexOf(probe, read, OPUS_HEAD) >= 0
        }
    }

    fun decodeToWav(input: File, output: File, onProgress: (Float) -> Unit = {}): Long {
        val total = input.length().coerceAtLeast(1L)
        val counting = CountingInputStream(FileInputStream(input))
        val reader = OggPacketReader(counting)
        val writer = WavWriter(output, TARGET_RATE, 1)

        var session: OpusSession? = null
        var channels = 1
        var preSkip = 0
        var headerParsed = false

        try {
            while (true) {
                val packet = reader.readPacket() ?: break
                if (!headerParsed) {
                    if (packet.size >= 19 && String(packet, 0, 8, Charsets.US_ASCII) == "OpusHead") {
                        channels = (packet[9].toInt() and 0xFF).coerceAtLeast(1)
                        preSkip = (packet[10].toInt() and 0xFF) or ((packet[11].toInt() and 0xFF) shl 8)
                        headerParsed = true
                    }
                    continue
                }
                if (packet.size >= 8 && String(packet, 0, 8, Charsets.US_ASCII) == "OpusTags") continue
                if (packet.isEmpty()) continue

                val active = session ?: OpusSession(channels, preSkip, writer).also { session = it }
                active.feed(packet)
                onProgress((counting.count.toFloat() / total).coerceIn(0f, 0.99f))
            }
            session?.finish()
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException(error.message ?: error.javaClass.simpleName, error)
        } finally {
            writer.close()
        }

        return MediaDecoder.durationOf(output)
    }

    private fun indexOf(haystack: ByteArray, haystackLength: Int, needle: ByteArray): Int {
        if (needle.isEmpty() || haystackLength < needle.size) return -1
        outer@ for (i in 0..haystackLength - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private class OpusSession(
        channels: Int,
        private val preSkip: Int,
        private val writer: WavWriter,
    ) {

        private val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        private val info = MediaCodec.BufferInfo()
        private val resampler = Pcm16Resampler(48000, TARGET_RATE)
        private var skipped = 0
        private var ptsUs = 0L
        private var outChannels = channels
        private var isFloat = false
        private var eos = false
        private var finished = false

        init {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, channels)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            codec.configure(format, null, null, 0)
            codec.start()
        }

        fun feed(packet: ByteArray) {
            var attempts = 0
            while (true) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = codec.getInputBuffer(index) ?: return
                    buffer.clear()
                    buffer.put(packet)
                    codec.queueInputBuffer(index, 0, packet.size, ptsUs, 0)
                    ptsUs += 20_000L
                    break
                }
                drain()
                if (++attempts > 50) throw IOException("декодер Opus не принимает данные")
            }
            drain()
        }

        fun finish() {
            if (finished) return
            finished = true

            var attempts = 0
            while (true) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain()
                if (++attempts > 50) break
            }

            var guard = 0
            while (!eos && guard++ < 3000) drain()
            resampler.flush { samples, count -> writer.write(samples, count) }

            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
        }

        private fun drain() {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 0)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        outChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }

                    index >= 0 -> {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index)
                            if (buffer != null) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                var mono = PcmConversion.toMonoShorts(buffer, outChannels, isFloat)
                                if (skipped < preSkip && mono.isNotEmpty()) {
                                    val drop = minOf(mono.size, preSkip - skipped)
                                    skipped += drop
                                    mono = if (drop >= mono.size) ShortArray(0) else mono.copyOfRange(drop, mono.size)
                                }
                                if (mono.isNotEmpty()) {
                                    resampler.process(mono, mono.size) { samples, count ->
                                        writer.write(samples, count)
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    }

                    else -> return
                }
            }
        }
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

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) count += skipped
            return skipped
        }
    }

    private class OggPacketReader(private val input: InputStream) {

        private val packet = ByteArrayOutputStream(8192)
        private var streamSerial = -1
        private var packetOpen = false
        private val header = ByteArray(27)

        fun readPacket(): ByteArray? {
            while (true) {
                if (!readFully(header, 27)) return null
                if (header[0] != 0x4F.toByte() || header[1] != 0x67.toByte() ||
                    header[2] != 0x67.toByte() || header[3] != 0x53.toByte()
                ) {
                    return null
                }

                val headerType = header[5].toInt() and 0xFF
                val serial = leInt(header, 14)
                val segmentCount = header[26].toInt() and 0xFF
                val lacing = ByteArray(segmentCount)
                if (!readFully(lacing, segmentCount)) return null

                var bodySize = 0
                for (index in 0 until segmentCount) bodySize += lacing[index].toInt() and 0xFF
                val body = ByteArray(bodySize)
                if (!readFully(body, bodySize)) return null

                if (streamSerial == -1) streamSerial = serial
                if (serial != streamSerial) continue

                if (headerType and 0x01 == 0 && packetOpen) {
                    packet.reset()
                    packetOpen = false
                }

                var offset = 0
                for (index in 0 until segmentCount) {
                    val length = lacing[index].toInt() and 0xFF
                    packet.write(body, offset, length)
                    offset += length
                    if (length < 255) {
                        val bytes = packet.toByteArray()
                        packet.reset()
                        packetOpen = false
                        return bytes
                    }
                }
                packetOpen = true
            }
        }

        private fun readFully(target: ByteArray, length: Int): Boolean {
            var offset = 0
            while (offset < length) {
                val read = input.read(target, offset, length - offset)
                if (read <= 0) return false
                offset += read
            }
            return true
        }

        private fun leInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}
