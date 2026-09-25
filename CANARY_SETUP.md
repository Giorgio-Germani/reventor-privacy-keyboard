# Canary Voice Input

This fork replaces the Whisper-based voice recognition with NVIDIA's
[Canary 180M Flash](https://huggingface.co/nvidia/canary-180m-flash) model
(via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)).

Canary supports German, English, French and Spanish and is fast enough for
on-device use. Canary itself cannot detect the spoken language, so a small
multilingual Whisper-tiny model runs on the first seconds of audio to detect
the language (de/en/fr/es) before transcription. The keyboard language
setting does not need to match the spoken language.

## Model files (not in this repository)

The model binaries are too large for GitHub and are git-ignored. Place the
following files into `voiceinput-shared/src/main/assets/` before building:

| File | Source |
|------|--------|
| `encoder.int8.onnx` (127 MB) | [sherpa-onnx canary-180m-flash int8](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2) |
| `decoder.int8.onnx` (71 MB) | same archive |
| `tokens.txt` | same archive (tracked in git, but re-downloadable) |
| `ggml-tiny-q8_0.bin` (42 MB) | [whisper.cpp ggml-tiny-q8_0](https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q8_0.bin) |

Quick setup:

```bash
curl -L -o canary.tar.bz2 https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2
tar -xjf canary.tar.bz2
cp sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8/{encoder.int8.onnx,decoder.int8.onnx,tokens.txt} voiceinput-shared/src/main/assets/
curl -L -o voiceinput-shared/src/main/assets/ggml-tiny-q8_0.bin https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q8_0.bin
```

## Build

```bash
git submodule update --init --force
./gradlew assembleStable
```

The resulting APK contains the models as (uncompressed) assets; total size
is ~380 MB.

## Code layout

- `voiceinput-shared/src/main/java/org/futo/voiceinput/shared/canary/CanaryRunner.kt` — the Canary engine + language identification
- `voiceinput-shared/src/main/java/org/futo/voiceinput/shared/AudioRecognizer.kt` — routes to Canary when all enabled languages are supported, falls back to Whisper
- `libs/sherpa-onnx-release.aar` moved to `extra-libs/` (tracked) so the build works without the `libs` submodule contents
