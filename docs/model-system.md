# Model system

Model distribution is independent of application releases. Bundled catalog metadata supplies a working baseline; a public HTTPS manifest can update that catalog without replacing the APK. A model is identified by its SHA-256, not only its display name. The default recommendation is multilingual Small, never Small.en.

## Trust and storage

The initial catalog uses `ggerganov/whisper.cpp` on Hugging Face. LFS object IDs are SHA-256 hashes. Metadata retrieved on 2026-09-17:

| File | Bytes | SHA-256 |
|---|---:|---|
| ggml-tiny.bin | 77691713 | be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21 |
| ggml-base.bin | 147951465 | 60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe |
| ggml-small.bin | 487601967 | 1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b |
| ggml-medium.bin | 1533763059 | 6c14d5adee5f86394037b4e4e8b59f1673b6cee10e3cf0b11bbdbee79c156208 |
| ggml-large-v3-turbo.bin | 1624555275 | 1fc70f774d38eb169993ac391eea357ef47c88757ef72ee5943879b7e8e2bc69 |

Source: https://huggingface.co/api/models/ggerganov/whisper.cpp/tree/main?recursive=false

Download only on explicit request. Stream into private `.part` files, bound received bytes, verify expected length and SHA-256, then atomically rename on the same filesystem before marking installed. Resume must validate Content-Range; a server returning 200 to a range request requires truncation and restart. Check cancellation while streaming and hashing. Never expose an incomplete file to the engine. Model deletion must respect active use.

No token is included in the APK. Catalog validation rejects unsafe names, non-HTTPS URLs, invalid hashes and absurd sizes. The HTTPS catalog publisher remains a trust boundary: hashes detect damaged downloads, not a compromised catalog publisher.

## Benchmark and quality

Benchmark keys include device identity, model hash and inference configuration. Store audio/processing duration, RTF, realtime multiplier, threads, thermal status and approximate memory where available. Use monotonic elapsed time. Hardware is supporting evidence; measured speed and memory headroom drive recommendations. Never automatically download a larger model.

Quality demonstrations use separately licensed fixed recordings and reference transcripts. WER/CER compare normalized recognized text with reference; they do not measure runtime. Missing legal assets must be shown honestly as unavailable, not as fabricated results.
