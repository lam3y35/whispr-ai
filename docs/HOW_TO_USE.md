# Whispr AI — User Guide & Manual (دليل الاستخدام)

> **WisprFlow, but free, private, and on-device.**  
> Works on **Windows** and **Android**. Supports **bilingual English & Arabic**, ultra-fast transcription, automatic grammar correction, and push-to-talk with the **Control** button.

---

## Table of Contents
1. [Key Features & Improvements](#1-key-features--improvements)
2. [Windows Setup & Usage](#2-windows-setup--usage)
   - [First Run](#first-run)
   - [Touching the Control Button (Recording)](#touching-the-control-button-recording)
   - [Installing the Fast Bilingual Model (~39 MB)](#installing-the-fast-bilingual-model-39-mb)
   - [Grammar Correction & Polish](#grammar-correction--polish)
3. [Android Setup & Usage](#3-android-setup--usage)
   - [Installation](#install-the-apk)
   - [Enabling the Whispr AI Keyboard](#enable-the-keyboard-one-time)
   - [Switching Between English & Arabic](#switching-between-english--arabic)
   - [Using in Any App (WhatsApp, Notes, etc.)](#using-it-in-any-app)
   - [Enabling Offline Arabic & English Dictation](#offline-speech-recognition-on-android)
4. [Bilingual Dictionary (English & Arabic)](#4-bilingual-dictionary-english--arabic)
5. [Troubleshooting & FAQ](#5-troubleshooting--faq)

---

## 1. Key Features & Improvements

- **Touch Control Button to Record**:
  - Touch (tap) the **Control** button (Left Ctrl or Right Ctrl) to start recording immediately.
  - **Smart Dual Mode**: Tap Control once to start recording; tap again to stop and write down the text. Or hold Control while speaking and release when done.
- **Fast, Lightweight Speech Model**:
  - Replaced the large 660 MB model requirement with support for **Whisper Tiny Multilingual INT8 (~39 MB)** and **Whisper Base INT8 (~74 MB)**.
  - Loads in less than a second, transcribes short phrases in ~150 ms on CPU, and runs 100% locally on your computer.
- **Bilingual: English & Arabic**:
  - Native recognition for both English and Arabic with automatic language detection or explicit toggle.
  - Full support for Arabic punctuation (`،` `؛` `؟`) and Right-to-Left (RTL) text.
- **Automatic Grammar & Punctuation Correction**:
  - Automatically cleans speech disfluencies and filler words:
    - **English**: removes *"um"*, *"uh"*, *"er"*, *"ah"*, *"you know"*, etc.
    - **Arabic**: removes *"يعني"*, *"أمم"*, *"آآآ"*, *"إيه"*, *"هاه"*, etc.
  - Automatically removes repeated stutter words (e.g. *"the the words"* → *"the words"*, *"في في"* → *"في"*).
  - Automatically capitalizes sentences in English, detects questions (ending in `?` or `؟`), and formats spacing around punctuation.
- **100% Free & Private**: No cloud accounts, no subscriptions, no telemetry, no audio leaves your device.

---

## 2. Windows Setup & Usage

### First Run
1. Download or locate `WhisprAI.exe`.
2. Double-click `WhisprAI.exe`. If Windows SmartScreen appears (*"Windows protected your PC"*), click **More info** → **Run anyway**.
3. Whispr AI starts in your system tray (bottom-right near the clock).

### Touching the Control Button (Recording)
Whispr AI comes with **Smart Dual Mode**:
- **Touch / Tap**: Click into any text field (Notepad, Word, VS Code, Browser, WhatsApp Desktop, Slack).
  - **Touch the `Ctrl` key** (Left Ctrl or Right Ctrl) → recording starts.
  - Speak naturally in English or Arabic.
  - **Touch the `Ctrl` key again** → recording stops, grammar is corrected, and the text is typed immediately at your cursor!
- **Hold-to-Talk**: Alternatively, hold down `Ctrl`, speak, and release `Ctrl` when you finish.
- To configure keys: Click the Whispr AI tray icon → **Settings**:
  - Select **ANY CTRL** (works on either Control key, ideal for laptops), **LEFT CTRL**, or **RIGHT CTRL**.

### Installing the Fast Bilingual Model (~39 MB)
To achieve fast speech recognition for both English and Arabic without high memory usage, use the **Whisper Tiny INT8** model:

1. Create the model directory:
   ```powershell
   mkdir "$env:LOCALAPPDATA\WhisprAI\models\whisper-tiny"
   ```
2. Download the 3 model files into `%LOCALAPPDATA%\WhisprAI\models\whisper-tiny\`:
   - `tiny-encoder.int8.onnx` (~15 MB)
   - `tiny-decoder.int8.onnx` (~24 MB)
   - `tiny-tokens.txt` (tokenizer tokens)
   
   *(You can download the pre-quantized multilingual Whisper INT8 bundle directly from the [sherpa-onnx release models repository](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) under `sherpa-onnx-whisper-tiny`).*
3. Open Whispr AI **Settings** → **Model**: you will see **"Whisper (Bilingual) ready"** with a green status light.

### Grammar Correction & Polish
In **Settings** → **Behaviour**:
- Check **"Correct grammar, format punctuation & clean fillers"** (enabled by default).
- When active:
  - Speaking *"um I went to the store and uh bought milk"* outputs:  
    `"I went to the store and bought milk."`
  - Speaking *"how do you make this project run faster"* outputs:  
    `"How do you make this project run faster?"`
  - Speaking *"مرحبا يعني كيف حالك اليوم"* outputs:  
    `"مرحبا، كيف حالك اليوم؟"`

---

## 3. Android Setup & Usage

The Android version provides both a standalone dictation app and an **on-screen dictation keyboard (IME)** that allows you to dictate directly into WhatsApp, Telegram, Gmail, Samsung Notes, or any app.

### Install the APK
1. Transfer `WhisprAI-android.apk` (or `app-release.apk`) to your phone via USB, Google Drive, or messaging.
2. Open your phone's **Files** or **Downloads** app and tap the APK.
3. If prompted with *"Install unknown apps"*, tap **Settings** → allow installation for that file manager → tap **Install**.

### Enable the Keyboard (One-time)
1. Open the **Whispr AI** app from your home screen.
2. Allow the **Microphone** permission when requested.
3. Tap **"Enable the Whispr AI keyboard"** → enable **Whispr AI Keyboard** in the system list.

### Switching Between English & Arabic
On the Whispr AI Keyboard and in the app:
- Tap the **[AUTO / EN / عربي]** toggle button in the top bar to choose your preferred language:
  - **`EN`**: Forces English speech recognition.
  - **`عربي`**: Forces Arabic speech recognition.
  - **`AUTO`**: Detects automatically based on your voice.

### Using it in Any App
1. Open WhatsApp, Telegram, Notes, or any app and tap a text field.
2. Switch to the Whispr AI keyboard (tap the keyboard/globe icon in the bottom navigation bar or spacebar).
3. Tap the red **● TAP TO DICTATE** button.
4. Speak in English or Arabic:
   - Your speech appears in real-time in the preview window with Arabic RTL alignment or English formatting.
   - Fillers (*"um"*, *"يعني"*) and stutters (*"the the"*) are automatically stripped.
   - Punctuation (`.`, `?`, `،`, `؟`) is corrected.
5. Tap **Insert text** — the text is committed right into your chat or document!

### Offline Speech Recognition on Android
Android phones include Google's on-device speech recognizer. To use it without internet:
1. Go to phone **Settings** → **System** → **Languages & input** (or **Gboard** settings).
2. Tap **Voice typing** → **Offline speech recognition** (or **Downloaded languages**).
3. Download **English** and **Arabic (العربية)**.
4. Whispr AI will now transcribe English and Arabic completely offline on your device!

---

## 4. Bilingual Dictionary (English & Arabic)

Teach Whispr AI how to spell your favorite names, technical terms, or brand names.

Open the **Dictionary** tab in Windows or Android:
- **Correction rule** (`hear -> write`):
  - `cloud code -> Claude Code`
  - `فيسبوك -> Facebook`
  - `شات جي بي تي -> ChatGPT`
  - `الذكاء الاصطناعي -> AI`
- **Term**: Single words or phrases the speech engine should prioritize.
- File location:
  - Windows: `%LOCALAPPDATA%\WhisprAI\dictionary.txt`
  - Android: Exportable from the app's Dictionary screen.

---

## 5. Troubleshooting & FAQ

| Problem | Cause | Solution |
|---|---|---|
| **Windows: Nothing happens when pressing Ctrl** | Trigger key mismatch | Open Settings → set Push-to-Talk key to **ANY CTRL** or **LEFT CTRL**. |
| **Windows: Recorded audio is digital silence** | Mic permission disabled | Windows Settings → **Privacy & security** → **Microphone** → Turn ON *"Let desktop apps access your microphone"*. |
| **Windows: Speech model not installed** | Missing model files | Place the 3 Whisper files in `%LOCALAPPDATA%\WhisprAI\models\whisper-tiny\`. |
| **Android: "Microphone permission missing"** | Permission not granted | Open the Whispr AI app once, allow the microphone prompt, then return to the keyboard. |
| **Android: Dictation requires internet** | Offline voice pack not installed | In phone Settings → Gboard / Voice typing → Download offline English & Arabic speech packs. |
| **Arabic text appears with English punctuation** | Language mode set to English | Tap the `[EN / عربي]` button on the keyboard to switch to `عربي` or `AUTO`. |

---

## Summary Checklist

- [x] **Windows Push-to-Talk**: Touch the Control button (Left or Right Ctrl) to start recording fast.
- [x] **Smart Dual Mode**: Tap to toggle recording ON/OFF, or hold to talk.
- [x] **Lightweight Model**: Whisper INT8 (~39 MB), fast and compact.
- [x] **Bilingual Support**: English & Arabic with automatic language detection and RTL support.
- [x] **Grammar Correction**: Automatically cleans fillers (*"um"*, *"يعني"*), fixes stutters (*"the the"*), capitalizes, and formats punctuation (`.`, `?`, `،`, `؟`).
- [x] **Android Version**: Native APK + on-screen dictation keyboard with 1-tap Arabic/English toggle.
