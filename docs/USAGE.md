# Whispr AI — How to Use

Push-to-talk dictation. Hold a key (or tap a button), speak, and the text lands
wherever your cursor is. Everything runs on your own device — nothing you say
leaves the machine.

You have two apps in this folder:

| File | What it is | Where it runs |
|---|---|---|
| `WhisprAI.exe` | Desktop dictation for Windows | Your laptop |
| `WhisprAI-android.apk` | Keyboard + app for Android | Your phone |

---

## 1. Windows laptop

### First run

1. **Double-click `WhisprAI.exe`.** Windows SmartScreen may appear because the
   app is not signed — click *More info* → *Run anyway*.
2. A tray icon appears (bottom-right, near the clock). The main window can be
   opened from the tray; closing the window keeps the app running in the tray
   so the hotkey still works.

### The push-to-talk key

- **Hold `Right Ctrl`** (the right-hand Ctrl key) and start speaking.
- **Release the key** — the recording stops and the text is typed into
  whatever text field your cursor was in (your editor, browser, chat window,
  anywhere).
- Right Ctrl is chosen because it types no character on any keyboard layout,
  so holding it can never interfere with normal typing.
- You can change the key later in **Settings** (Right Shift or Right Alt).

### Before your first dictation (one-time setup)

1. **The speech model.** Windows has no built-in speech engine, so Whispr AI
   uses NVIDIA's Parakeet model. On this machine it is **already installed**
   at `%LOCALAPPDATA%\WhisprAI\models\parakeet-v2\`. If you ever see
   "Speech model not installed", open Settings → Model to see the exact
   folder it expects.
2. **Microphone permission.** Windows Settings → Privacy & security →
   Microphone → make sure *Let desktop apps access your microphone* is On.
   If it is off, the app hears silence — not an error.

### Using it day to day

- Click into any text field (Notepad, VS Code, WhatsApp Web — anything).
- Hold **Right Ctrl**, speak normally, let go.
- The text appears with punctuation, capitalization and spacing cleaned up.
- Your dictations are saved locally: the main window's **Transcriptions**
  tab shows a searchable history with a copy button on each entry.

### The Dictionary (the part that makes it yours)

Every speech engine mishears names. Open the **Dictionary** tab:

- **Add a correction** — *when you hear X, write Y*. Example:
  hear `cloud code` → write `Claude Code`. This catches `CloudCode` and
  `Cloud-Code` too, but never touches `Cloudflare` or the word "cloud".
- **Add a term** — a word the engine should lean toward (names, jargon,
  product names).
- The dictionary is also a plain text file you can edit directly:
  `%LOCALAPPDATA%\WhisprAI\dictionary.txt`

---

## 2. Android phone

The Android app is a **keyboard**. That is what lets it dictate into any app
(WhatsApp, Gmail, Notes...) — the same mechanism Gboard's voice typing uses.

### Move the APK to your phone (pick one)

- **USB cable (simplest):** connect the phone, set USB mode to *File transfer*,
  copy `WhisprAI-android.apk` into the Downloads folder.
- **Telegram/WhatsApp to yourself:** send the APK as a file to your own
  "Saved Messages", open it on the phone.
- **Google Drive / any cloud:** upload, download on the phone.

### Install

1. On the phone, tap the APK in your Files/Downloads app.
2. Android will warn *"For your security, your phone is not allowed to
   install unknown apps from this source."* Tap **Settings** → allow
   *Install unknown apps* for that app → go back → **Install**.
   (This warning appears for every app installed outside the Play Store.)

### Enable the keyboard (one-time)

1. Open the **Whispr AI** app from your launcher.
2. Allow the **Microphone** permission when asked.
3. Tap **"Enable the Whispr AI keyboard"** → find *Whispr AI Keyboard* in the
   list and switch it on.
4. In any app: tap a text field, then switch keyboards with the
   keyboard/globe icon in the navigation bar (or long-press the spacebar on
   some phones) and pick **Whispr AI Keyboard**.

### Using it

- Tap a text field so the Whispr AI keyboard shows.
- Tap the red **TAP TO DICTATE** button, speak, tap **Insert text** —
  the corrected text is committed into the field.
- The keyboard shows corrections it applied ("2 correction(s) applied").
- The app itself shows your **History** (copy any entry) and the same
  **Dictionary** editor as on Windows, and both share one file format —
  corrections you teach on the laptop can be copied to the phone as text.

### Notes

- The phone's speech recognition comes from Android's speech service
  (Google's). It works offline on most modern phones; if you see
  "network needed", your phone's speech service may only have the online
  engine — download an offline voice pack:
  Settings → System → Keyboards → Gboard → Voice typing → Offline speech
  recognition → download your language.
- Password fields intentionally refuse dictation.

---

## 3. Troubleshooting

| Symptom | Fix |
|---|---|
| Windows: logs say injected but nothing appears | Some apps (notably Electron apps) silently ignore synthetic typing. Whispr AI falls back to clipboard+paste; if that also fails, run as Administrator for elevated windows. |
| Windows: transcript is empty | Microphone privacy toggle (see above), or wrong mic selected in Windows Sound settings. |
| Windows: very slow first transcription | Normal — the model loads once (~2 s) after launch. |
| Phone: "mic not granted" on the keyboard | Open the Whispr AI *app* once and allow the microphone there; the keyboard reuses the grant. |
| Phone: keyboard not in the switcher | Re-check Settings → System → Keyboards → On-screen keyboard → Whispr AI Keyboard is toggled on. |
| Engine keeps mishearing the same word | Add a correction pair in the Dictionary — that is what it is for. |

---

## 4. Where your data lives

| What | Windows | Android |
|---|---|---|
| Dictionary | `%LOCALAPPDATA%\WhisprAI\dictionary.txt` | App-private file (exportable via the UI) |
| History | `%LOCALAPPDATA%\WhisprAI\transcripts.jsonl` | App-private file |
| Speech model | `%LOCALAPPDATA%\WhisprAI\models\parakeet-v2\` | Android's own speech service |

No cloud account. No telemetry. Delete the folders and everything is gone.
