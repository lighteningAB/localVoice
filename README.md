# localVoice

A fully on-device Android voice-memo app — record, transcribe, and clean up speech without anything ever leaving the phone. Inspired by Nothing's Essential Voice / Essential Space, but **everything runs locally**.

## What it does

1. **Record** voice memos via a foreground service.
2. **Transcribe** locally with `whisper.cpp` (multilingual `ggml-small-q5_1`).
3. **Clean up** the transcript with `llama.cpp` running `Qwen3-1.7B-Instruct` — strips fillers (uh/um), fixes punctuation, preserves the speaker's language.
4. **Browse** recordings with raw + cleaned transcripts on each card.

No cloud. No telemetry. Audio and transcripts live in app-private storage.

## Stack

| Layer | Tech |
|---|---|
| App | Kotlin · Compose · Material 3 |
| Persistence | Room · WorkManager |
| ASR | [whisper.cpp](https://github.com/ggml-org/whisper.cpp) v1.7.4 — JNI module in `app/src/main/cpp/` |
| LLM | [llama.cpp](https://github.com/ggml-org/llama.cpp) b5450 — separate `:llmnative` Gradle module |
| ASR model | `ggml-small-q5_1.bin` (~190 MB, 99 languages) — downloaded on first launch |
| LLM model | `Qwen3-1.7B-Instruct-Q4_K_M.gguf` (~1 GB, 119 languages) — downloaded on first launch |
| Min Android | API 31 (Android 12+) |
| ABI | `arm64-v8a` only |

Each native lib statically links its own copy of `ggml` to avoid a CMake target collision between whisper and llama. ARMv8.2-a + DotProduct + FP16 SIMD baseline (Snapdragon 845+).

## Build

Requirements:

- Android Studio Ladybug+ with NDK + CMake 3.22.1
- A real arm64 Android device (emulators choke on the models)
- ~3 GB free on the device for downloaded models

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First launch fetches whisper.cpp + llama.cpp source via CMake `FetchContent` — initial native build takes ~10 minutes, subsequent builds are incremental.

After install, open the app and tap **Download** on each model banner — the whisper model is ~190 MB, the cleanup model is ~1 GB. Wi-Fi recommended.

## Architecture (one-paragraph version)

`AudioRecord` (16 kHz mono PCM) → `WavWriter` (`.wav` in `filesDir/recordings/`) → Room (`Recording`) → `TranscribeWorker` (loads `WhisperContext`, runs `whisper_full`, writes `Transcript` row, captures detected language) → `CleanupWorker` (loads `LlamaContext` with Qwen3, builds a strict deletion-only prompt with the detected language pinned and few-shot examples, writes `Cleanup` row) → Compose UI re-renders via `Flow` on the DAOs.

See [`BUILD_GUIDE.md`](./BUILD_GUIDE.md) for the full phased build plan and design rationale.

## Status

Working through a phased build:

- [x] Phase 1 — Audio capture, Room persistence, playback
- [x] Phase 2 — whisper.cpp JNI integration with multilingual model
- [x] Phase 3 — Qwen3-1.7B cleanup via llama.cpp JNI
- [ ] Phase 4 — UX polish (segment seek, title generation, search)
- [ ] Phase 5 — Quick-tile, share sheet, Essential Key intent
- [ ] Phase 6 — Release prep

## License

[MIT](./LICENSE) for this codebase.

Models and their own licenses are downloaded at first launch:

- `ggml-small-q5_1.bin` — Whisper, [MIT](https://github.com/openai/whisper/blob/main/LICENSE)
- `Qwen3-1.7B-Instruct-Q4_K_M.gguf` — Qwen3, [Apache 2.0](https://huggingface.co/Qwen/Qwen3-1.7B/blob/main/LICENSE)

The native runtimes (`whisper.cpp`, `llama.cpp`, `ggml`) are MIT-licensed and fetched at build time.
