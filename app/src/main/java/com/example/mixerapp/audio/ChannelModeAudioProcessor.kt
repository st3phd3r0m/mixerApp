package com.example.mixerapp.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.example.mixerapp.model.AudioMode
import java.nio.ByteBuffer

/**
 * AudioProcessor qui route les deux canaux PCM 16-bit stéréo selon le mode choisi :
 *  - STEREO : pass-through
 *  - LEFT   : canal gauche dupliqué sur les deux sorties
 *  - RIGHT  : canal droit  dupliqué sur les deux sorties
 *
 * Il est toujours actif (pour les flux 2 canaux PCM_16BIT) afin d'éviter une
 * reconfiguration du pipeline lors d'un changement de mode en cours de lecture.
 */
@OptIn(UnstableApi::class)
class ChannelModeAudioProcessor : BaseAudioProcessor() {

    /** Peut être modifié depuis le thread UI ; @Volatile suffit (délai d'un buffer). */
    @Volatile
    var audioMode: AudioMode = AudioMode.STEREO

    private var inputChannelCount: Int = 2

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Supporte PCM16 mono et stereo. Les autres formats restent en pass-through.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT ||
            (inputAudioFormat.channelCount != 1 && inputAudioFormat.channelCount != 2)
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }

        inputChannelCount = inputAudioFormat.channelCount

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

        if (inputChannelCount == 1) {
            while (inputBuffer.hasRemaining()) {
                val sample = inputBuffer.short
                when (audioMode) {
                    AudioMode.STEREO -> {
                        output.putShort(sample)
                        output.putShort(sample)
                    }
                    AudioMode.LEFT -> {
                        output.putShort(sample)
                        output.putShort(0.toShort())
                    }
                    AudioMode.RIGHT -> {
                        output.putShort(0.toShort())
                        output.putShort(sample)
                    }
                }
            }
            output.flip()
            return
        }

        when (audioMode) {
            AudioMode.STEREO -> {
                // Pass-through direct
                output.put(inputBuffer)
            }
            AudioMode.LEFT -> {
                while (inputBuffer.hasRemaining()) {
                    val left = inputBuffer.short
                    inputBuffer.short          // discard right
                    output.putShort(left)
                    output.putShort(0.toShort())
                }
            }
            AudioMode.RIGHT -> {
                while (inputBuffer.hasRemaining()) {
                    inputBuffer.short          // discard left
                    val right = inputBuffer.short
                    output.putShort(0.toShort())
                    output.putShort(right)
                }
            }
        }
        output.flip()
    }
}

