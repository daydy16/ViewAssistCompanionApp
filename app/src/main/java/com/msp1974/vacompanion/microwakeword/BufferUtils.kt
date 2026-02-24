package com.msp1974.vacompanion.microwakeword

import java.nio.ByteBuffer

/**
 * ByteBuffer extension to fill this buffer from a source buffer.
 * Transfers as many bytes as possible without exceeding either buffer's capacity.
 */
fun ByteBuffer.fillFrom(src: ByteBuffer): Int {
    val remaining = remaining()
    if (remaining == 0)
        return 0

    val srcRemaining = src.remaining()
    if (srcRemaining <= remaining) {
        put(src)
        return srcRemaining
    } else {
        val currentLimit = src.limit()
        src.limit(src.position() + remaining)
        put(src)
        src.limit(currentLimit)
        return remaining
    }
}
