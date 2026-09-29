# Task E bounded re-review — fix round 1

Date: 2026-09-19

## Verdict

**Accepted for the bounded Task E scope.** E-1, E-2, and E-3 from
`docs/reports/E-review.md` are closed. No direct regression was found in the fixes represented by
`.review/E-fix1.diff`.

This re-review was limited to those three findings and the code changed to address them. It is not
a broad re-review of Task E or the repository.

## Finding disposition

### E-1 — Closed: supplied cancellation race

After native transcription returns, the engine now checks coroutine cancellation and then checks
the supplied `CancellationSignal` before it constructs or returns a `TranscriptionResult`
(`app/src/main/kotlin/io/github/surioustype/localscribe/engine/WhisperTranscriptionEngine.kt:115-122`).
This closes the interval after the final native cancellation poll: a successful native result is
discarded if external cancellation arrived in that interval.

The added regression arranges exactly that ordering by making the fake bridge set external
cancellation after its final poll and before returning success
(`app/src/test/kotlin/io/github/surioustype/localscribe/engine/WhisperTranscriptionEngineTest.kt:94-115`
and `:197-204`). The reported focused suite includes this test.

### E-2 — Closed: typed native failures and context RAII

Kotlin and C++ define the same stable integer mapping: out of memory `1`, invalid model `2`,
cancelled `3`, and generic failure `4`
(`app/src/main/kotlin/io/github/surioustype/localscribe/engine/WhisperNativeBridge.kt:3-10` and
`app/src/main/cpp/whisper_jni.cpp:15-18`). JNI constructs `NativeBridgeException(int)` rather than
returning error text (`whisper_jni.cpp:45-64`), and Kotlin maps those codes to
`INSUFFICIENT_MEMORY`, `MODEL_CORRUPTED`, coroutine cancellation, or `NATIVE_FAILURE`
(`WhisperTranscriptionEngine.kt:166-186`). Unknown integer codes safely fall back to
`NATIVE_FAILURE`.

The JNI error sites now use the typed codes for allocation, model initialization, cancellation,
and generic native failure (`whisper_jni.cpp:218-255` and `:258-348`). The supplied `javap`
evidence confirms that the Kotlin exception constructor has the JNI descriptor `(I)V`. The Kotlin
regressions cover actionable load mappings and native cancellation
(`WhisperTranscriptionEngineTest.kt:117-164`).

The raw `whisper_context` is immediately wrapped in a `unique_ptr` with a `whisper_free` deleter
(`whisper_jni.cpp:20-35`, `:237-248`). If `make_shared` or registry insertion throws, either the
local unique owner or the local shared handle releases the context. Successful insertion transfers
shared ownership to the registry. Unload resets the same unique owner while holding the lifecycle
mutex (`whisper_jni.cpp:362-382`). The allocation leak identified in E-2 is therefore closed
without weakening the existing transcribe/unload ownership rules.

### E-3 — Closed: decoder output-format transition

`AndroidAudioPipeline` now forwards every output-format notification to a bounded
`StreamingPcmAccumulator` instead of rejecting a changed format
(`app/src/main/kotlin/io/github/surioustype/localscribe/audio/AndroidAudioPipeline.kt:107-134`).
The accumulator finishes the current resampler when sample rate or channel count changes, starts a
new resampler, and appends both outputs under one `maximumSamples` cap
(`app/src/main/kotlin/io/github/surioustype/localscribe/audio/StreamingAudioProcessor.kt:88-128`).
An encoding-only change does not reset resampling state; the pipeline still decodes each output
buffer using its current `PcmFormat`, so PCM16/float transitions use the new encoding while
preserving sample-rate phase.

The two added tests cover duration preservation across a 48 kHz stereo to 16 kHz mono transition
and enforcement of the total output cap across a transition
(`app/src/test/kotlin/io/github/surioustype/localscribe/audio/StreamingAudioProcessorTest.kt:49-73`).
The accumulator retains only the requested output plus one codec-buffer conversion at a time, so
the fix does not introduce an unbounded recording-length allocation.

## Evidence considered

The implementation report records fresh results of 11/11 focused JVM tests, successful native
compilation for both `x86_64` and `arm64-v8a`, the JNI exception constructor descriptor `(I)V`,
and `0x4000` alignment for every ELF `LOAD` segment in both ABIs. This reviewer inspected the
current source and `.review/E-fix1.diff`; no Gradle or native inference command was run during this
re-review. As instructed, the approximately 70-minute software-emulator inference test was not
repeated. Its earlier passing result remains prior end-to-end evidence rather than fresh evidence
for this fix round.

No findings remain open from the bounded E review. Full-system interactions remain for the later
broad review.
