package com.msp1974.vacompanion.microwakeword

import android.content.Context
import android.os.Environment
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.ByteBuffer

/**
 * Provides custom wake word models from the device's Downloads/vaca directory.
 * Users can add custom .tflite + .json pairs to Downloads/vaca/ for additional wake words.
 */
class CustomWakeWordProvider(
    private val context: Context,
    private val subPath: String = "vaca"
) : WakeWordProvider {

    override fun getWakeWords(): List<WakeWordWithId> {
        val gson = Gson()
        val wakeWords = mutableListOf<WakeWordWithId>()

        val directories = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subPath),
            File(context.filesDir, subPath)
        )

        for (dir in directories) {
            if (!dir.isDirectory) continue

            dir.listFiles()?.filter { it.extension == "json" }?.forEach { jsonFile ->
                runCatching {
                    val json = jsonFile.readText()
                    val wakeWord: WakeWordConfig =
                        gson.fromJson(json, object : TypeToken<WakeWordConfig>() {}.type)

                    // Verify the corresponding .tflite model exists
                    val modelFile = File(dir, wakeWord.model)
                    if (modelFile.exists()) {
                        val id = jsonFile.nameWithoutExtension
                        wakeWords.add(WakeWordWithId(id, wakeWord))
                    } else {
                        Log.w(TAG, "Model file not found: ${wakeWord.model} for wake word ${jsonFile.name}")
                    }
                }.onFailure {
                    Log.e(TAG, "Error loading custom wake word: ${jsonFile.name}", it)
                }
            }
        }
        return wakeWords
    }

    override fun loadWakeWordModel(model: String): ByteBuffer {
        val directories = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subPath),
            File(context.filesDir, subPath)
        )

        for (dir in directories) {
            val modelFile = File(dir, model)
            if (modelFile.exists()) {
                val bytes = modelFile.readBytes()
                val buffer = ByteBuffer.allocateDirect(bytes.size)
                buffer.put(bytes)
                buffer.rewind()
                return buffer
            }
        }

        throw IllegalArgumentException("Model file not found: $model")
    }

    companion object {
        private const val TAG = "CustomWakeWordProvider"
    }
}
