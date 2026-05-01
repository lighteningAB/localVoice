package com.example.localvoice.whisper

class WhisperContext private constructor(private var ptr: Long) : AutoCloseable {

    fun transcribe(samples: FloatArray, language: String = "auto", nThreads: Int = 4): String {
        check(ptr != 0L) { "WhisperContext released" }
        return nativeTranscribe(ptr, samples, language, nThreads)
    }

    /** ISO 639-1 code of the language detected on the most recent transcribe() call, or empty. */
    fun detectedLanguage(): String {
        check(ptr != 0L) { "WhisperContext released" }
        return nativeGetDetectedLanguage(ptr)
    }

    override fun close() {
        if (ptr != 0L) {
            nativeFree(ptr)
            ptr = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }

        fun fromFile(modelPath: String): WhisperContext {
            val ptr = nativeInit(modelPath)
            require(ptr != 0L) { "Failed to load whisper model: $modelPath" }
            return WhisperContext(ptr)
        }

        @JvmStatic external fun nativeInit(path: String): Long
        @JvmStatic external fun nativeTranscribe(
            ptr: Long, samples: FloatArray, language: String, nThreads: Int,
        ): String
        @JvmStatic external fun nativeGetDetectedLanguage(ptr: Long): String
        @JvmStatic external fun nativeFree(ptr: Long)
    }
}
