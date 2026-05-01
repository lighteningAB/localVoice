package com.example.localvoice.llm

class LlamaContext private constructor(private var ptr: Long) : AutoCloseable {

    fun generate(prompt: String, maxTokens: Int = 1024): String {
        check(ptr != 0L) { "LlamaContext released" }
        return nativeGenerate(ptr, prompt, maxTokens)
    }

    override fun close() {
        if (ptr != 0L) {
            nativeFree(ptr)
            ptr = 0L
        }
    }

    companion object {
        init {
            System.loadLibrary("llm_jni")
        }

        fun fromFile(modelPath: String, ctxSize: Int = 4096): LlamaContext {
            val p = nativeInit(modelPath, ctxSize)
            require(p != 0L) { "Failed to load llama model: $modelPath" }
            return LlamaContext(p)
        }

        @JvmStatic external fun nativeInit(path: String, ctxSize: Int): Long
        @JvmStatic external fun nativeGenerate(ptr: Long, prompt: String, maxTokens: Int): String
        @JvmStatic external fun nativeFree(ptr: Long)
    }
}
