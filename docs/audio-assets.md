# Bundled audio fixtures

## English demo and performance fixture

`app/src/main/assets/demo/english_jfk_37s.wav` contains **natural human speech**, John F. Kennedy's inaugural address of January 20, 1961. It is not synthesized, looped, or time-stretched. The same asset can serve the English demo and the 30–45-second performance benchmark; it is not a representative multilingual accuracy dataset.

- Duration: **37.250 seconds** (596,000 frames).
- Format: RIFF/WAVE, mono, 16,000 Hz, signed 16-bit little-endian PCM.
- Size: 1,192,044 bytes.
- SHA-256: `2d96f4537a3b87c2f813ecf7ec85fef16e9148b041913e3901700fee73862f97`.
- Machine-readable inventory: `app/src/main/assets/demo/manifest.json`.
- Reference text: adjacent `english_jfk_37s.txt`.

## Source and rights provenance

Checked September 17, 2026. The [JFK Presidential Library original item JFKWHA-001](https://www.jfklibrary.org/asset-viewer/archives/jfkwha-001), *Swearing-in Ceremony and Inaugural Address, 20 January 1961*, explicitly states “Public Domain.” Its archival creator is the U.S. Department of the Army's White House Army Signal Agency. Preferred attribution: White House Audio Collection, John F. Kennedy Presidential Library and Museum.

The actual derivation input is the [Wikimedia Commons audio derivative](https://commons.wikimedia.org/wiki/File:JFK_inaugural_address.ogg), attributed there to the JFK Library and identified as a U.S. federal government work in the public domain under 17 U.S.C. §105. This is public-domain provenance, not a claim that the recording has a CC0 license. No endorsement is implied. The complete source recordings are not bundled.

- [Input Ogg download](https://upload.wikimedia.org/wikipedia/commons/d/d5/JFK_inaugural_address.ogg), SHA-256 `a257580da6eda796ed1c703e545865005753f7cb20cc042e0db3c44de82274fc`.
- [Original archive MP3 download](https://static.jfklibrary.org/d277jo8t408p66s82yeiuy3qoli8o34k.mp3?filename=JFKWHA-001-AU_WR.mp3&odc=20260305221149-0500), downloaded for provenance, SHA-256 `4d6b9c0e9bf3c82ab48e83dfcc64d4f173454d5efc870430941ded2b6f33727c`.

## Derivation and transcript

The excerpt uses Ogg source time **00:50.250–01:27.500**, not the original MP3 timeline, which includes additional ceremonial audio. [Published caption boundaries](https://commons.wikimedia.org/wiki/TimedText:JFK_inaugural_address.ogg.en.srt) identify the two relevant sentences/paragraph parts. Reference punctuation is normalized. These boundaries and text were checked against published records, not independently auditioned or manually word-aligned; treat the text as a reviewable reference, not a certified word-error-rate gold standard.

> The world is very different now. For man holds in his mortal hands the power to abolish all forms of human poverty and all forms of human life. And yet the same revolutionary beliefs for which our forebears fought are still at issue around the globe, the belief that the rights of man come not from the generosity of the state but from the hand of God.

Reproduce with FFmpeg (the committed bytes and hash are authoritative because encoder versions can affect headers):

```sh
ffmpeg -nostdin -i JFK_inaugural_address.ogg -ss 50.25 -t 37.25 \
  -ac 1 -ar 16000 -c:a pcm_s16le -map_metadata -1 \
  -fflags +bitexact -flags:a +bitexact english_jfk_37s.wav
```

FFprobe and Python's WAV reader verified duration, channel count, sample count, encoding and hash. This verifies the fixture format, not model speed or transcription accuracy. Compute real-time factor as measured inference seconds divided by 37.25; report actual device/model/thread conditions and cold versus warm measurements.

## Missing languages and future additions

Russian and mixed Russian/English recordings are **not bundled**: no independently verified redistributable source with a matching reference was established in this pass. The manifest records both omissions explicitly. Do not display absent demos as runnable or count them as passed tests. Add a naturally spoken recording only after recording consent or checking the exact source's redistribution license; include its transcript, provenance, transformation interval, duration and SHA-256 in the manifest. Common Voice's project name alone is insufficient evidence for an arbitrary downloaded mirror.
