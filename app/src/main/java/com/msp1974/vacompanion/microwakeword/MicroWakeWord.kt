package com.msp1974.vacompanion.microwakeword

import android.util.Log
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer

private const val STRIDE = 3

/**
 * Represents a single microWakeWord model using TFLite inference.
 * Handles sliding window probability averaging for robust detection.
 */
class MicroWakeWord(
    val id: String,
    val wakeWord: String,
    private val model: ByteBuffer,
    private val probabilityCutoff: Float,
    private val slidingWindowSize: Int
) : AutoCloseable {
    private var interpreter: Interpreter? = null
    private var isInitialized = false
    private var inputTensorBuffer: TensorBuffer? = null
    private var outputScale: Float = 0f
    private var outputZeroPoint: Int = 0
    private val probabilities = ArrayDeque<Float>(slidingWindowSize)

    private fun initializeIfNeeded() {
        if (!isInitialized) {
            try {
                val tempModel = model.duplicate()
                tempModel.rewind()
                interpreter = Interpreter(tempModel)

                interpreter?.allocateTensors()
                val inputDetails = interpreter!!.getInputTensor(0)
                val inputQuantParams = inputDetails.quantizationParams()
                inputTensorBuffer = TensorBuffer.create(
                    inputDetails.dataType(),
                    inputDetails.shape(),
                    inputQuantParams.scale,
                    inputQuantParams.zeroPoint
                )

                val outputDetails = interpreter!!.getOutputTensor(0)
                val outputQuantParams = outputDetails.quantizationParams()
                outputScale = outputQuantParams.scale
                outputZeroPoint = outputQuantParams.zeroPoint

                isInitialized = true
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "TensorFlow Lite native library not available", e)
                throw RuntimeException("TensorFlow Lite is not supported on this device", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize TensorFlow Lite interpreter", e)
                throw e
            }
        }
    }

    /**
     * Process audio features through the TFLite model.
     * @return true if wake word is detected (sliding window average > cutoff)
     */
    fun processAudioFeatures(features: FloatArray): Boolean {
        if (features.isEmpty())
            return false

        initializeIfNeeded()

        if (!isInitialized) {
            Log.w(TAG, "Interpreter not initialized, returning false")
            return false
        }

        val tensorBuffer = inputTensorBuffer ?: return false
        if (features.size * STRIDE != tensorBuffer.flatSize)
            error("Unexpected feature size ${features.size} for stride $STRIDE and tensor size ${tensorBuffer.flatSize}")

        tensorBuffer.put(features)
        if (!tensorBuffer.isComplete)
            return false

        val probability = getWakeWordProbability(tensorBuffer.getTensor())
        tensorBuffer.clear()
        return isWakeWordDetected(probability)
    }

    /**
     * Returns the current average probability for diagnostics.
     */
    fun getCurrentProbability(): Float {
        return if (probabilities.isNotEmpty()) probabilities.average().toFloat() else 0f
    }

    private fun getWakeWordProbability(input: ByteBuffer): Float {
        val output = Array(1) { ByteArray(1) }
        interpreter?.run(input, output) ?: return 0f
        val probability = (output[0][0].toUByte().toFloat() - outputZeroPoint) * outputScale
        return probability
    }

    private fun isWakeWordDetected(probability: Float): Boolean {
        if (probabilities.size == slidingWindowSize)
            probabilities.removeFirst()
        probabilities.add(probability)
        return probabilities.size == slidingWindowSize && probabilities.average() > probabilityCutoff
    }

    fun reset() {
        probabilities.clear()
    }

    override fun close() {
        interpreter?.close()
    }

    companion object {
        private const val TAG = "MicroWakeWord"
    }
}
