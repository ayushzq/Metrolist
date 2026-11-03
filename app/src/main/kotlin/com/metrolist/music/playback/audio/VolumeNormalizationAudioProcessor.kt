package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

@UnstableApi
class VolumeNormalizationAudioProcessor : BaseAudioProcessor() {
    private var encoding = C.ENCODING_INVALID
    private var bytesPerSample = 0

    @Volatile
    var enabled = false
        set(value) {
            if (field != value) {
                field = value
                Timber.tag(TAG).d("Normalization processor enabled: $value")
            }
        }

    private data class GainState(val targetGainMb: Int, val linearGain: Double)

    @Volatile
    private var currentGain = GainState(0, 1.0)

    @Synchronized
    fun setTargetGain(gainMb: Int) {
        if (currentGain.targetGainMb != gainMb) {
            val linearGain = 10.0.pow(gainMb / 2000.0)
            currentGain = GainState(gainMb, linearGain)
            Timber.tag(TAG).d("Target gain set to $gainMb mB (Linear multiplier: $linearGain)")
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        encoding = inputAudioFormat.encoding
        bytesPerSample =
            when (encoding) {
                C.ENCODING_PCM_16BIT -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            }

        Timber.tag(TAG).d(
            "Configured: sampleRate=${inputAudioFormat.sampleRate}, channels=${inputAudioFormat.channelCount}, encoding=$encoding",
        )
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        val gain = currentGain
        val normalizationLinear = if (enabled && gain.targetGainMb != 0) gain.linearGain else 1.0
        val totalLinear = normalizationLinear * boostLinear
        val applyGain = totalLinear != 1.0
        // Boosting can push peaks past full scale, so use a soft limiter instead of hard clipping
        val limit = totalLinear > 1.0
        val sampleCount = inputBuffer.remaining() / bytesPerSample
        val output = replaceOutputBuffer(sampleCount * bytesPerSample)

        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)

        when (encoding) {
            C.ENCODING_PCM_16BIT ->
                repeat(sampleCount) {
                    val sample = inputBuffer.getShort()
                    output.putShort(
                        if (applyGain) {
                            scale(sample.toDouble(), 32768.0, totalLinear, limit)
                                .roundToInt()
                                .coerceIn(-32768, 32767)
                                .toShort()
                        } else {
                            sample
                        },
                    )
                }

            C.ENCODING_PCM_24BIT ->
                repeat(sampleCount) {
                    val b0 = inputBuffer.get().toInt() and 0xFF
                    val b1 = inputBuffer.get().toInt() and 0xFF
                    val b2 = inputBuffer.get().toInt()
                    val sample = (b2 shl 16) or (b1 shl 8) or b0
                    val processed =
                        if (applyGain) {
                            scale(sample.toDouble(), 8388608.0, totalLinear, limit)
                                .roundToInt()
                                .coerceIn(-8388608, 8388607)
                        } else {
                            sample
                        }
                    output.put((processed and 0xFF).toByte())
                    output.put(((processed shr 8) and 0xFF).toByte())
                    output.put(((processed shr 16) and 0xFF).toByte())
                }

            C.ENCODING_PCM_32BIT ->
                repeat(sampleCount) {
                    val sample = inputBuffer.getInt()
                    output.putInt(
                        if (applyGain) {
                            scale(sample.toDouble(), 2147483648.0, totalLinear, limit)
                                .coerceIn(-2147483648.0, 2147483647.0)
                                .toLong()
                                .toInt()
                        } else {
                            sample
                        },
                    )
                }

            C.ENCODING_PCM_FLOAT ->
                repeat(sampleCount) {
                    val sample = inputBuffer.getFloat()
                    output.putFloat(
                        if (applyGain) {
                            val scaled = sample * totalLinear.toFloat()
                            if (limit) softLimit(scaled.toDouble()).toFloat() else scaled.coerceIn(-1.0f, 1.0f)
                        } else {
                            sample
                        },
                    )
                }
        }

        output.flip()
    }

    /** Applies [linear] gain to an integer sample of full-scale [fullScale]. */
    private fun scale(
        sample: Double,
        fullScale: Double,
        linear: Double,
        limit: Boolean,
    ): Double {
        val scaled = sample / fullScale * linear
        val limited = if (limit) softLimit(scaled) else scaled.coerceIn(-1.0, 1.0)
        return limited * (fullScale - 1.0)
    }

    /** Transparent below [LIMITER_KNEE], smoothly rounds peaks towards +-1.0 above it. */
    private fun softLimit(x: Double): Double {
        val a = abs(x)
        if (a <= LIMITER_KNEE) return x
        val over = (a - LIMITER_KNEE) / (1.0 - LIMITER_KNEE)
        val y = LIMITER_KNEE + (1.0 - LIMITER_KNEE) * tanh(over)
        return if (x < 0) -y else y
    }

    override fun onReset() {
        encoding = C.ENCODING_INVALID
        bytesPerSample = 0
    }

    companion object {
        private const val TAG = "VolumeNormalizationProcessor"
        private const val LIMITER_KNEE = 0.85

        @Volatile
        private var boostLinear = 1.0

        /** User chosen extra gain in millibels (0 = off). Shared by every processor instance. */
        @Volatile
        var boostMb: Int = 0
            set(value) {
                val clamped = value.coerceIn(0, 1200)
                field = clamped
                boostLinear = 10.0.pow(clamped / 2000.0)
            }
    }
}
