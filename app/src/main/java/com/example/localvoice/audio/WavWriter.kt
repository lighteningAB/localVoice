package com.example.localvoice.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Streams 16-bit PCM samples into a WAV file. Reserves the 44-byte header up front,
 * appends raw little-endian samples, then rewrites the header on close with the final
 * payload size so the file is playable as standard PCM WAV.
 */
class WavWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int,
) : AutoCloseable {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytesWritten = 0L

    init {
        raf.setLength(0)
        raf.write(ByteArray(HEADER_SIZE))
    }

    fun write(buffer: ShortArray, lengthShorts: Int) {
        val bytes = ByteArray(lengthShorts * 2)
        for (i in 0 until lengthShorts) {
            val s = buffer[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = ((s ushr 8) and 0xFF).toByte()
        }
        raf.write(bytes)
        dataBytesWritten += bytes.size
    }

    override fun close() {
        raf.seek(0)
        raf.write(buildHeader(dataBytesWritten.toInt()))
        raf.close()
    }

    private fun buildHeader(dataSize: Int): ByteArray {
        val byteRate = sampleRate * channels * BITS_PER_SAMPLE / 8
        val blockAlign = channels * BITS_PER_SAMPLE / 8
        val totalSize = HEADER_SIZE + dataSize

        return ByteArray(HEADER_SIZE).apply {
            // RIFF chunk
            write(0, "RIFF")
            writeIntLE(4, totalSize - 8)
            write(8, "WAVE")
            // fmt sub-chunk
            write(12, "fmt ")
            writeIntLE(16, 16)               // PCM fmt chunk size
            writeShortLE(20, 1)              // PCM = 1
            writeShortLE(22, channels)
            writeIntLE(24, sampleRate)
            writeIntLE(28, byteRate)
            writeShortLE(32, blockAlign)
            writeShortLE(34, BITS_PER_SAMPLE)
            // data sub-chunk
            write(36, "data")
            writeIntLE(40, dataSize)
        }
    }

    private fun ByteArray.write(offset: Int, ascii: String) {
        for (i in ascii.indices) this[offset + i] = ascii[i].code.toByte()
    }

    private fun ByteArray.writeIntLE(offset: Int, v: Int) {
        this[offset] = (v and 0xFF).toByte()
        this[offset + 1] = ((v ushr 8) and 0xFF).toByte()
        this[offset + 2] = ((v ushr 16) and 0xFF).toByte()
        this[offset + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun ByteArray.writeShortLE(offset: Int, v: Int) {
        this[offset] = (v and 0xFF).toByte()
        this[offset + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    companion object {
        private const val HEADER_SIZE = 44
        private const val BITS_PER_SAMPLE = 16
    }
}
