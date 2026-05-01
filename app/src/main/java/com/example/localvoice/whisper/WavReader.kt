package com.example.localvoice.whisper

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Reads a 16-bit mono PCM WAV file produced by [com.example.localvoice.audio.WavWriter]
 * into a normalized FloatArray (samples in [-1.0, 1.0]) suitable for whisper.cpp.
 *
 * Assumes the standard 44-byte header layout this app writes. Not a general-purpose
 * WAV decoder — won't handle stereo, 24-bit, or files with extra chunks.
 */
object WavReader {
    private const val HEADER_SIZE = 44

    fun read16kMono(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size > HEADER_SIZE) { "WAV file too short: ${file.absolutePath}" }

        val numSamples = (bytes.size - HEADER_SIZE) / 2
        val buffer = ByteBuffer
            .wrap(bytes, HEADER_SIZE, numSamples * 2)
            .order(ByteOrder.LITTLE_ENDIAN)

        val out = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            out[i] = buffer.short.toFloat() / 32768f
        }
        return out
    }
}
