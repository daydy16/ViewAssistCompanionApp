package com.msp1974.vacompanion.microwakeword

import java.nio.ByteBuffer

/**
 * Wake word model configuration parsed from JSON manifest.
 */
data class WakeWordConfig(
    val type: String,
    val wake_word: String,
    val author: String,
    val website: String,
    val model: String,
    val trained_languages: Array<String>,
    val version: Int,
    val micro: MicroConfig,
)

/**
 * Micro-specific configuration for the wake word model.
 */
data class MicroConfig(
    val probability_cutoff: Float,
    val feature_step_size: Int,
    val sliding_window_size: Int,
    val tensor_arena_size: Int,
    val minimum_esphome_version: String,
)

/**
 * A wake word with its identifier.
 */
data class WakeWordWithId(val id: String, val wakeWord: WakeWordConfig)

/**
 * Interface for providing wake word models and metadata.
 */
interface WakeWordProvider {
    fun getWakeWords(): List<WakeWordWithId>
    fun loadWakeWordModel(model: String): ByteBuffer
}
