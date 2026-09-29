# Task E — audio and whisper.cpp JNI report

Date: 2026-09-19

## Delivered adapters

- `AudioSourceStore` is the persistence seam implemented by Task C. Its methods are
  `observe`, `get`, `upsert`, and `remove`, all using `AudioSourceRecord`.
- `AndroidAudioRepository(context, store, ioDispatcher, maximumPrivateImportBytes)` lists
  MediaStore audio, imports SAF and shared URIs, preserves persistable read grants, and streams
  transient sources into app-private storage only when no durable URI is available. Private
  imports default to a 2 GiB cap and delete incomplete output on failure.
- `AndroidAudioPipeline(context, ioDispatcher, maximumWindowDurationMs)` seeks to the previous
  sync sample with MediaExtractor, decodes with MediaCodec, discards samples before the requested
  half-open window, finishes and resets streaming conversion when the decoder's sample rate or
  channel count changes, accepts PCM 16-bit and float encoding changes, mixes channels, resamples
  incrementally to mono 16 kHz, and applies a clipping-safe peak gain. The default window cap is
  120 seconds and the final output is capped by the requested 16 kHz frame count.
- `WhisperTranscriptionEngine(ioDispatcher, bridge, maximumPromptCharacters)` validates the actual
  SHA-256 and size of transcription and optional VAD model files before native load, owns one
  serialized native context, bounds prompts to 1,000 characters by default, passes per-window
  language/thread/translation/temperature/VAD settings, and aborts native work when either the
  coroutine or supplied cancellation signal is cancelled. Engine segment timestamps are relative
  to the supplied PCM window in milliseconds; `TranscriptionCoordinator` is responsible for the
  single conversion to absolute source time.

`AudioSourceException` and `WhisperEngineException` expose stable `DomainFailure` codes while their
diagnostics omit source paths and transcript content. whisper.cpp logging is disabled at the JNI
boundary.

## Bounded review fixes

The 2026-09-19 Task E review findings were addressed with focused regressions:

- A supplied `CancellationSignal` is checked again after JNI returns and before any transcript is
  constructed. A successful native result is discarded if cancellation arrived after the final
  native poll. Coroutine cancellation remains unchanged.
- JNI now throws `NativeBridgeException` with stable integer error codes rather than classifying
  platform exception text. Kotlin maps native allocation failure to `INSUFFICIENT_MEMORY`, model
  parse/load rejection to `MODEL_CORRUPTED`, native cancellation to `CancellationException`, and
  all other native failures to `NATIVE_FAILURE`.
- The native context is held by `unique_ptr` until it has transferred into the shared registry
  handle. Allocation or registry insertion exceptions therefore release a successfully parsed
  whisper context.
- `StreamingPcmAccumulator`, the conversion seam used by `AndroidAudioPipeline`, finishes the
  prior resampler and starts a new one at a genuine sample-rate/channel transition. Encoding-only
  changes use the new buffer encoding without resetting resampling state. Accumulated output stays
  capped to the requested 16 kHz window.

The pre-fix cancellation regression failed at its assertion because the engine returned success.
The typed-error and format-transition tests initially failed compilation because their requested
APIs did not exist. After implementation, the focused suite passed 11/11: six engine tests and five
streaming-audio tests, with zero failures, errors, or skips. Fresh native builds passed for both
`x86_64` and `arm64-v8a`; `javap` confirmed the JNI exception constructor descriptor `(I)V`, and
fresh `llvm-readelf -l` output retained `0x4000` alignment for every `LOAD` segment in both ABIs.
The 70-minute software-emulator inference smoke was intentionally not repeated; its earlier passing
result remains the end-to-end JNI evidence below.

## Native lifecycle and dependency pin

The JNI registry assigns opaque integer handles backed by shared ownership. Context construction
uses RAII through registry insertion. Transcription holds a
per-context lifecycle mutex. Abort only sets an atomic flag, and unload removes the registry entry,
sets the flag, then waits for active inference before freeing the context. A stale concurrent abort
therefore cannot dereference freed memory.

The build uses NDK `30.0.16248370`, CMake `4.1.2`, C++17, and static whisper.cpp/ggml code inside the
single `liblocalscribe_whisper.so`. The app builds `arm64-v8a` and `x86_64`. Linker maximum and
common page sizes are 16 KiB; `llvm-readelf -l` reported `LOAD` alignment `0x4000` for both ABIs.

- Upstream: `https://github.com/ggml-org/whisper.cpp`
- Release: `v1.9.4`
- Annotated tag object: `7d75b14994ae7f59623e2471445e2355fe506ed2`
- Resolved commit: `927cfce34f31707e17f2bff35c349632fb9e2c3a`
- Source archive SHA-256: `41b664fee09e79176ac277b5237debec34f8d74af3c7d71f333f1ec67989ecde`
- Fetch URL: `https://github.com/ggml-org/whisper.cpp/archive/927cfce34f31707e17f2bff35c349632fb9e2c3a.tar.gz`

FetchContent verifies the archive SHA before extraction and never follows a floating branch. The
upstream MIT notice is packaged verbatim at
`app/src/main/assets/licenses/whisper.cpp-LICENSE.txt`. No model weights are included in the APK.

The optional VAD path uses the separately installed and verified model supplied to
`loadModel`. A transcription that requests VAD without a loaded VAD model, or with a different
verified hash, fails explicitly. Catalog/download ownership remains with Task D.

## Verification evidence

- `:app:buildCMakeDebug[x86_64]`: passed.
- `:app:buildCMakeDebug[arm64-v8a]`: passed.
- `llvm-readelf -l` on both native outputs: all `LOAD` segments aligned to `0x4000`.
- Focused JVM suite: 11/11 passed (`StreamingAudioProcessorTest` 5/5 and
  `WhisperTranscriptionEngineTest` 6/6), with zero failures/errors/skips. The review-fix run used
  `-Pkotlin.incremental=false --no-build-cache` and completed successfully.
- `:app:ktlintCheck`: passed.
- Generated stereo 48 kHz PCM16 WAV instrumentation: passed on API 35 x86_64. The test decoded
  `[250 ms, 750 ms)` to exactly 8,000 mono 16 kHz samples and checked bounded values/reference mix.
- Optional native smoke without runner arguments: skipped with an explicit message as designed.
- Real native smoke: passed on API 35 x86_64 using an external app-private
  `ggml-tiny.bin` (77,691,713 bytes), SHA-256
  `be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21`, and one second of silent
  PCM. Instrumentation reported `OK (1 test)` after `4,248.185 s`.

The native smoke duration reflects an unaccelerated software x86_64 emulator and is not a device
performance benchmark. It verifies model load, JNI request/result construction, inference, and
unload only. No ARM64 device was available, so ARM64 verification is compile/link plus ELF layout.

The test-only `EngineTestRunner` substitutes plain `android.app.Application` for adapter tests
before the product Application is integrated. Normal builds retain `AndroidJUnitRunner`; the test
runner override is activated only with `-PtestRunner=io.github.surioustype.localscribe.audio.EngineTestRunner`.

Focused JVM verification covers stereo mixing, chunk-independent resampling and exact one-second
frame accounting, conservative normalization, real format-transition duration and output caps,
verified load-once lifecycle, missing-VAD failure, relative segment mapping, both cancellation
completion races, typed native error mapping, cancellation-triggered abort, and
unload-after-inference safety.

## Limits

- MediaCodec support depends on codecs installed on the device. A missing audio track, unsupported
  decoder, or unsupported decoded PCM encoding is returned as `UNSUPPORTED_CODEC`.
- The pipeline does not cache whole-recording PCM and does not use FFmpeg or denoise audio.
- Native process crashes cannot be converted into Kotlin failures; persisted chunk checkpoints
  bound the lost work as documented in the architecture.
- VAD is optional. No VAD model is silently downloaded or substituted by the engine.
