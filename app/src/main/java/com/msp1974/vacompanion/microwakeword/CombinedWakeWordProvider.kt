package com.msp1974.vacompanion.microwakeword

import android.content.Context
import java.nio.ByteBuffer

/**
 * Combines built-in (asset) and custom (Downloads/vaca) wake word providers.
 * Custom wake words override built-in ones with the same ID.
 */
class CombinedWakeWordProvider(context: Context) : WakeWordProvider {
    private val assetProvider = AssetWakeWordProvider(context.assets)
    private val customProvider = CustomWakeWordProvider(context)

    override fun getWakeWords(): List<WakeWordWithId> {
        val builtIn = assetProvider.getWakeWords()
        val custom = customProvider.getWakeWords()

        // Custom wake words override built-in ones with same ID
        val merged = mutableMapOf<String, Pair<WakeWordWithId, WakeWordProvider>>()
        for (ww in builtIn) {
            merged[ww.id] = Pair(ww, assetProvider)
        }
        for (ww in custom) {
            merged[ww.id] = Pair(ww, customProvider)
        }

        providerMap = merged.mapValues { it.value.second }
        return merged.values.map { it.first }
    }

    override fun loadWakeWordModel(model: String): ByteBuffer {
        // Try custom first, then asset
        return try {
            customProvider.loadWakeWordModel(model)
        } catch (e: Exception) {
            assetProvider.loadWakeWordModel(model)
        }
    }

    private var providerMap = mapOf<String, WakeWordProvider>()
}
