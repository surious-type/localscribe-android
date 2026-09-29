# Task E bounded review — audio and whisper.cpp JNI

Date: 2026-09-19

## Verdict

**Needs changes.** The native context registry, shared ownership, lifecycle mutex, and atomic abort
flag form a sound ownership scheme; this review found no use-after-free path between transcribe,
abort, and unload. Audio windows and JNI PCM are duration-bounded, model files are hashed from
their actual bytes before load, SAF grants/private imports are handled without routine whole-file
copies, and native timestamps remain relative until the coordinator converts them once.

Three correctness/spec gaps remain. The first two affect required cancellation and actionable
error semantics. The third leaves a decoder behavior explicitly required by the Task E brief
unsupported. These should be fixed before Task E is accepted.

## Findings

### E-1 — High: supplied cancellation can race native completion and return a result

`WhisperTranscriptionEngine.transcribe` checks the supplied `CancellationSignal` before entering
native code and native callbacks poll it during inference, but after `bridge.transcribe` returns it
only calls `coroutineContext.ensureActive()`
(`app/src/main/kotlin/io/github/surioustype/localscribe/engine/WhisperTranscriptionEngine.kt:93-119`).
If the supplied signal becomes true after the final progress/abort callback and before native
return, `whisper_full` can return success and the engine returns a transcript. That violates the
frozen port requirement that both cancellation mechanisms return no partial result. The current
test covers coroutine cancellation only
(`app/src/test/kotlin/io/github/surioustype/localscribe/engine/WhisperTranscriptionEngineTest.kt:67-91`).

Add a post-native check of `cancellationSignal.isCancellationRequested()` before constructing the
result, and cover the completion race with a fake bridge that marks the external signal after its
last poll but returns a result. The coordinator currently rechecks its own control signal before
commit, which protects that caller, but the engine contract must hold independently.

### E-2 — Medium: native OOM and corrupt-model load failures lose actionable domain codes

The JNI layer translates `std::bad_alloc` into `IllegalStateException("native_out_of_memory")`
(`app/src/main/cpp/whisper_jni.cpp:226-230` and `:315-323`). A verified file that whisper.cpp cannot
parse is also thrown as `IllegalStateException("model_load_failed")`
(`app/src/main/cpp/whisper_jni.cpp:213-217`). Kotlin's `nativeBoundary` maps every such Java
exception to its caller's generic code, normally `NATIVE_FAILURE`
(`app/src/main/kotlin/io/github/surioustype/localscribe/engine/WhisperTranscriptionEngine.kt:163-173`).
Consequently native allocation failure is not reported as `INSUFFICIENT_MEMORY`, and a file whose
bytes match its recorded digest but whose model structure is invalid is not reported as
`MODEL_CORRUPTED`. The `handle == 0` model-corruption branch at lines 48-49 is effectively bypassed
because JNI throws when initialization returns null.

Use typed JNI exceptions/status results (preferred) or a tightly controlled native error enum so
Kotlin can map OOM, invalid model, cancellation, and other native failures to the required stable
`FailureCode`. Do not classify by arbitrary platform exception text. Also free the raw
`whisper_context` if `std::make_shared<EngineHandle>` fails at `whisper_jni.cpp:219`; today that
specific allocation failure leaks the successfully created context.

### E-3 — Medium: a genuine decoder output-format change is rejected

The decode loop accepts the first output format and repeated identical notifications, but throws
`UNSUPPORTED_CODEC` whenever sample rate, channel count, or PCM encoding actually changes
(`app/src/main/kotlin/io/github/surioustype/localscribe/audio/AndroidAudioPipeline.kt:108-116`).
The Task E brief explicitly requires output-format changes, channel count, PCM16, and float decoder
output to be handled. This behavior can reject otherwise decodable files when a codec reports a
new output format during the requested window.

Handle the new `PcmFormat` by safely finishing/resetting the streaming conversion at that boundary
while keeping output capped to the requested duration. Add a focused adapter test with a fake
decoder seam or another deterministic fixture that exercises an actual changed format; the current
WAV device test validates only fixed stereo PCM16 at 48 kHz.

## Reviewed areas without a blocker

- `whisper_jni.cpp:242-359` keeps a `shared_ptr` while transcribing, removes the registry entry
  before unload waits, and frees the context only while holding the lifecycle mutex. Concurrent
  abort therefore cannot dereference a freed context.
- `AndroidAudioPipeline.kt:30-143` caps windows at 120 seconds and caps accumulated output by the
  requested 16 kHz sample count. Per-codec-buffer conversion allocations remain bounded.
- `StreamingAudioProcessor.kt:8-73` maintains resampling state across codec buffers and performs
  channel averaging before resampling. The supplied focused tests cover chunk independence and
  exact one-second 48 kHz to 16 kHz accounting.
- `WhisperTranscriptionEngine.kt:134-155` checks file existence, byte count, and a freshly computed
  SHA-256 for both transcription and optional VAD files before native load.
- `AndroidAudioRepository.kt:65-107` attempts a persistable read grant, copies a transient source
  only when no durable URI is available, and releases owned grants/private copies on removal.
  Streaming private import has a size cap and deletes partial output on handled failures.
- Native timestamps are converted from whisper.cpp centiseconds to relative milliseconds at
  `whisper_jni.cpp:151-160`; the coordinator adds `chunk.startMs` once at
  `app/src/main/kotlin/io/github/surioustype/localscribe/execution/TranscriptionCoordinator.kt:168-175`.
- whisper.cpp logging is disabled at the JNI boundary, and reviewed diagnostics do not include
  transcript text or private source/model paths.

## Evidence considered

This was a bounded source review of `docs/briefs/E-audio-native.md`,
`docs/reports/E-audio-native.md`, `.review/E.diff`, the frozen ports, the audio/JNI
implementation, and focused tests. Per instruction, the expensive native smoke test was not rerun.
The implementation report records: 6 focused JVM tests passing; the generated WAV device test
passing; the real tiny-model JNI test passing after about 70 minutes on a software x86_64 emulator;
both ARM64 and x86_64 native builds passing; and both ELF outputs aligned to 16 KiB. Those prior
results are accepted as supplied evidence, not fresh verification performed by this reviewer.

Full repository review remains deferred to the later broad review.
