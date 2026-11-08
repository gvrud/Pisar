package ru.pisar.app.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException

object MediaDecoder {

    private const val TARGET_RATE = 16000

    fun decodeToWav(input: File, output: File, onProgress: (Float) -> Unit = {}): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val writer = WavWriter(output, TARGET_RATE, 1)

        try {
            extractor.setDataSource(input.absolutePath)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                throw IOException("в файле нет аудиодорожки")
            }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("неизвестный формат")
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }

            val decoder = try {
                MediaCodec.createDecoderByType(mime)
            } catch (error: Exception) {
                throw IOException("декодер $mime недоступен", error)
            }
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var sourceChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                2
            }
            val sourceRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            var isFloat = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
            var resampler = Pcm16Resampler(sourceRate, TARGET_RATE)
            var lastPts = 0L
            var stalled = 0

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)
                        if (buffer == null) {
                            inputDone = true
                        } else {
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                lastPts = extractor.sampleTime.coerceAtLeast(0L)
                                decoder.queueInputBuffer(inputIndex, 0, size, lastPts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        sourceChannels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        val rate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        resampler = Pcm16Resampler(rate, TARGET_RATE)
                    }

                    outputIndex >= 0 -> {
                        stalled = 0
                        if (bufferInfo.size > 0) {
                            val buffer = decoder.getOutputBuffer(outputIndex)
                            if (buffer != null) {
                                buffer.position(bufferInfo.offset)
                                buffer.limit(bufferInfo.offset + bufferInfo.size)
                                val mono = PcmConversion.toMonoShorts(buffer, sourceChannels, isFloat)
                                if (mono.isNotEmpty()) {
                                    resampler.process(mono, mono.size) { samples, count ->
                                        writer.write(samples, count)
                                    }
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }

                    else -> {
                        if (inputDone) stalled++
                        if (stalled > 1500) break
                    }
                }

                if (durationUs > 0) {
                    onProgress((lastPts.toFloat() / durationUs).coerceIn(0f, 0.99f))
                }
            }

            resampler.flush { samples, count -> writer.write(samples, count) }
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException(error.message ?: error.javaClass.simpleName, error)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
            writer.close()
        }

        return durationOf(output)
    }

    fun durationOf(wav: File): Long {
        val info = Wav.info(wav) ?: return 0L
        val bytesPerSecond = info.sampleRate.toLong() * info.channels * (info.bitsPerSample / 8).coerceAtLeast(1)
        if (bytesPerSecond <= 0) return 0L
        return info.dataSize * 1000L / bytesPerSecond
    }
}
