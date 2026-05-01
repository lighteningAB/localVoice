#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct LlmCtx {
    llama_model   *model   = nullptr;
    llama_context *ctx     = nullptr;
    llama_sampler *sampler = nullptr;
    const llama_vocab *vocab = nullptr;
};

static void llama_log_cb(ggml_log_level level, const char *text, void * /*user*/) {
    int prio = ANDROID_LOG_INFO;
    if (level == GGML_LOG_LEVEL_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) prio = ANDROID_LOG_WARN;
    __android_log_write(prio, "llama.cpp", text);
}

static std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    int n = -llama_tokenize(vocab, text.c_str(), text.size(), nullptr, 0, add_special, /*parse_special*/true);
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(vocab, text.c_str(), text.size(), tokens.data(), tokens.size(), add_special, true) < 0) {
        return {};
    }
    return tokens;
}

static std::string token_to_piece(const llama_vocab *vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), /*lstrip*/0, /*special*/true);
    if (n < 0) return {};
    return std::string(buf, n);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_localvoice_llm_LlamaContext_nativeInit(
    JNIEnv *env, jclass /*clazz*/, jstring jpath, jint ctxSize) {

    llama_log_set(llama_log_cb, nullptr);
    ggml_log_set(llama_log_cb, nullptr);

    static bool backend_inited = false;
    if (!backend_inited) {
        llama_backend_init();
        backend_inited = true;
    }

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("loading model: %s (ctx=%d)", path, ctxSize);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only

    llama_model *model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);

    if (!model) {
        LOGE("llama_model_load_from_file returned null");
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx       = ctxSize > 0 ? (uint32_t)ctxSize : 4096;
    cparams.n_batch     = 512;
    cparams.n_threads   = 6;
    cparams.n_threads_batch = 6;

    llama_context *ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        LOGE("llama_init_from_model returned null");
        llama_model_free(model);
        return 0;
    }

    // Low temperature + tight top-p so the model doesn't drift into paraphrase or translate.
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.1f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto *out = new LlmCtx{};
    out->model   = model;
    out->ctx     = ctx;
    out->sampler = smpl;
    out->vocab   = llama_model_get_vocab(model);
    return reinterpret_cast<jlong>(out);
}

JNIEXPORT jstring JNICALL
Java_com_example_localvoice_llm_LlamaContext_nativeGenerate(
    JNIEnv *env, jclass /*clazz*/, jlong ptr, jstring jprompt, jint maxNewTokens) {

    auto *c = reinterpret_cast<LlmCtx *>(ptr);
    if (!c || !c->ctx) return env->NewStringUTF("");

    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    std::string promptStr(prompt);
    env->ReleaseStringUTFChars(jprompt, prompt);

    auto prompt_tokens = tokenize(c->vocab, promptStr, /*add_special*/true);
    if (prompt_tokens.empty()) {
        LOGE("tokenize produced 0 tokens");
        return env->NewStringUTF("");
    }
    LOGI("prompt: %zu tokens", prompt_tokens.size());

    // Reset KV cache for a fresh generation
    llama_kv_self_clear(c->ctx);

    // Decode the prompt
    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), (int32_t)prompt_tokens.size());
    if (llama_decode(c->ctx, batch) != 0) {
        LOGE("llama_decode (prompt) failed");
        return env->NewStringUTF("");
    }

    std::string out;
    int generated = 0;
    while (generated < maxNewTokens) {
        llama_token tok = llama_sampler_sample(c->sampler, c->ctx, -1);
        if (llama_vocab_is_eog(c->vocab, tok)) {
            break;
        }
        std::string piece = token_to_piece(c->vocab, tok);
        out += piece;

        // Gemma chat template stop: <end_of_turn>
        if (out.size() >= 13) {
            auto pos = out.rfind("<end_of_turn>");
            if (pos != std::string::npos) {
                out.erase(pos);
                break;
            }
        }

        llama_batch nb = llama_batch_get_one(&tok, 1);
        if (llama_decode(c->ctx, nb) != 0) {
            LOGE("llama_decode (token %d) failed", generated);
            break;
        }
        generated++;
    }

    LOGI("generated %d tokens, %zu bytes", generated, out.size());
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_localvoice_llm_LlamaContext_nativeFree(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong ptr) {
    auto *c = reinterpret_cast<LlmCtx *>(ptr);
    if (!c) return;
    if (c->sampler) llama_sampler_free(c->sampler);
    if (c->ctx) llama_free(c->ctx);
    if (c->model) llama_model_free(c->model);
    delete c;
}

} // extern "C"
