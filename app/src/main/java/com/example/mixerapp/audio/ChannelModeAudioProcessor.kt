package com.example.mixerapp.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.example.mixerapp.model.AudioMode
import com.example.mixerapp.model.LimiterConfiguration
import java.nio.ByteBuffer
import kotlin.math.*

/**
 * AudioProcessor qui route les deux canaux PCM 16-bit stéréo selon le mode choisi :
 *  - STEREO : pass-through
 *  - LEFT   : canal gauche dupliqué sur les deux sorties
 *  - RIGHT  : canal droit  dupliqué sur les deux sorties
 *
 * Supporte aussi un Limiteur optionnel.
 */
@OptIn(UnstableApi::class)
class ChannelModeAudioProcessor : BaseAudioProcessor() {

    /** Peut être modifié depuis le thread UI ; @Volatile suffit (délai d'un buffer). */
    @Volatile
    var audioMode: AudioMode = AudioMode.STEREO

    @Volatile
    var limiterConfig: LimiterConfiguration = LimiterConfiguration()

    @Volatile
    var volume: Float = 1.0f

    @Volatile
    var masterVolume: Float = 1.0f

    private var inputChannelCount: Int = 2
    private var sampleRate: Int = 44100

    // État du limiteur
    private var currentGain: Float = 1.0f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Supporte PCM16 mono et stereo. Les autres formats restent en pass-through.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            (inputAudioFormat.channelCount != 1 && inputAudioFormat.channelCount != 2)
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        inputChannelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate

        // Mono -> sortie stereo pour permettre un vrai pan L/R.
        if (inputChannelCount == 1) {
            return AudioProcessor.AudioFormat(
                inputAudioFormat.sampleRate,
                2,
                C.ENCODING_PCM_16BIT
            )
        }

        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputSize = if (inputChannelCount == 1) remaining * 2 else remaining
        val output = replaceOutputBuffer(outputSize)

        val config = limiterConfig
        val isLimiterEnabled = config.isEnabled
        val gain = volume * masterVolume
        
        // Pré-calculer les paramètres du limiteur pour ce buffer
        val threshold = if (isLimiterEnabled) 10.0.pow(config.thresholdDb / 20.0).toFloat() else 1.0f
        val ceiling = if (isLimiterEnabled) 10.0.pow(config.ceilingDb / 20.0).toFloat() else 1.0f
        val releaseCoeff = if (isLimiterEnabled && config.releaseMs > 0) {
            exp(-1.0 / (config.releaseMs * 0.001 * sampleRate)).toFloat()
        } else 0f

        if (inputChannelCount == 1) {
            while (inputBuffer.hasRemaining()) {
                val sampleShort = inputBuffer.short
                val s = sampleShort.toFloat() / 32768f
                
                // Audio Routing
                val (rawL, rawR) = when (audioMode) {
                    AudioMode.STEREO -> s to s
                    AudioMode.LEFT -> s to 0f
                    AudioMode.RIGHT -> 0f to s
                }

                // Apply Volume (Track * Master)
                val left = rawL * gain
                val right = rawR * gain
                
                // Apply Limiter
                val outL: Float
                val outR: Float
                
                if (isLimiterEnabled) {
                    val peak = max(abs(left), abs(right))
                    var targetGain = 1.0f
                    if (peak > threshold) {
                        targetGain = threshold / peak
                    }
                    targetGain *= ceiling
                    
                    if (targetGain < currentGain) {
                        currentGain = targetGain // Attack instantané
                    } else {
                        currentGain = currentGain * releaseCoeff + targetGain * (1f - releaseCoeff)
                    }
                    outL = left * currentGain
                    outR = right * currentGain
                } else {
                    outL = left
                    outR = right
                }

                output.putShort((outL * 32767f).toInt().coerceIn(-32768, 32767).toShort())
                output.putShort((outR * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
            output.flip()
            return
        }

        // Stereo Input
        while (inputBuffer.hasRemaining()) {
            val inL = inputBuffer.short.toFloat() / 32768f
            val inR = inputBuffer.short.toFloat() / 32768f
            
            val (rawL, rawR) = when (audioMode) {
                AudioMode.STEREO -> inL to inR
                AudioMode.LEFT -> inL to 0f
                AudioMode.RIGHT -> 0f to inR
            }

            // Apply Volume (Track * Master)
            val left = rawL * gain
            val right = rawR * gain

            val outL: Float
            val outR: Float
            
            if (isLimiterEnabled) {
                val peak = max(abs(left), abs(right))
                var targetGain = 1.0f
                if (peak > threshold) {
                    targetGain = threshold / peak
                }
                targetGain *= ceiling
                
                if (targetGain < currentGain) {
                    currentGain = targetGain
                } else {
                    currentGain = currentGain * releaseCoeff + targetGain * (1f - releaseCoeff)
                }
                outL = left * currentGain
                outR = right * currentGain
            } else {
                outL = left
                outR = right
            }

            output.putShort((outL * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            output.putShort((outR * 32767f).toInt().coerceIn(-32768, 32767).toShort())
        }
        output.flip()
    }
}

