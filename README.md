# Whispr AI

Push-to-talk dictation, on-device. Speak, and corrected text lands wherever
your cursor is — no cloud account, no telemetry, nothing you say leaves the
machine.

| Platform | What it is | Status |
|---|---|---|
| **Android** (this repo) | A dictation **keyboard** (IME) + companion app: history, dictionary editor, in-app dictation. Works in any app's text field. | Built and tested: 24/24 tests pass, including all 19 shared dictionary contract vectors |
| **Windows** | Desktop push-to-talk (hold **Right Ctrl**, talk, release; text types into the focused app). Built from a renamed fork of [per-simmons/murmur-youtube](https://github.com/per-simmons/murmur-youtube). | Builds clean (0 warnings), 63/63 tests pass, published exe self-test passes |

## Credits and licensing

The Windows implementation originated from
[per-simmons/murmur-youtube](https://github.com/per-simmons/murmur-youtube)
(upstream has no license file, so its code is **not** redistributed in this
repository — this repo contains the Android app, documentation, and the
branding/integration work). The dictionary **correction contract** is shared
behaviour across all implementations and is exercised by the same test-vector
file (`test/dictionary-test-vectors.json`) that upstream's CI runs.

The Windows app's speech engine is NVIDIA
**Parakeet TDT 0.6B v2** (ONNX int8 via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)),
model weights under **CC-BY-4.0** (modified by int8 quantization + ONNX
export); sherpa-onnx is Apache-2.0. If you redistribute the model, credit
NVIDIA and link <https://creativecommons.org/licenses/by/4.0/>.

The Android app's speech recognition uses the device's Android speech service
(`SpeechRecognizer`, on-device when available).

## Repository layout

```
whispr_ai/
├── lib/
│   ├── main.dart            # Flutter UI: record, history, dictionary editor
│   └── dictionary.dart      # Dart port of the shared correction contract
├── android/app/src/main/kotlin/dev/whispr/whispr_ai/
│   ├── ImeDictationService.kt   # the dictation keyboard (IME)
│   ├── DictionaryCorrector.kt   # Kotlin port of the correction contract
│   ├── DictionaryStore.kt       # shared file storage (dictionary + history)
│   └── MainActivity.kt          # Flutter ↔ native bridge
├── test/
│   ├── dictionary_test.dart             # runs the shared contract vectors
│   └── dictionary-test-vectors.json     # copied from upstream/shared — do not drift
└── docs/USAGE.md            # full user guide (install, hotkeys, phone transfer)
```

## Build

Requirements: Flutter (Android toolchain configured), JDK 17, Android SDK 35/36.

```bash
flutter pub get
flutter analyze
flutter test                    # 24 tests, including the 19 shared vectors
flutter build apk --release     # → build/app/outputs/flutter-apk/app-release.apk
```

The dictionary file format is shared across platforms:
`hear -> write` per line for corrections, bare text for terms, `# off:`
prefix for disabled entries — the same file the desktop app edits.

## The dictionary, in one paragraph

Every speech engine mishears names. Teach Whispr AI two ways at once:
entries bias the engine before transcribing, and a deterministic correction
pass rewrites the transcript after — whole matches only (a rule for
"cloud code" never touches "Cloudflare"), longest phrase first, and glued
forms like "CloudCode" / "Cloud-Code" still match. Corrections that fired
are shown in history so you can tell the dictionary is doing anything.
