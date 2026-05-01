# localVoice — Build Guide

A hands-on roadmap for building an on-device voice-memo app (Nothing's Essential Voice clone)
that uses **whisper.cpp** for transcription and **gemma-2-2b-it** for cleanup/summary.

This guide is written so you build it yourself: each phase lists the *concept* to learn,
the *steps* to take, and a *verify* checkpoint. Code snippets are provided only where
they're small and load-bearing — the bulk you'll write yourself.

---

## 0. What you're building

```
                ┌──────────────────────┐
   tap record   │ RecordingService     │  16 kHz mono PCM → .wav
   ───────────► │ (foreground svc)     │ ─────────────────────────┐
                └──────────────────────┘                          │
                                                                  ▼
                ┌──────────────────────┐         ┌────────────────────────┐
                │ TranscribeWorker     │ ◄────── │ filesDir/recordings/   │
                │  whisper.cpp (JNI)   │         └────────────────────────┘
                └──────────────────────┘
                          │ raw transcript + segment timestamps
                          ▼
                ┌──────────────────────┐
                │ CleanupWorker        │  MediaPipe LLM Inference
                │  gemma-2-2b-it       │  → {title, summary, actions, cleaned}
                └──────────────────────┘
                          │
                          ▼
                ┌──────────────────────┐
                │ Room DB              │  Recording / Transcript / Summary
                └──────────────────────┘
                          │
                          ▼
                ┌──────────────────────┐
                │ Compose UI           │  list, detail, playback, search
                └──────────────────────┘
```

Both models run **fully on-device**. Nothing leaves the phone.

---

## 1. Prerequisites & one-time setup

You'll need:

- Android Studio (Ladybug or newer)
- **Android NDK** (Tools → SDK Manager → SDK Tools → check "NDK (Side by side)" + "CMake")
  - whisper.cpp is C/C++; you compile it as a native lib
- A real **arm64** test device. Emulators choke on these models. A Nothing phone is fine.
- ~3 GB free on the test device for models during development
- A Hugging Face account (for both whisper GGML weights and the Gemma 4 GGUF). No Kaggle license consent needed — Gemma 4 ships under Apache 2.0.

Sanity check the project builds as-is:

```bash
cd /Users/patrickfan/Documents/localVoice
./gradlew assembleDebug
```

If that fails, fix it before going further. The current scaffold is plain Compose + Kotlin + minSdk 31, no native code yet.

---

## 2. Architectural decisions to lock in (Phase 0)

Decide these *before* writing code. Each has trade-offs that compound later.

### 2.1 Locked decisions for this build

| Decision | **Locked pick** | Notes |
|---|---|---|
| Languages supported | **Multilingual** | Whisper auto-detects; default UI language follows phone locale |
| ASR runtime | **whisper.cpp via JNI** | Vendored from upstream `examples/whisper.android` |
| ASR model | **`ggml-large-v3-turbo-q5_0.bin`** (~570 MB) | Multilingual, ~8× faster than `large-v3` at near-identical accuracy. Source: `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin` |
| LLM runtime | **llama.cpp via JNI** | Apache 2.0 weights + fastest community GGUF turnaround for new Gemma releases. Revisit MediaPipe LLM Inference at Phase 3 if Google ships an official Gemma 4 `.task` bundle |
| LLM model | **`gemma-4-E4B-it`** Q4_K_M GGUF (~4–5 GB) | 4.5B effective / 8B total via Per-Layer Embeddings, Apache 2.0, 128 K context, 35+ instruction-tuned languages. Find a community quant under `huggingface.co/<bartowski|unsloth|lmstudio-community>/gemma-4-E4B-it-GGUF` |
| Min RAM target | **8 GB** | Test device class: Nothing Phone (2)/(3). On 6 GB devices, drop to a Q3_K_M quant or fall back to `gemma-3-1b-it` |
| Model delivery | **First-launch download**, Wi-Fi-only by default, resumable, SHA-256 verified | Total cold install footprint: ~5–6 GB |
| ABI | **`arm64-v8a` only** | All Nothing phones are arm64; halves APK size |
| Persistence | **Room** + WorkManager for background jobs | Standard |
| UI | **Compose**, single-Activity | Already wired up |

> **Multilingual implication:** since the whisper model has no `.en` suffix, let it auto-detect language per recording (adds ~50 ms) or expose a language picker in settings.

> **License upside:** Gemma 4 ships under Apache 2.0. No Kaggle gate, no consent UI required — download the GGUF straight from Hugging Face on first launch.

> **Gemma 4 also has built-in audio (ASR + speech translation).** We're still using whisper.cpp because it's specialized, faster for pure transcription, and decouples ASR from the LLM (so each can be swapped independently). Worth A/B-ing against Gemma's native ASR in Phase 5+.

---

## 3. Phase 1 — Audio capture & storage

**Goal:** Press record, get a `.wav` file in app storage, see it in a list, play it back.
No ML yet.

### Concepts to learn first
- Android runtime permissions (`RECORD_AUDIO`, `POST_NOTIFICATIONS`)
- Foreground services with type `microphone` (mandatory on Android 14+)
- `AudioRecord` raw PCM capture (vs `MediaRecorder`, which encodes — you want raw)
- WAV file structure: 44-byte header + interleaved PCM
- Room basics (entity, DAO, database)

### Steps

1. **Manifest permissions** (`app/src/main/AndroidManifest.xml`):
   ```xml
   <uses-permission android:name="android.permission.RECORD_AUDIO"/>
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE"/>
   <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
   <uses-permission android:name="android.permission.INTERNET"/>
   ```
   And declare the service inside `<application>`:
   ```xml
   <service
       android:name=".audio.RecordingService"
       android:foregroundServiceType="microphone"
       android:exported="false"/>
   ```

2. **Add deps** to `app/build.gradle.kts` (and `gradle/libs.versions.toml` aliases):
   - `androidx.room:room-runtime`, `room-ktx`, `room-compiler` (KSP)
   - `androidx.work:work-runtime-ktx`
   - `androidx.lifecycle:lifecycle-service`
   - Apply `com.google.devtools.ksp` plugin

3. **Build `RecordingService`**:
   - Start it as foreground with a notification.
   - Inside, `AudioRecord(MIC, 16000, MONO, PCM_16BIT, bufSize)`.
   - In a background thread loop, read short[] buffers and write them to a `FileOutputStream` opened on `filesDir/recordings/<uuid>.wav`.
   - Reserve 44 bytes at the start; on stop, seek back and write the WAV header with the final byte count.
   - Broadcast / Flow the recording id when finished.

4. **Room schema** (`data/db/`):
   ```kotlin
   @Entity data class Recording(
     @PrimaryKey val id: String,
     val filePath: String,
     val durationMs: Long,
     val createdAt: Long,
     val status: String  // RECORDED, TRANSCRIBING, CLEANING, READY, FAILED
   )
   ```

5. **Compose UI**:
   - Single screen with a big record/stop FAB.
   - Below it, a `LazyColumn` of recordings. Tap → simple `MediaPlayer`-based playback.
   - Use a `ViewModel` with `StateFlow<UiState>`; observe Room via `Flow`.

### Verify
- Record 30 s, stop, see a row appear.
- Tap → audio plays back clearly.
- Adb pull the .wav and open in Audacity → it's 16 kHz mono.
- Lock screen during recording → it keeps recording (foreground service works).

### Gotchas
- WAV header byte order is **little-endian**. Test with a known tool before assuming it's right.
- Don't use `MediaRecorder` — it gives you AAC/MP4, then you'd need to decode back to PCM for whisper.
- Foreground service notification is mandatory; users will see it. Make it informative ("Recording…").

---

## 4. Phase 2 — whisper.cpp integration

**Goal:** Finished recordings auto-produce a raw transcript with segment timestamps.

This is the steepest phase. Treat it as: get the upstream example working *unchanged* first, then graft it in.

### Concepts to learn first
- JNI basics: `extern "C" JNIEXPORT`, `jstring`/`jfloatArray`, `GetFloatArrayElements`
- CMake + Android NDK toolchain (`externalNativeBuild` block in Gradle)
- ABI filters and APK size impact
- WorkManager + chained workers
- 16-bit PCM ↔ float32 conversion (whisper wants `float[]` in `[-1.0, 1.0]`)

### Steps

1. **Clone whisper.cpp** somewhere outside the project:
   ```bash
   git clone https://github.com/ggerganov/whisper.cpp ~/src/whisper.cpp
   ```
   Open `examples/whisper.android` in Android Studio standalone first. **Build and run it** on your device. If you can't get the upstream example transcribing the bundled jfk.wav, don't try to integrate yet — fix that first.

2. **Vendor it into localVoice**:
   - Create `app/src/main/cpp/`. Copy `whisper.cpp/whisper.h`, `whisper.cpp`, the `ggml*` files, and the JNI wrapper from `examples/whisper.android/lib/src/main/jni/whisper/jni.c`.
   - Create `app/src/main/cpp/CMakeLists.txt` modeled on the example's. Targets `arm64-v8a` only.
   - In `app/build.gradle.kts`:
     ```kotlin
     android {
       defaultConfig {
         ndk { abiFilters += listOf("arm64-v8a") }
         externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
       }
       externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
     }
     ```

3. **Kotlin JNI wrapper** (`whisper/WhisperContext.kt`):
   ```kotlin
   class WhisperContext private constructor(private val ptr: Long) {
     fun transcribe(samples: FloatArray): String = nativeFullTranscribe(ptr, samples)
     fun release() = nativeFree(ptr)
     companion object {
       init { System.loadLibrary("whisper") }
       fun fromFile(modelPath: String) = WhisperContext(nativeInitFromPath(modelPath))
       @JvmStatic external fun nativeInitFromPath(path: String): Long
       @JvmStatic external fun nativeFullTranscribe(ctx: Long, samples: FloatArray): String
       @JvmStatic external fun nativeFree(ctx: Long)
     }
   }
   ```
   Match these symbols in your `jni.c`.

4. **Model bootstrap** (`models/ModelDownloader.kt`):
   - On first launch, if `filesDir/models/ggml-large-v3-turbo-q5_0.bin` is missing, download from `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin`.
   - Show progress in UI. Verify SHA-256 against a constant in code.
   - Use `OkHttp` with a streaming body; write to a `.part` file and rename on success.

5. **WAV → float[]** util:
   - Skip 44 bytes, read shorts, divide by 32768f.
   - Whisper supports PCM at any rate but yours is already 16 kHz so no resampling needed.

6. **`TranscribeWorker`** (WorkManager):
   - Input: recording id.
   - Load model once into a singleton `WhisperContext` (lazy; release after N idle minutes).
   - Run `transcribe()`, parse out segments (modify the JNI to also return timestamps as JSON, or call additional `whisper_full_n_segments` / `whisper_full_get_segment_*` and serialize).
   - Persist `Transcript(recordingId, rawText, segmentsJson)`. Update `Recording.status`.
   - Enqueue from `RecordingService` on stop: `WorkManager.beginUniqueWork(...)`.

### Verify
- Record "the quick brown fox jumps over the lazy dog", stop, wait. Transcript appears in the UI.
- Logcat shows whisper timing (~5-15 s per minute of audio on a modern phone).
- Kill app mid-transcription, reopen → WorkManager retries and completes.

### Gotchas
- Don't initialize whisper on the main thread — model load is hundreds of ms.
- `Runtime.availableProcessors()` overcounts on big.LITTLE; cap threads at 4–6.
- If you see "failed to load model" at runtime, it's almost always a path issue — log the absolute path and `ls -la` it via adb.
- Beam search > 1 doubles latency for marginal gain at this model size — keep it at 1.
- ProGuard will strip your JNI methods in release builds; add `-keepclasseswithmembernames class * { native <methods>; }`.

---

## 5. Phase 3 — Gemma 4 cleanup via llama.cpp

**Goal:** Each transcript gets a title, summary, action items, and cleaned-up version.

### Concepts to learn first
- llama.cpp's C API (`llama_model_load_from_file`, `llama_init_from_model`, `llama_decode`, sampler chain)
- GGUF format and quantization tradeoffs (Q4_K_M vs Q5_K_M vs Q3_K_M)
- Gemma 4 chat template: BOS + `<start_of_turn>user\n…<end_of_turn>\n<start_of_turn>model\n` (verify against the model's `tokenizer.chat_template` field — get it wrong and outputs degrade silently)
- Sampler chain basics: temperature → top-k → top-p → final
- Defensive JSON parsing (small models drift)

### Steps

1. **Pick a community GGUF**: search `huggingface.co/models?search=gemma-4-E4B-it+GGUF`. Look for `bartowski`, `unsloth`, or `lmstudio-community` — they're the trustworthy mirrors. Download `gemma-4-E4B-it-Q4_K_M.gguf` once locally to verify it loads with `llama-cli`.

2. **Vendor llama.cpp** the same way you did whisper.cpp:
   - Clone `https://github.com/ggml-org/llama.cpp`.
   - Add a second native module under `app/src/main/cpp/llama/` with its own `CMakeLists.txt`. Build target: `libllama_jni.so`.
   - llama.cpp shares ggml with whisper.cpp — be careful not to link two copies. Easiest path: keep the native modules separate and let each statically link its own ggml. APK-size cost is real (~10 MB) but the alternative is multi-day CMake archaeology.

3. **JNI surface** (`llm/LlamaContext.kt`):
   ```kotlin
   class LlamaContext private constructor(private val ptr: Long) {
     fun generate(prompt: String, maxTokens: Int = 1024): String =
       nativeGenerate(ptr, prompt, maxTokens)
     fun release() = nativeFree(ptr)
     companion object {
       init { System.loadLibrary("llama_jni") }
       fun fromFile(path: String, ctxSize: Int = 4096) =
         LlamaContext(nativeInit(path, ctxSize))
       @JvmStatic external fun nativeInit(path: String, ctxSize: Int): Long
       @JvmStatic external fun nativeGenerate(ctx: Long, prompt: String, maxTokens: Int): String
       @JvmStatic external fun nativeFree(ctx: Long)
     }
   }
   ```
   Inside `nativeGenerate`, build the Gemma 4 chat-templated prompt, tokenize, decode in a loop, sample greedy or low-temp, stop on `<end_of_turn>`.

4. **Model download**: same pattern as whisper, larger file (~4–5 GB). Wi-Fi-only toggle by default. Resumable transfer (HTTP `Range` header) is mandatory at this size — users *will* lose connection mid-download.

5. **`CleanupRepository`** wraps `LlamaContext` with prompt construction and JSON parsing:
   ```kotlin
   class CleanupRepository(modelPath: String) {
     private val llm = LlamaContext.fromFile(modelPath)
     suspend fun cleanup(raw: String): CleanupResult = withContext(Dispatchers.Default) {
       val prompt = buildGemmaChatPrompt(systemInstruction, raw)
       val response = llm.generate(prompt, maxTokens = 1024)
       parseCleanupJson(response) ?: CleanupResult.fallback(raw)
     }
     fun close() = llm.release()
   }
   ```

6. **Prompt template** (start strict, loosen if you see refusals):
   ```
   You are a transcript editor for a voice memo app.
   Given the raw transcript below, produce ONLY a JSON object with these keys:
     "title":   string, ≤8 words, no quotes
     "summary": string, ≤2 sentences
     "actions": array of strings (action items, can be empty)
     "cleaned": string, the transcript with proper punctuation,
                capitalization, and filler words removed. Do not paraphrase.

   Raw transcript:
   """
   {{RAW}}
   """

   JSON:
   ```
   Strip ```json fences from the response before parsing. If parsing fails, retry once with a stricter "Output JSON only, no prose" prefix; on second failure, fall back to raw.

7. **`CleanupWorker`**: chained after `TranscribeWorker` via WorkManager's `then()`. Persist a `Summary` row, set `Recording.status = READY`.

### Verify
- A 1-minute "let me think about my day…" memo produces a coherent title, summary, and 0–3 action items.
- Force-close app during cleanup → resumes via WorkManager.
- Toggle airplane mode at any point → no errors related to network (everything is on-device).

### Gotchas
- llama.cpp's `llama_context` is **not thread-safe**. Wrap it with a `Mutex` or use a single-thread dispatcher.
- The GGUF weights are mmap'd; loading is fast but first inference can be slow as pages fault in. Run a 1-token warmup after `nativeInit`.
- If you keep whisper-large-v3-turbo + gemma-4-E4B loaded simultaneously, expect ~6 GB RSS. **Unload whisper before invoking gemma** — chain them, don't parallelize.
- Get the Gemma 4 chat template *exactly* right — wrong BOS or wrong `<start_of_turn>` sentinels degrade output quality silently rather than failing loudly. Print `tokenizer.chat_template` from the GGUF metadata and reproduce it byte-for-byte.
- Gemma 4 E4B (4.5B effective) is more reliable at JSON than Gemma 2/3, but it still occasionally adds Markdown fences. Your parser must tolerate leading/trailing prose and ` ```json ` fences.
- Per-Layer Embeddings (PLE) means the loaded model uses more disk than parameter count suggests. Verify free space before download.
- ProGuard will strip llama JNI methods in release builds — extend the `-keepclasseswithmembernames class * { native <methods>; }` rule to cover both whisper and llama packages.

---

## 6. Phase 4 — UX polish

**Goal:** App stops feeling like a demo.

### Tasks
- Recordings list shows Gemma-generated title, not timestamp.
- Detail screen with tabs: **Cleaned** / **Raw** / **Action items**.
- Tap a word in the cleaned view → seek playback to that segment (using whisper segment timestamps you saved earlier).
- Status chips per item: Recording → Transcribing → Cleaning → Ready → Failed.
- Inline progress for model downloads on first launch.
- Empty states (no recordings, no model).
- Failure recovery UI ("retry transcription").
- Search across cleaned transcripts (Room FTS4 virtual table over `Summary.cleaned`).

### Verify
- A non-technical person can use the app without a tutorial.
- Killing the app mid-anything and reopening leaves UI consistent with DB state.

---

## 7. Phase 5 — Reliability & Essential-Voice-style integrations

### Tasks
- **Quick Settings tile** to start recording without opening the app.
- **Assistant shortcut** (App Actions / `START_VOICE_RECORDING` intent) so the Essential Key (or any user-mapped hardware button) can launch it. Note: the Essential Key on Nothing phones is just a user-configurable button; there's no private API — you ship a launchable intent and the user maps it.
- **Share sheet export** (Markdown of cleaned transcript + summary).
- **Auto-delete** setting (recordings older than N days).
- **Wipe all data** setting (privacy).
- **Model management** screen: show model versions, free disk, allow re-download / delete.
- Battery hygiene: stop foreground service immediately on stop; release model handles when idle 5+ minutes.

### Verify
- Map the Essential Key to your shortcut on a Nothing phone — single press starts recording.
- Run a 30-minute battery test recording → CPU spike during transcription, idle otherwise.

---

## 8. Phase 6 — Release prep

- Rename package from `com.example.localvoice` to a real namespace (Android Studio: Refactor → Rename Package).
- App icon (adaptive) + splash.
- ProGuard / R8 rules:
  ```proguard
  -keepclasseswithmembernames class * { native <methods>; }
  -keep class com.google.mediapipe.** { *; }
  ```
- App Bundle (`./gradlew bundleRelease`), `arm64-v8a` only.
- Privacy policy page (even for on-device-only apps, Play requires one).
- Internal track release → dogfood for a week.

---

## 9. Suggested order of attack

**Week 1**: Phase 1 end-to-end. Don't touch ML yet.
**Week 2**: Get upstream `whisper.android` example transcribing on your device, *then* port it. Most of the friction is here.
**Week 3**: Phase 3 Gemma 4 via llama.cpp JNI, chained after whisper.
**Week 4**: Phase 4 UX polish.
**Week 5+**: Phases 5–6 as you have appetite.

If a phase is taking >2x your estimate, stop and ask whether you're solving the right problem. Native build issues in particular have a way of eating days.

---

## 10. Reference / further reading

- whisper.cpp (canonical): https://github.com/ggml-org/whisper.cpp
- whisper.cpp Android example: https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android
- Whisper GGML models (incl. large-v3-turbo Q5_0): https://huggingface.co/ggerganov/whisper.cpp
- Whisper Large V3 Turbo model card: https://huggingface.co/openai/whisper-large-v3-turbo
- llama.cpp (canonical): https://github.com/ggml-org/llama.cpp
- llama.cpp Android build notes: https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md
- Gemma 4 E4B IT (Hugging Face): https://huggingface.co/google/gemma-4-E4B-it
- Community GGUFs to look for: `bartowski/gemma-4-E4B-it-GGUF`, `unsloth/gemma-4-E4B-it-GGUF`, `lmstudio-community/gemma-4-E4B-it-GGUF`
- MediaPipe LLM Inference (Android) — fallback if Google ships an official Gemma 4 .task: https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android
- AudioRecord docs: https://developer.android.com/reference/android/media/AudioRecord
- Foreground service types (Android 14+): https://developer.android.com/about/versions/14/changes/fgs-types-required
- WAV header reference: http://soundfile.sapp.org/doc/WaveFormat/

---

## Appendix A — Glossary

- **GGML / GGUF** — quantized model formats used by ggml-based runtimes (whisper.cpp, llama.cpp).
- **JNI** — Java Native Interface; the bridge between Kotlin/Java and C/C++.
- **NDK** — Native Development Kit; Android's C/C++ toolchain.
- **Quantization (Q4_K_M, Q5_1, int4)** — compressing model weights to fewer bits per parameter. Smaller, faster, slightly less accurate.
- **PPR / cache components** — Next.js terms; ignore here.
- **Foreground service** — long-running background work with a mandatory user-visible notification.
- **WorkManager** — Android's deferrable, reliable background job scheduler.
