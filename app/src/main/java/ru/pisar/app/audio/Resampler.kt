package ru.pisar.app.audio

import kotlin.math.roundToInt

class Pcm16Resampler(
    private val inRate: Int,
    private val outRate: Int = 16000,
) {

    private val step = inRate.toDouble() / outRate
    private val outBuffer = ShortArray(8192)
    private var outCount = 0
    private var lastSample = 0.0
    private var position = 0.0
    private var started = false

    val passthrough: Boolean get() = inRate == outRate

    fun process(samples: ShortArray, count: Int, sink: (ShortArray, Int) -> Unit) {
        if (count <= 0) return
        if (!started) {
            lastSample = samples[0].toDouble()
            position = 0.0
            started = true
        }

        val maxIndex = (count - 1).toDouble()
        var pos = position
        while (pos <= maxIndex) {
            val index = pos.toInt()
            val fraction = pos - index
            val a = if (index == 0) lastSample else samples[index - 1].toDouble()
            val b = samples[index].toDouble()
            val value = (a + (b - a) * fraction).roundToInt().coerceIn(-32768, 32767)
            emit(value.toShort(), sink)
            pos += step
        }

        position = pos - count
        lastSample = samples[count - 1].toDouble()
    }

    fun flush(sink: (ShortArray, Int) -> Unit) {
        if (outCount > 0) {
            sink(outBuffer, outCount)
            outCount = 0
        }
    }

    private fun emit(value: Short, sink: (ShortArray, Int) -> Unit) {
        if (outCount == outBuffer.size) {
            sink(outBuffer, outCount)
            outCount = 0
        }
        outBuffer[outCount++] = value
    }
}

fun downmixToMono(interleaved: ShortArray, frames: Int, channels: Int, destination: ShortArray): ShortArray {
    if (channels <= 1) {
        System.arraycopy(interleaved, 0, destination, 0, frames)
        return destination
    }
    for (frame in 0 until frames) {
        var sum = 0
        val base = frame * channels
        for (channel in 0 until channels) sum += interleaved[base + channel]
        destination[frame] = (sum / channels).toShort()
    }
    return destination
}
