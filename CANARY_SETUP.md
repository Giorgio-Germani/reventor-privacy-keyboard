# Canary Voice Input

This fork replaces the Whisper-based voice recognition with NVIDIA's
[Canary 180M Flash](https://huggingface.co/nvidia/canary-180m-flash) model
(via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)).

Canary supports German, English, French and Spanish and is fast enough for
on-device use. Canary cannot detect the spoken language itself, so the user
picks the language they speak in the voice input window; the choice is
remembered and passed to the recognizer directly. There is no automatic
language detection.

## Model files (not in this repository)

The model binaries are too large for GitHub and are git-ignored. Place the
following files into `voiceinput-shared/src/main/assets/` before building:

| File | Source |
|------|--------|
| `encoder.int8.onnx` (127 MB) | [sherpa-onnx canary-180m-flash int8](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2) |
| `decoder.int8.onnx` (71 MB) | same archive |
| `tokens.txt` | same archive (tracked in git, but re-downloadable) |

Quick setup:

```bash
curl -L -o canary.tar.bz2 https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2
tar -xjf canary.tar.bz2
cp sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8/{encoder.int8.onnx,decoder.int8.onnx,tokens.txt} voiceinput-shared/src/main/assets/
```

## Build

```bash
git submodule update --init --force
./gradlew assembleStable
```

The resulting APK contains the models as (uncompressed) assets; total size
is ~340 MB.

## Code layout

- `voiceinput-shared/src/main/java/org/futo/voiceinput/shared/canary/CanaryRunner.kt` — the Canary engine; takes the chosen language with every run
- `voiceinput-shared/src/main/java/org/futo/voiceinput/shared/AudioRecognizer.kt` — microphone capture, feeds recordings to Canary
- `java/src/org/futo/inputmethod/latin/uix/actions/VoiceInputAction.kt` — voice input window with the language selector
- `libs/sherpa-onnx-release.aar` moved to `extra-libs/` (tracked) so the build works without the `libs` submodule contents
