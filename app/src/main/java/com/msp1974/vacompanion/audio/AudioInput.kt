package com.msp1974.viewassistcompanionapp.audio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import android.content.Context
import kotlin.coroutines.coroutineContext
import android.annotation.SuppressLint
import com.msp1974.vacompanion.audio.AudioDSP
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.min

/**
 * Represents a chunk of audio data in both raw and float formats.
 * raw: PCM 16-bit ByteBuffer for microWakeWord processing
 * floats: normalized FloatArray for Wyoming streaming and diagnostics
 */
data class AudioChunk(val raw: ByteBuffer, val floats: FloatArray)

/**
 * Handles audio recording from device microphone.
 * Emits audio buffers as a Flow for processing.
 * Includes system audio effects (AEC, AGC, NoiseSuppressor) for far-field voice pickup.
 */
internal class AudioRecorder(
    private val context: Context
) {
    private val config: APPConfig = APPConfig.getInstance(context)

    private var amplitudeBuffer = CircularArray(10)
    private var targetLevel = 0.25f
    private var minAmplification = 0.003f

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_IN_SHORTS = 1280
        const val MAX_LEVEL = 32768f
        const val MIC_GAIN_STEP = 0.025f
    }

    /**
     * Check if audio recording permission is granted.
     */
    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Start recording audio and emit AudioChunk containing both raw ByteBuffer and FloatArray.
     * Always uses VOICE_RECOGNITION audio source and enables system audio effects.
     *
     * @return Flow of AudioChunk containing raw PCM and normalized float samples
     */
    @SuppressLint("MissingPermission")
    fun startRecording(): Flow<AudioChunk> = flow {
        require(hasRecordPermission()) { "RECORD_AUDIO permission not granted" }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufferSize, BUFFER_SIZE_IN_SHORTS * 2)

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("Failed to initialize AudioRecord")
        }

        // Setup system audio effects
        var aec: AcousticEchoCanceler? = null
        var agc: AutomaticGainControl? = null
        var ns: NoiseSuppressor? = null

        val sessionId = audioRecord.audioSessionId
        try {
            aec = AcousticEchoCanceler.create(sessionId)
            aec?.enabled = true
        } catch (e: Exception) {
            Timber.w("AcousticEchoCanceler not available: ${e.message}")
        }
        try {
            agc = AutomaticGainControl.create(sessionId)
            agc?.enabled = true
        } catch (e: Exception) {
            Timber.w("AutomaticGainControl not available: ${e.message}")
        }
        try {
            ns = NoiseSuppressor.create(sessionId)
            ns?.enabled = true
        } catch (e: Exception) {
            Timber.w("NoiseSuppressor not available: ${e.message}")
        }

        Timber.i("Audio recording started with AEC=${aec != null}, AGC=${agc != null}, NS=${ns != null}")

        val audioBuffer = ShortArray(BUFFER_SIZE_IN_SHORTS)

        try {
            audioRecord.startRecording()

            while (coroutineContext.isActive) {
                val readCount = audioRecord.read(audioBuffer, 0, audioBuffer.size)

                if (readCount > 0) {
                    // Create raw ByteBuffer (PCM 16-bit) for microWakeWord
                    val rawBuffer = ByteBuffer.allocateDirect(readCount * 2)
                        .order(ByteOrder.nativeOrder())
                    for (i in 0 until readCount) {
                        rawBuffer.putShort(audioBuffer[i])
                    }
                    rawBuffer.flip()

                    // Create float array for Wyoming streaming and diagnostics
                    var audioFloatBuffer = FloatArray(readCount) { i -> audioBuffer[i] / MAX_LEVEL }

                    // Mic boost for streaming path
                    if (config.useAdvancedGain) {
                        val max = audioFloatBuffer.max()
                        if (max > minAmplification) {
                            amplitudeBuffer.add(max)
                            val target = targetLevel + (config.micGain * MIC_GAIN_STEP)
                            var gain = target / amplitudeBuffer.average()
                            if (max * gain > 0.9) {
                                gain = min(250.0f, (gain / (max * gain)) * 0.9f)
                            }
                            audioFloatBuffer = (audioFloatBuffer.map { i -> i * gain }).toFloatArray()
                        }
                    }

                    emit(AudioChunk(rawBuffer, audioFloatBuffer))
                }
            }
        } finally {
            // Release audio effects
            aec?.release()
            agc?.release()
            ns?.release()

            if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop()
            }
            audioRecord.release()
            Timber.i("Audio input stopped")
        }
    }.flowOn(Dispatchers.Default)
}

class CircularArray(val size: Int) {
    private val buffer = MutableList(size) {0f}
    private var samples: Int = 0

    fun add(value: Float) {
        if (samples < size) samples++
        Collections.rotate(buffer, 1)
        buffer[size - 1] = value
    }

    fun average(): Float {
        return min(1f, buffer.sum() / samples)
    }
}