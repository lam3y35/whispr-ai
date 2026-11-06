# Whispr AI: WhisperFlow Model & Hardware Guide

This guide explains how **Whispr AI** detects your computer's hardware (GPU vs. CPU), selects the best speech model, and provides step-by-step instructions for downloading and using the **WhisperFlow** ("fast, but good") model.

---

## 1. Model Comparison: WhisperFlow vs. Default

| Feature | Default Model (CPU Tier) | WhisperFlow Model (Best / GPU Tier) |
| :--- | :--- | :--- |
| **Model Family** | OpenAI Whisper Tiny / Base (INT8) | OpenAI Whisper Small / Flow (INT8) |
| **Model Size** | **~39 MB** (tiny) / **~74 MB** (base) | **~150 MB – 240 MB** |
| **Accuracy** | Good for everyday dictation | **High** — handles complex vocabulary, accents & jargon |
| **Speed / Latency** | Ultra-fast (< 0.1s latency on CPU) | Fast (< 0.15s latency on GPU with DirectML/CUDA) |
| **Languages** | Bilingual (English & Arabic) | Bilingual (English & Arabic) with enhanced context |
| **Recommended Device** | **CPU-only**, laptops, battery-saving | **GPU systems** (NVIDIA, AMD Radeon, Intel Arc/Iris) |
| **Folder Name** | `models/whisper-tiny/` or `whisper-base/` | `models/whisper-flow/` or `whisper-small/` |

---

## 2. Automatic Hardware Detection (GPU vs. CPU)

Whispr AI automatically inspects your device hardware on startup:
* **Processor (CPU)**: Logical core count and system architecture (`X64` or `Arm64`).
* **System Memory**: Total installed RAM.
* **Graphics Adapter (GPU)**: Detects NVIDIA GeForce/RTX, AMD Radeon, and Intel Arc/Iris Xe display controllers via Win32 DXGI.

### Selection Logic:
* **GPU Detected**: The app automatically selects the **WhisperFlow Model** (`whisper-flow` or `whisper-small`) and enables hardware acceleration (`DirectML` or `CUDA`).
* **CPU Only**: The app automatically selects the **Default Model** (`whisper-tiny` or `whisper-base`) for instant transcription without consuming excessive CPU or battery.
* **Manual Override**: Open **Settings** (`Ctrl+,`) and choose your preferred tier under **SPEECH MODEL**:
  * `AUTO (SMART)`: Auto-select based on device hardware.
  * `WHISPERFLOW (GPU)`: Force high-accuracy WhisperFlow model.
  * `DEFAULT (CPU)`: Force lightweight model.

---

## 3. How to Install the WhisperFlow Model

### Step 1: Create the Model Folder

Open PowerShell and create the model directory:

```powershell
New-Item -ItemType Directory -Force "$env:LOCALAPPDATA\WhisprAI\models\whisper-flow"
```

### Step 2: Download WhisperFlow Model Files

The WhisperFlow model requires three files:
1. `encoder.int8.onnx`
2. `decoder.int8.onnx`
3. `tokens.txt`

You can download pre-quantized bilingual Whisper-Small ONNX files from the sherpa-onnx release repository:

```powershell
# Set download target
$targetDir = "$env:LOCALAPPDATA\WhisprAI\models\whisper-flow"

# Download Whisper-Small INT8 model archive (Bilingual English & Arabic)
$url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2"
$archive = "$env:TEMP\whisper-small.tar.bz2"

Invoke-WebRequest -Uri $url -OutFile $archive
tar -xjf $archive -C $targetDir --strip-components=1
Remove-Item $archive
```

Alternatively, on Linux / macOS:

```bash
mkdir -p ~/.local/share/WhisprAI/models/whisper-flow
curl -L -o /tmp/whisper-small.tar.bz2 https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-small.tar.bz2
tar -xjf /tmp/whisper-small.tar.bz2 -C ~/.local/share/WhisprAI/models/whisper-flow --strip-components=1
```

### Step 3: Verify Folder Structure

Your model folder should look like this:

```text
%LOCALAPPDATA%\WhisprAI\models\whisper-flow\
    ├── small-encoder.int8.onnx  (or encoder.int8.onnx)
    ├── small-decoder.int8.onnx  (or decoder.int8.onnx)
    └── small-tokens.txt        (or tokens.txt)
```

---

## 4. How to Use It

### On Windows
1. **Launch Whispr AI**: Run `WhisprAI.exe`.
2. **Open Settings**: Click the gear icon or press `Ctrl+,`.
   * Under **DEVICE & ACCELERATION**, confirm your GPU is recognized.
   * Under **SPEECH MODEL**, ensure the lamp is **GREEN** indicating `WhisperFlow (Best / High Accuracy) ready`.
3. **Dictate**:
   * **Touch / Tap Control button**: Touch the `Control` key (Left Ctrl or Right Ctrl) to begin recording. The recording indicator will glow.
   * Speak naturally in English or Arabic.
   * Touch the `Control` key again to stop dictation.
   * Your speech is transcribed with WhisperFlow and automatically injected into whichever application is currently focused (Notepad, Word, Discord, Browser, etc.).
   * The built-in **Bilingual Grammar Engine** will automatically:
     * Remove hesitations and filler words (`um`, `uh`, `يعني`, `أمم`).
     * Deduplicate stutter words (e.g., *"the the words"* $\rightarrow$ *"the words"*).
     * Format punctuation and questions (`?` in English, `؟` in Arabic).

### On Android (Mobile RAM Optimized)
1. Open the **Whispr AI** app.
2. The home screen displays your device specifications and active model tier:
   * **Mobile RAM Optimization**: Phones have constrained RAM compared to laptops and desktop workstations. Whispr AI defaults to the **`DEFAULT (CPU)`** lightweight model (~39 MB / on-device speech engine), using under 150 MB total RAM with near-zero battery drain.
   * If you have a flagship gaming or workstation tablet and want maximum accuracy, you can optionally tap the **`WHISPERFLOW (GPU)`** chip.
3. Switch the language toggle between `AUTO`, `ENGLISH`, and `العربية`.
4. Enable the **Whispr AI Keyboard** in system settings to use push-to-talk dictation across all Android apps.

---

## 5. Execution Providers: GPU Acceleration

Whispr AI supports three execution providers:
1. **`directml`**: High-performance DirectX 12 hardware acceleration compatible with **all modern GPUs** (NVIDIA GeForce/RTX, AMD Radeon RX, Intel Arc, and Intel Iris Xe).
2. **`cuda`**: Dedicated NVIDIA CUDA acceleration for NVIDIA RTX / GTX GPUs.
3. **`cpu`**: Multi-threaded CPU fallback (4 threads) when running on systems without a dedicated GPU.

If GPU initialization encounters any driver incompatibilities, Whispr AI automatically and silently falls back to CPU execution so your dictation is never interrupted.

---

## 6. Summary of Default Search Locations

Whispr AI automatically discovers speech models placed in any of the following folders:

1. `%LOCALAPPDATA%\WhisprAI\models\whisper-flow\` (WhisperFlow Best model)
2. `%LOCALAPPDATA%\WhisprAI\models\whisper-small\` (Whisper Small)
3. `%LOCALAPPDATA%\WhisprAI\models\whisper-tiny\` (Default lightweight model)
4. `%LOCALAPPDATA%\WhisprAI\models\whisper-base\` (Default base model)
5. `<AppDirectory>\models\whisper-flow\`
6. `<AppDirectory>\models\whisper-tiny\`
