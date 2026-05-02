# localVoice

A fully on-device Android voice app — record voice memos and dictate into any text field, all with speech-to-text running locally on the phone. Inspired by Nothing's Essential Voice / Essential Space, but **everything runs locally**.

## What it does

1. **Voice memos**: record → transcribe locally with whisper.cpp → optionally clean up with a local LLM.
2. **Voice keyboard (IME)**: switch to localVoice as your keyboard from any text field, tap the mic, dictate, and the transcribed text is inserted at your cursor.

No cloud. No telemetry. Audio and transcripts live in app-private storage.

---

## Setup (for users)

### 1. Install
Sideload the APK. If Android blocks it, allow "install from unknown sources" for whichever app you sent it from (Drive, Files, etc.).

### 2. Open the app and download the model
1. Open **localVoice**. Grant microphone access when prompted.
2. The home screen shows a **model status banner**. Tap **Download** for the **Whisper model** (~190 MB, one-time). Wi-Fi recommended; takes a minute or two.
3. Optional: also download the **cleanup model** (Qwen3, ~1 GB). Used only for LLM-cleaned memos. Skip if you only want the voice keyboard.

### 3. Quick sanity check (optional)
Tap the big record button on the home screen → say a sentence → tap stop. Transcribed text should appear under the recording.

### 4. Enable the voice keyboard
1. In the app, scroll to the **Voice keyboard** card.
2. Tap **Open keyboard settings**. Android's on-screen keyboards list opens.
3. Toggle **localVoice** on. Accept Android's warning ("this keyboard can read everything you type" — it's our app, we don't log anything).

That's it. **Gboard remains your default typing keyboard.** No Gboard settings to change.

### How to dictate
1. Tap any text field. Gboard appears as usual.
2. Tap the **keyboard switcher** (icon in the bottom corner of Gboard, or in the notification shade when the keyboard is up) → pick **localVoice**.
3. A dark keyboard with a big yellow mic button appears.
4. **Tap the mic** → speak → **tap the mic again**. Text is inserted at your cursor.
5. Tap the small icon below the mic (the globe) to switch back to Gboard.

### Performance notes
- First dictation after switching to localVoice: ~5 seconds.
- Subsequent dictations in the same session: ~2–3 seconds for short utterances.
- Hard cap: 60 seconds per utterance (auto-stops).
- Works fully offline.

### Known limitations
- Switching keyboards inside a **home-screen widget's text field** can dismiss the keyboard — Android launcher quirk, not our bug (Gboard's globe has the same issue). Workaround: dictate inside a regular app.
- If transcription fails: re-open localVoice and verify the Whisper model finished downloading.

---

## Stack

| Layer | Tech |
|---|---|
| App | Kotlin · Compose · Material 3 |
| Persistence | Room · WorkManager |
| ASR | [whisper.cpp](https://github.com/ggml-org/whisper.cpp) v1.7.4 — JNI module in `app/src/main/cpp/` |
| LLM | [llama.cpp](https://github.com/ggml-org/llama.cpp) b5450 — separate `:llmnative` Gradle module |
| ASR model | `ggml-small-q5_1.bin` (~190 MB, 99 languages) — downloaded on first launch |
| LLM model | `Qwen3-1.7B-Instruct-Q4_K_M.gguf` (~1 GB, 119 languages) — downloaded on first launch |
| IME | `LocalVoiceImeService` — `InputMethodService` reusing the whisper pipeline, no DB writes |
| Min Android | API 31 (Android 12+) |
| ABI | `arm64-v8a` only |

Each native lib statically links its own copy of `ggml` to avoid a CMake target collision between whisper and llama. ARMv8.2-a + DotProduct + FP16 SIMD baseline (Snapdragon 845+).

## Build (for developers)

Requirements:

- Android Studio Ladybug+ with NDK + CMake 3.22.1
- A real arm64 Android device (emulators choke on the models)
- ~3 GB free on the device for downloaded models

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First build fetches whisper.cpp + llama.cpp source via CMake `FetchContent` — initial native build takes ~10 minutes, subsequent builds are incremental. Native code is always built `Release` (`-O3 -DNDEBUG`) even when Kotlin is debug — ggml in debug mode is 5-15× slower.

## Architecture

**Memo path** (cleanup is heavyweight, runs as background work):

`AudioRecord` (16 kHz mono PCM) → `WavWriter` → Room (`Recording`) → `TranscribeWorker` (`WhisperContext.transcribe` with full 30 s `audio_ctx` for accuracy, writes `Transcript`, captures detected language) → `CleanupWorker` (loads `LlamaContext` with Qwen3, builds a deletion-only prompt with detected language pinned + few-shot examples, writes `Cleanup`) → Compose UI re-renders via `Flow` on the DAOs.

**IME path** (lightweight, in-process, no persistence):

`LocalVoiceImeService.onCreate` → background warmup primes thread pool + GGML backend. User taps mic → `AudioRecord` (`VOICE_RECOGNITION` source) → in-memory `ShortArray` chunks → user taps stop → `WhisperContext.transcribe` with `audio_ctx` proportional to actual audio length → `currentInputConnection.commitText`. The whisper context is kept warm across utterances; calls are serialized through a Mutex.

The two paths share `WhisperContext` (one-time JNI wrapper) and `ModelManager` (download + path management). The IME never writes to Room and never invokes the cleanup pipeline — keyboard dictation wants raw text fast, not LLM-edited text.

See [`BUILD_GUIDE.md`](./BUILD_GUIDE.md) for the full phased build plan and design rationale.

## Status

- [x] Phase 1 — Audio capture, Room persistence, playback
- [x] Phase 2 — whisper.cpp JNI integration with multilingual model
- [x] Phase 3 — Qwen3-1.7B cleanup via llama.cpp JNI
- [x] Phase 4 — Voice keyboard (IME) + `audio_ctx` performance fix + encoder warmup
- [ ] Phase 5 — IME polish (haptics, theme awareness, smaller model option for lower latency)
- [ ] Phase 6 — Release prep

## License

[MIT](./LICENSE) for this codebase.

Models and their own licenses are downloaded at first launch:

- `ggml-small-q5_1.bin` — Whisper, [MIT](https://github.com/openai/whisper/blob/main/LICENSE)
- `Qwen3-1.7B-Instruct-Q4_K_M.gguf` — Qwen3, [Apache 2.0](https://huggingface.co/Qwen/Qwen3-1.7B/blob/main/LICENSE)

The native runtimes (`whisper.cpp`, `llama.cpp`, `ggml`) are MIT-licensed and fetched at build time.
