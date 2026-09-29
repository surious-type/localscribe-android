# Task F1 — real benchmark and licensed quality demo adapters

Role worker. Own app/benchmark/ and its unit/instrumentation tests only. Read core BenchmarkPorts/models, docs/model-system.md, audio-assets.md, contracts.md and C/E constructor reports. No agents or git mutations; no build/core interface edits without lead.

Implement AssetDemoAudioRepository reading assets/demo/manifest.json fixtures only. Load reference text, validate WAV checksum/format/duration, return mono16k PCM. Missing Russian/mixed assets remain unavailable. Read legal metadata from fixture manifest, not hardcoded success. No synthesizing recognition from reference.

Implement AndroidBenchmarkManager using injected engine factory, data.BenchmarkStore, DemoAudioRepository, shared execution Mutex, monotonic clock and memory/thermal sampler. Model load/inference/unload occurs under SAME Mutex as transcription and deletion; load/unload excluded from inference timing but document warm measurement. Persist device/model/hash/config/sample identity with timing, threads, thermal and approximate native+Java peak memory sampled during work. Ensure cancellation propagates and unload runs even on errors. Validate resource constraints and all expected statuses.

Core BenchmarkRecord includes sampleId with default "unspecified"; Room schema v2 persists it. Set the actual fixture ID for every new record and never silently compare different audio/configuration. Actual recognized text must be exposed through a quality demo result API to F2, alongside reference, QualityMetrics score and aligned token differences. Run same fixed sample across selected installed models sequentially; never auto-download. Android hardware profile uses ActivityManager/Build/available cores and stable non-sensitive device profile ID (no advertising/installation identifier uploads).

Benchmarks remain foreground UI operations in F2 initially: cancellation when activity stops is acceptable if explicitly handled. Don't start background inference outside a foreground service. Provide Flow run state with progress/model/error and cancellation. Engine receives configured VAD only if model available and verified.

Test metric duration boundaries, no reuse across model/hash/config/sample, cancellation/unload, fixture checksum/reference and quality difference. Use real fixture decoding test; inference smoke can reuse E external tiny model when feasible. Save docs/reports/F1-benchmark.md with exact constructors/results; no fabricated timing claims.
