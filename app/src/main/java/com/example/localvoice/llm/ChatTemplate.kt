package com.example.localvoice.llm

/**
 * Builds chat-templated prompts for instruction-tuned models.
 * llama.cpp's tokenizer adds the BOS token (`<|begin_of_text|>`) automatically when
 * `add_special=true`, so it's intentionally absent from these strings.
 */
object ChatTemplate {

    /** Llama 3.x format. Stop on `<|eot_id|>` (covered by `llama_vocab_is_eog`). */
    fun llama32(system: String, user: String): String = buildString {
        append("<|start_header_id|>system<|end_header_id|>\n\n")
        append(system)
        append("<|eot_id|>")
        append("<|start_header_id|>user<|end_header_id|>\n\n")
        append(user)
        append("<|eot_id|>")
        append("<|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    /**
     * Qwen3 ChatML format. Append `/no_think` somewhere in the user message to skip the
     * thinking-mode trace and go straight to the answer (much faster for simple tasks).
     * Stop on `<|im_end|>` (covered by `llama_vocab_is_eog`).
     */
    fun qwen3(system: String, user: String): String = buildString {
        append("<|im_start|>system\n")
        append(system)
        append("<|im_end|>\n")
        append("<|im_start|>user\n")
        append(user)
        append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }
}
