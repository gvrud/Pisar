package ru.pisar.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal object PcmConversion {

    fun toMonoShorts(buffer: ByteBuffer, channels: Int, isFloat: Boolean): ShortArray {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val channelCount = channels.coerceAtLeast(1)

        if (isFloat) {
            val floats = buffer.asFloatBuffer()
            val frames = floats.remaining() / channelCount
            val out = ShortArray(frames)
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channelCount) {
                    sum += floats.get(frame * channelCount + channel)
                }
                out[frame] = ((sum / channelCount) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
            }
            return out
        }

        val shorts = buffer.asShortBuffer()
        val frames = shorts.remaining() / channelCount
        val out = ShortArray(frames)
        if (channelCount == 1) {
            shorts.get(out)
            return out
        }
        for (frame in 0 until frames) {
            var sum = 0
            for (channel in 0 until channelCount) {
                sum += shorts.get(frame * channelCount + channel)
            }
            out[frame] = (sum / channelCount).toShort()
        }
        return out
    }
}
