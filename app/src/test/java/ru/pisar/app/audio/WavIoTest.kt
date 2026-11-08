package ru.pisar.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavIoTest {

    @Test
    fun writerProducesReadableWav() {
        val file = File.createTempFile("pisar-wav", ".wav")
        try {
            val writer = WavWriter(file, 16000, 1)
            val samples = ShortArray(1600) { ((it * 7) % 1000).toShort() }
            writer.write(samples, samples.size)
            writer.close()

            val info = Wav.info(file)
            assertNotNull(info)
            assertEquals(16000, info!!.sampleRate)
            assertEquals(1, info.channels)
            assertEquals(16, info.bitsPerSample)
            assertEquals(WavInfo.FORMAT_PCM, info.audioFormat)
            assertEquals(44L, info.dataOffset)
            assertEquals(3200L, info.dataSize)
            assertEquals(100L, Wav.durationMs(file))

            WavReader(file).use { reader ->
                val buffer = ShortArray(4096)
                assertEquals(1600, reader.read(buffer, buffer.size))
                assertEquals(samples[100], buffer[100])
                assertEquals(-1, reader.read(buffer, buffer.size))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun parserSkipsUnknownChunks() {
        val file = File.createTempFile("pisar-extra", ".wav")
        try {
            file.writeBytes(buildWav(sampleRate = 44100, channels = 2, bits = 16, extraChunk = true, dataSize = 320))

            val info = Wav.info(file)
            assertNotNull(info)
            assertEquals(44100, info!!.sampleRate)
            assertEquals(2, info.channels)
            assertEquals(16, info.bitsPerSample)
            assertEquals(320L, info.dataSize)
        } finally {
            file.delete()
        }
    }

    @Test
    fun decoderResamplesStereo44kTo16kMono() {
        val source = File.createTempFile("pisar-44k", ".wav")
        val output = File.createTempFile("pisar-out", ".wav")
        try {
            source.writeBytes(buildWav(sampleRate = 44100, channels = 2, bits = 16, dataSize = 4410 * 4))

            val info = Wav.info(source)
            assertNotNull(info)
            WavPcmDecoder.decodeToWav(source, output, info!!)

            val outInfo = Wav.info(output)
            assertNotNull(outInfo)
            assertEquals(16000, outInfo!!.sampleRate)
            assertEquals(1, outInfo.channels)
            assertEquals(16, outInfo.bitsPerSample)

            val frames = (outInfo.dataSize / 2).toInt()
            assertTrue("frames=$frames", frames in 1590..1610)
            assertEquals(100L, Wav.durationMs(output))
        } finally {
            source.delete()
            output.delete()
        }
    }

    private fun buildWav(
        sampleRate: Int,
        channels: Int,
        bits: Int,
        dataSize: Int,
        extraChunk: Boolean = false,
    ): ByteArray {
        val extra = if (extraChunk) 8 + 10 else 0
        val total = 12 + 8 + 16 + extra + 8 + dataSize
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(total - 8)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * channels * bits / 8)
        buffer.putShort((channels * bits / 8).toShort())
        buffer.putShort(bits.toShort())
        if (extraChunk) {
            buffer.put("LIST".toByteArray(Charsets.US_ASCII))
            buffer.putInt(10)
            repeat(10) { buffer.put(0) }
        }
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        repeat(dataSize) { buffer.put((it % 50).toByte()) }
        return buffer.array()
    }
}
