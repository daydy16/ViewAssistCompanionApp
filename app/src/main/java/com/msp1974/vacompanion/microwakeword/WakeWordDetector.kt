package com.msp1974.vacompanion.microwakeword

import android.util.Log
import com.example.microfeatures.MicroFrontend
import java.nio.ByteBuffer

private const val SAMPLES_PER_CHUNK = 160  // 10ms at 16kHz
private const val BYTES_PER_SAMPLE = 2     // PCM 16-bit
private const val BYTES_PER_CHUNK = SAMPLES_PER_CHUNK * BYTES_PER_SAMPLE

/**
 * Main wake word detector using TensorFlow MicroFrontend for feature extraction
 * and TFLite models for inference. Processes raw PCM audio and returns detection results.
 */
class WakeWordDetector(private val wakeWordProvider: WakeWordProvider) : AutoCloseable {
    private val frontend = MicroFrontend()
    private val buffer = ByteBuffer.allocateDirect(BYTES_PER_CHUNK)
    private val wakeWords = wakeWordProvider.getWakeWords()
    private var activeWakeWords = listOf<MicroWakeWord>()

    data class DetectionResult(
        val wakeWordId: String,
        val wakeWordPhrase: String,
        val probability: Float = 0f
    )

    /**
     * Process raw PCM audio buffer and detect wake words.
     * @param audio Raw PCM 16-bit ByteBuffer from AudioRecorder
     * @return List of detected wake words (typically 0 or 1)
     */
    fun detect(audio: ByteBuffer): List<DetectionResult> {
        val detections = mutableListOf<DetectionResult>()
        buffer.fillFrom(audio)
        while (buffer.flip().remaining() == BYTES_PER_CHUNK) {
            val processOutput = frontend.processSamples(buffer)
            buffer.position(buffer.position() + processOutput.samplesRead * BYTES_PER_SAMPLE)
            buffer.compact()
            buffer.fillFrom(audio)
            if (processOutput.features.isEmpty())
                continue
            for (wakeWord in activeWakeWords) {
                val result = wakeWord.processAudioFeatures(processOutput.features)
                if (result && !detections.any { it.wakeWordId == wakeWord.id })
                    detections.add(DetectionResult(wakeWord.id, wakeWord.wakeWord, wakeWord.getCurrentProbability()))
            }
        }
        buffer.compact()
        return detections
    }

    /**
     * Get the current detection probability for diagnostics display.
     * Returns the max probability across all active wake words.
     */
    fun getCurrentMaxProbability(): Float {
        return activeWakeWords.maxOfOrNull { it.getCurrentProbability() } ?: 0f
    }

    /**
     * Set which wake words should be actively detected.
     * @param wakeWordIds List of wake word IDs (matching JSON manifest filenames without extension)
     */
    fun setActiveWakeWords(wakeWordIds: List<String>) {
        for (wakeWord in activeWakeWords)
            wakeWord.close()
        activeWakeWords = buildList {
            for (wakeWordId in wakeWordIds) {
                val wakeWordWithId = wakeWords.firstOrNull { it.id == wakeWordId }
                if (wakeWordWithId == null) {
                    Log.w(TAG, "Wake word with id $wakeWordId not found")
                    continue
                }
                add(
                    MicroWakeWord(
                        wakeWordWithId.id,
                        wakeWordWithId.wakeWord.wake_word,
                        wakeWordProvider.loadWakeWordModel(wakeWordWithId.wakeWord.model),
                        wakeWordWithId.wakeWord.micro.probability_cutoff,
                        wakeWordWithId.wakeWord.micro.sliding_window_size
                    )
                )
            }
        }
    }

    /**
     * Get available wake word IDs
     */
    fun getAvailableWakeWordIds(): List<String> {
        return wakeWords.map { it.id }
    }

    fun reset() {
        for (wakeWord in activeWakeWords) {
            wakeWord.reset()
        }
    }

    override fun close() {
        frontend.close()
        for (model in activeWakeWords)
            model.close()
    }

    companion object {
        private const val TAG = "WakeWordDetector"
    }
}
