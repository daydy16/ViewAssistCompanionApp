package com.msp1974.vacompanion.utils

import android.content.Context
import android.os.Environment
import com.msp1974.vacompanion.microwakeword.AssetWakeWordProvider
import com.msp1974.vacompanion.microwakeword.WakeWordConfig
import com.msp1974.vacompanion.utils.AuthUtils.Companion.log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.forEachDirectoryEntry
import kotlin.io.path.isDirectory

data class WakeWord(val name: String, val fileName: String, val builtIn: Boolean = true)

class WakeWords(val context: Context) {

    /**
     * Get built-in wake words by scanning assets/wakeWords/ for .json manifests.
     * Replaces the old hardcoded ONNX-based map.
     */
    private fun getBuiltInWakeWords(): Map<String, WakeWord> {
        val wakeWords = mutableMapOf<String, WakeWord>()
        val assetManager = context.assets

        try {
            val assetsList = assetManager.list(AssetWakeWordProvider.DEFAULT_WAKE_WORD_PATH) ?: emptyArray()
            val gson = Gson()

            for (asset in assetsList) {
                if (!asset.endsWith(".json")) continue

                runCatching {
                    val json = assetManager.open("${AssetWakeWordProvider.DEFAULT_WAKE_WORD_PATH}/$asset")
                        .bufferedReader().use { it.readText() }
                    val config: WakeWordConfig = gson.fromJson(json, object : TypeToken<WakeWordConfig>() {}.type)
                    val id = asset.removeSuffix(".json")
                    wakeWords[id] = WakeWord(config.wake_word, config.model, true)
                }.onFailure {
                    log.d("Error loading wake word manifest: $asset - ${it.message}")
                }
            }
        } catch (e: Exception) {
            log.d("Error scanning wake word assets: ${e.message}")
        }

        return wakeWords
    }

    fun getCustomWakeWords(path: String): Map<String, WakeWord> {
        val customWakeWords = mutableMapOf<String, WakeWord>()
        val downloadPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val vacaDownloadDir = Path(downloadPath.toString(), path)
        val vacaFilesDir = Path(context.filesDir.toString(), path)
        val gson = Gson()

        // Scan for .tflite + .json pairs (new microWakeWord format)
        for (dir in listOf(vacaDownloadDir, vacaFilesDir)) {
            if (!dir.isDirectory()) continue

            dir.forEachDirectoryEntry("*.json") { jsonEntry ->
                runCatching {
                    val json = jsonEntry.toFile().readText()
                    val config: WakeWordConfig = gson.fromJson(json, object : TypeToken<WakeWordConfig>() {}.type)
                    val tfliteFile = File(jsonEntry.parent.toFile(), config.model)

                    if (tfliteFile.exists()) {
                        val key = jsonEntry.toFile().nameWithoutExtension.lowercase()
                        customWakeWords[key] = WakeWord(
                            config.wake_word,
                            tfliteFile.absolutePath,
                            false
                        )
                        log.d("Found custom microWakeWord: $key at ${tfliteFile.absolutePath}")
                    }
                }.onFailure {
                    log.d("Error loading custom wake word: ${jsonEntry.fileName} - ${it.message}")
                }
            }

            // Also support legacy .onnx custom models for backward compatibility
            dir.forEachDirectoryEntry("*.onnx") { entry ->
                val key = entry.fileName.toString().replace(".onnx", "").lowercase()
                if (key !in customWakeWords) {
                    val name = key.replace("_", " ")
                    customWakeWords[key] = WakeWord(name.capitalizeWords(), entry.absolutePathString(), false)
                    log.d("Found legacy custom wake word: $key")
                }
            }
        }

        return customWakeWords
    }

    fun String.capitalizeWords(delimiter: String = " ") =
        split(delimiter).joinToString(delimiter) { word ->
            val smallCaseWord = word.lowercase()
            smallCaseWord.replaceFirstChar(Char::titlecaseChar)
        }

    fun getWakeWords(): Map<String, WakeWord> {
        return mutableMapOf<String, WakeWord>().apply {
            putAll(getBuiltInWakeWords())
            putAll(getCustomWakeWords("vaca"))
        }
    }
}