package dev.whispr.whispr_ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Shared storage for the dictionary file and the transcript history. Both the IME and
 * the Flutter UI (via the method channel in MainActivity) read and write through here,
 * so there is exactly one file format:
 *
 *   dictionary : lines of "hear -> write" (corrections), bare text (terms),
 *                "# off: ..." marks a disabled entry — same format as the desktop app.
 *   history    : JSON lines, one transcript object per line.
 */
object DictionaryStore {
    fun dictionaryFile(context: Context) = File(context.filesDir, "whispr_dictionary.txt")
    fun historyFile(context: Context) = File(context.filesDir, "whispr_transcripts.jsonl")

    fun loadEntries(context: Context): List<DictionaryEntry> {
        val f = dictionaryFile(context)
        if (!f.exists()) return emptyList()
        val entries = mutableListOf<DictionaryEntry>()
        f.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line == "# Whispr AI dictionary") return@forEach
            val disabled = line.startsWith("# off:")
            val body = if (disabled) line.removePrefix("# off:").trim() else line
            if (body.isEmpty()) return@forEach
            val arrow = body.split(" -> ", limit = 2)
            entries.add(
                if (arrow.size == 2) {
                    DictionaryEntry(
                        kind = EntryKind.CORRECTION,
                        hear = arrow[0].trim(),
                        write = arrow[1].trim(),
                        isEnabled = !disabled,
                    )
                } else {
                    DictionaryEntry(
                        kind = EntryKind.TERM,
                        write = body,
                        isEnabled = !disabled,
                    )
                }
            )
        }
        return entries
    }

    fun saveEntries(context: Context, entries: List<DictionaryEntry>) {
        val sb = StringBuilder("# Whispr AI dictionary\n")
        entries.forEach { sb.append(it.toFileLine()).append('\n') }
        dictionaryFile(context).writeText(sb.toString())
    }

    fun rules(context: Context) =
        DictionaryCorrector.compileRules(loadEntries(context))

    fun appendTranscript(context: Context, text: String, applied: List<AppliedCorrection>) {
        val obj = JSONObject()
        obj.put("ts", System.currentTimeMillis())
        obj.put("source", "ime")
        obj.put("text", text)
        obj.put("corrections", JSONArray().apply {
            applied.forEach { c ->
                put(JSONObject().put("from", c.from).put("to", c.to).put("count", c.count))
            }
        })
        historyFile(context).appendText(obj.toString() + "\n")
    }

    fun readHistory(context: Context): List<JSONObject> {
        val f = historyFile(context)
        if (!f.exists()) return emptyList()
        return f.readLines().mapNotNull { line ->
            try { JSONObject(line) } catch (_: Exception) { null }
        }.reversed()
    }
}

enum class KeyboardMode { ENGLISH, ARABIC, SYMBOLS, SYMBOLS_EXT, EMOJI, CLIPBOARD }

/**
 * Whispr AI full bilingual keyboard with integrated push-to-talk voice dictation,
 * on-device speech recognition, bilingual grammar cleaning, and custom dictionary correction.
 */
class ImeDictationService : InputMethodService(), RecognitionListener {

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var rules: List<DictionaryCorrector.Rule> = emptyList()
    private var language: String = "auto"

    private var currentMode = KeyboardMode.ENGLISH
    private var isShifted = false
    private var isCapsLock = false
    private var lastShiftPressTime = 0L

    // UI elements
    private lateinit var rootLayout: LinearLayout
    private lateinit var headerBar: LinearLayout
    private lateinit var dictationButton: TextView
    private lateinit var statusText: TextView
    private lateinit var langPillEn: TextView
    private lateinit var langPillAr: TextView
    private lateinit var keysContainer: LinearLayout

    // Theming (Matches the user's sleek obsidian-dark keyboard with cyan/teal accents)
    private val bgDark = Color.parseColor("#121316")
    private val keyBg = Color.parseColor("#22242B")
    private val keyBgPressed = Color.parseColor("#363A45")
    private val keySpecialBg = Color.parseColor("#1A1C22")
    private val textWhite = Color.parseColor("#FFFFFF")
    private val textHint = Color.parseColor("#787D8A")
    private val tealAccent = Color.parseColor("#00E5FF")
    private val recRed = Color.parseColor("#E53935")
    private val recRedLit = Color.parseColor("#FF5252")

    private val backspaceHandler = Handler(Looper.getMainLooper())
    private val backspaceRunnable = object : Runnable {
        override fun run() {
            handleBackspace()
            backspaceHandler.postDelayed(this, 50)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    override fun onCreateInputView(): View {
        rules = DictionaryStore.rules(this)
        val prefs = getSharedPreferences("whispr_ime_prefs", Context.MODE_PRIVATE)
        language = prefs.getString("language", "auto") ?: "auto"
        currentMode = if (language == "ar") KeyboardMode.ARABIC else KeyboardMode.ENGLISH

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgDark)
            setPadding(dp(3), dp(4), dp(3), dp(6))
        }

        buildHeaderBar()
        rootLayout.addView(headerBar)

        keysContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rootLayout.addView(keysContainer)

        renderKeyboard()
        return rootLayout
    }

    private fun buildHeaderBar() {
        headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42)
            ).apply {
                setMargins(dp(4), dp(2), dp(4), dp(4))
            }
        }

        // Whispr Voice Dictation button
        dictationButton = TextView(this).apply {
            text = "🎙️ Whispr"
            setTextColor(tealAccent)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = createPillDrawable(active = false, cornerRadiusDp = 16f, customBorderColor = tealAccent)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                toggleListening()
            }
        }

        // Status & Live preview text
        statusText = TextView(this).apply {
            text = "on-device · bilingual"
            setTextColor(textHint)
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            setPadding(dp(8), 0, dp(8), 0)
        }

        // Language switcher container: [ En ] [ ع ]
        val langContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = createKeyDrawable(Color.parseColor("#181A20"), Color.parseColor("#181A20"), 16f)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }

        langPillEn = TextView(this).apply {
            text = "En"
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                setLanguage("en")
            }
        }

        langPillAr = TextView(this).apply {
            text = "ع"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                setLanguage("ar")
            }
        }

        langContainer.addView(langPillEn)
        langContainer.addView(langPillAr)
        val emojiButton = TextView(this).apply {
            text = "😊"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                currentMode = if (currentMode == KeyboardMode.EMOJI) {
                    if (language == "ar") KeyboardMode.ARABIC else KeyboardMode.ENGLISH
                } else {
                    KeyboardMode.EMOJI
                }
                renderKeyboard()
            }
        }

        val clipboardButton = TextView(this).apply {
            text = "📋"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                currentMode = if (currentMode == KeyboardMode.CLIPBOARD) {
                    if (language == "ar") KeyboardMode.ARABIC else KeyboardMode.ENGLISH
                } else {
                    KeyboardMode.CLIPBOARD
                }
                renderKeyboard()
            }
        }

        headerBar.addView(dictationButton)
        headerBar.addView(emojiButton)
        headerBar.addView(clipboardButton)
        headerBar.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headerBar.addView(langContainer)
    }

    private fun setLanguage(lang: String) {
        language = lang
        getSharedPreferences("whispr_ime_prefs", Context.MODE_PRIVATE)
            .edit().putString("language", lang).apply()
        currentMode = if (lang == "ar") KeyboardMode.ARABIC else KeyboardMode.ENGLISH
        updateLanguagePills()
        renderKeyboard()
    }

    private fun updateLanguagePills() {
        val isAr = language == "ar"
        langPillEn.apply {
            background = createPillDrawable(!isAr, cornerRadiusDp = 14f)
            setTextColor(if (!isAr) tealAccent else textHint)
            typeface = if (!isAr) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        langPillAr.apply {
            background = createPillDrawable(isAr, cornerRadiusDp = 14f)
            setTextColor(if (isAr) tealAccent else textHint)
            typeface = if (isAr) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun renderKeyboard() {
        keysContainer.removeAllViews()

        when (currentMode) {
            KeyboardMode.ENGLISH -> buildEnglishKeyboard()
            KeyboardMode.ARABIC -> buildArabicKeyboard()
            KeyboardMode.SYMBOLS -> buildSymbolsKeyboard()
            KeyboardMode.SYMBOLS_EXT -> buildSymbolsExtKeyboard()
            KeyboardMode.EMOJI -> buildEmojiKeyboard()
            KeyboardMode.CLIPBOARD -> buildClipboardKeyboard()
        }
    }

    // ---- English QWERTY Layout ----
    private fun buildEnglishKeyboard() {
        val row1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
        val hints1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        keysContainer.addView(createRowWithHints(row1, hints1))

        val row2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
        keysContainer.addView(createRow(row2, sidePaddingPercent = 0.5f))

        // Row 3: Shift, z..m, Backspace
        val r3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val shiftIcon = if (isCapsLock) "⇪" else "⇧"
        val shiftColor = if (isShifted || isCapsLock) tealAccent else textWhite
        r3.addView(createSpecialKey(shiftIcon, weight = 1.5f, textColor = shiftColor) {
            val now = System.currentTimeMillis()
            if (now - lastShiftPressTime < 350) {
                isCapsLock = !isCapsLock
                isShifted = isCapsLock
            } else {
                isShifted = !isShifted
                if (!isShifted) isCapsLock = false
            }
            lastShiftPressTime = now
            renderKeyboard()
        })
        val row3Chars = listOf("z", "x", "c", "v", "b", "n", "m")
        row3Chars.forEach { char ->
            r3.addView(createCharKey(char, weight = 1.0f))
        }
        r3.addView(createBackspaceKey(weight = 1.5f))
        keysContainer.addView(r3)

        // Row 4: ?123, comma, Space, dot, Enter
        val r4 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        r4.addView(createSpecialKey("?123", weight = 1.4f, textColor = tealAccent) {
            currentMode = KeyboardMode.SYMBOLS
            renderKeyboard()
        })
        r4.addView(createCharKey(",", weight = 1.0f))
        r4.addView(createSpaceKey("English", weight = 4.2f))
        r4.addView(createCharKey(".", weight = 1.0f))
        r4.addView(createEnterKey(weight = 1.4f))
        keysContainer.addView(r4)
    }

    // ---- Arabic Layout ----
    private fun buildArabicKeyboard() {
        val row1 = listOf("ض", "ص", "ث", "ق", "ف", "غ", "ع", "ه", "خ", "ح", "ج")
        keysContainer.addView(createRow(row1))

        val row2 = listOf("ش", "س", "ي", "ب", "ل", "ا", "ت", "ن", "م", "ك", "ط")
        keysContainer.addView(createRow(row2))

        val r3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val row3 = listOf("ذ", "ء", "ؤ", "ر", "ى", "ة", "و", "ز", "ظ", "د")
        row3.forEach { char ->
            r3.addView(createCharKey(char, weight = 1.0f))
        }
        r3.addView(createBackspaceKey(weight = 1.4f))
        keysContainer.addView(r3)

        val r4 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        r4.addView(createSpecialKey("123", weight = 1.4f, textColor = tealAccent) {
            currentMode = KeyboardMode.SYMBOLS
            renderKeyboard()
        })
        r4.addView(createCharKey("،", weight = 1.0f))
        r4.addView(createSpaceKey("العربية", weight = 4.2f))
        r4.addView(createCharKey(".", weight = 1.0f))
        r4.addView(createEnterKey(weight = 1.4f))
        keysContainer.addView(r4)
    }

    // ---- Symbols Layout (Matches Screenshot 4) ----
    private fun buildSymbolsKeyboard() {
        val row1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        keysContainer.addView(createRow(row1))

        // Row 2: Islamic and decorative ligatures & symbols
        val row2 = listOf("﷼", "الله", "ﷺ", "ﷻ", "♡", "•", "%", "«", "»", "~")
        keysContainer.addView(createRow(row2))

        // Row 3: Symbols
        val row3 = listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/")
        keysContainer.addView(createRow(row3))

        // Row 4: >|= toggle, punctuation, Backspace
        val r4 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        r4.addView(createSpecialKey(">|=", weight = 1.4f, textColor = tealAccent) {
            currentMode = KeyboardMode.SYMBOLS_EXT
            renderKeyboard()
        })
        val row4Chars = listOf("*", "\"", "'", ":", ";", "!", "?")
        row4Chars.forEach { r4.addView(createCharKey(it, weight = 1.0f)) }
        r4.addView(createBackspaceKey(weight = 1.4f))
        keysContainer.addView(r4)

        // Row 5: ABC / Space / Enter
        val r5 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val returnLabel = if (language == "ar") "عربي" else "ABC"
        r5.addView(createSpecialKey(returnLabel, weight = 1.4f, textColor = tealAccent) {
            currentMode = if (language == "ar") KeyboardMode.ARABIC else KeyboardMode.ENGLISH
            renderKeyboard()
        })
        r5.addView(createCharKey(",", weight = 1.0f))
        r5.addView(createSpaceKey("Space", weight = 4.2f))
        r5.addView(createCharKey(".", weight = 1.0f))
        r5.addView(createEnterKey(weight = 1.4f))
        keysContainer.addView(r5)
    }

    // ---- Extended Symbols Layout (Matches Screenshot 1) ----
    private fun buildSymbolsExtKeyboard() {
        val row1 = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        keysContainer.addView(createRow(row1))

        // Row 2: Currency & math symbols from Screenshot 1
        val row2 = listOf("£", "€", "ج.م", " ", "√", "π", "÷", "×", "=", "|")
        keysContainer.addView(createRow(row2))

        // Row 3: Brackets & arrows
        val row3 = listOf("«", "^", "°", "{", "}", "[", "]", "<", ">", "\\")
        keysContainer.addView(createRow(row3))

        // Row 4: Symbols & Backspace
        val r4 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        r4.addView(createSpecialKey("123", weight = 1.4f, textColor = tealAccent) {
            currentMode = KeyboardMode.SYMBOLS
            renderKeyboard()
        })
        val r4Chars = listOf("Δ", "©", "®", "™", "✓", "§", "¶")
        r4Chars.forEach { r4.addView(createCharKey(it, weight = 1.0f)) }
        r4.addView(createBackspaceKey(weight = 1.4f))
        keysContainer.addView(r4)

        // Row 5: ABC / 123 / Space / Enter
        val r5 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val returnLabel = if (language == "ar") "عربي" else "ABC"
        r5.addView(createSpecialKey(returnLabel, weight = 1.4f, textColor = tealAccent) {
            currentMode = if (language == "ar") KeyboardMode.ARABIC else KeyboardMode.ENGLISH
            renderKeyboard()
        })
        r5.addView(createSpecialKey("123", weight = 1.0f, textColor = tealAccent) {
            currentMode = KeyboardMode.SYMBOLS
            renderKeyboard()
        })
        r5.addView(createSpaceKey("Space", weight = 3.6f))
        r5.addView(createCharKey("»", weight = 1.0f))
        r5.addView(createEnterKey(weight = 1.4f))
        keysContainer.addView(r5)
    }

    // ---- Emoji Layout (Matches Screenshot 5) ----
    private fun buildEmojiKeyboard() {
        val rows = listOf(
            listOf("😂", "🔥", "❤️", "😍", "😊", "🙏", "👍", "👏"),
            listOf("✨", "🎉", "🥰", "😎", "🤔", "😭", "🥺", "💯"),
            listOf("⭐", "🌹", "🕊️", "☕", "(✪‿✪)", "(◕‿◕)", "(•‿•)", "(¬_¬)")
        )
        rows.forEach { row ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.forEach { em ->
                r.addView(createCharKey(em, weight = 1.0f))
            }
            keysContainer.addView(r)
        }

        val rBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val returnLabel = if (language == "ar") "عربي" else "ABC"
        rBottom.addView(createSpecialKey(returnLabel, weight = 1.5f, textColor = tealAccent) {
            currentMode = if (language == "ar") KeyboardMode.ARABIC else KeyboardMode.ENGLISH
            renderKeyboard()
        })
        rBottom.addView(createSpaceKey("Space", weight = 4.5f))
        rBottom.addView(createBackspaceKey(weight = 1.5f))
        keysContainer.addView(rBottom)
    }

    // ---- Clipboard / Dictation History Layout (Matches Screenshot 2) ----
    private fun buildClipboardKeyboard() {
        val history = DictionaryStore.readHistory(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(190)
            )
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }

        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        if (history.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "No saved transcripts yet. Tap 🎙️ Whispr to dictate!"
                setTextColor(textHint)
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(30), 0, 0)
            })
        } else {
            history.take(6).forEach { item ->
                val txt = item.optString("text")
                if (txt.isNotEmpty()) {
                    val card = TextView(this).apply {
                        text = "📋 $txt"
                        setTextColor(textWhite)
                        textSize = 14f
                        background = createKeyDrawable(keyBg, keyBgPressed, cornerRadiusDp = 8f)
                        setPadding(dp(12), dp(8), dp(12), dp(8))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, dp(3), 0, dp(3))
                        }
                        setOnClickListener {
                            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            currentInputConnection?.commitText(txt + " ", 1)
                        }
                    }
                    listLayout.addView(card)
                }
            }
        }
        scroll.addView(listLayout)
        container.addView(scroll)

        val rBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val returnLabel = if (language == "ar") "عربي" else "ABC"
        rBottom.addView(createSpecialKey(returnLabel, weight = 1.5f, textColor = tealAccent) {
            currentMode = if (language == "ar") KeyboardMode.ARABIC else KeyboardMode.ENGLISH
            renderKeyboard()
        })
        rBottom.addView(createSpaceKey("Space", weight = 4.5f))
        rBottom.addView(createBackspaceKey(weight = 1.5f))
        container.addView(rBottom)

        keysContainer.addView(container)
    }

    // ---- Key View Factory Helpers ----

    private fun createRow(keys: List<String>, sidePaddingPercent: Float = 0f): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            if (sidePaddingPercent > 0) {
                addView(View(context), LinearLayout.LayoutParams(0, dp(48), sidePaddingPercent))
            }
            keys.forEach { char ->
                addView(createCharKey(char, weight = 1.0f))
            }
            if (sidePaddingPercent > 0) {
                addView(View(context), LinearLayout.LayoutParams(0, dp(48), sidePaddingPercent))
            }
        }
    }

    private fun createRowWithHints(keys: List<String>, hints: List<String>): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            keys.forEachIndexed { i, char ->
                val hint = hints.getOrNull(i)
                addView(createCharKeyWithHint(char, hint, weight = 1.0f))
            }
        }
    }

    private fun createCharKey(char: String, weight: Float): View {
        val displayChar = if (isShifted || isCapsLock) char.uppercase() else char
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            background = createKeyDrawable(keyBg, keyBgPressed)
            isClickable = true
            isFocusable = true
            val isAr = BilingualGrammarCorrector.isArabic(char)
            val font = if (isAr) Typeface.DEFAULT_BOLD else Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            addView(TextView(context).apply {
                text = displayChar
                textSize = 19f
                typeface = font
                setTextColor(textWhite)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                currentInputConnection?.commitText(displayChar, 1)
                if (isShifted && !isCapsLock) {
                    isShifted = false
                    renderKeyboard()
                }
            }
        }
    }

    private fun createCharKeyWithHint(char: String, hint: String?, weight: Float): View {
        val displayChar = if (isShifted || isCapsLock) char.uppercase() else char
        val isAr = BilingualGrammarCorrector.isArabic(char)
        val font = if (isAr) Typeface.DEFAULT_BOLD else Typeface.create(Typeface.SERIF, Typeface.ITALIC)
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            background = createKeyDrawable(keyBg, keyBgPressed)
            isClickable = true
            isFocusable = true

            if (hint != null) {
                addView(TextView(context).apply {
                    text = hint
                    textSize = 10f
                    typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                    setTextColor(textHint)
                    gravity = Gravity.TOP or Gravity.END
                    setPadding(0, dp(2), dp(4), 0)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                })
            }

            addView(TextView(context).apply {
                text = displayChar
                textSize = 19f
                typeface = font
                setTextColor(textWhite)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })

            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                currentInputConnection?.commitText(displayChar, 1)
                if (isShifted && !isCapsLock) {
                    isShifted = false
                    renderKeyboard()
                }
            }
        }
    }

    private fun createSpecialKey(
        label: String,
        weight: Float,
        textColor: Int = textWhite,
        onClick: () -> Unit
    ): View {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            background = createKeyDrawable(keySpecialBg, keyBgPressed)
            isClickable = true
            isFocusable = true
            addView(TextView(context).apply {
                text = label
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(textColor)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBackspaceKey(weight: Float): View {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            background = createKeyDrawable(keySpecialBg, keyBgPressed)
            isClickable = true
            isFocusable = true
            addView(TextView(context).apply {
                text = "⌫"
                textSize = 18f
                setTextColor(tealAccent)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        handleBackspace()
                        backspaceHandler.removeCallbacks(backspaceRunnable)
                        backspaceHandler.postDelayed(backspaceRunnable, 400)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        backspaceHandler.removeCallbacks(backspaceRunnable)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
        } else {
            ic.commitText("", 1)
        }
    }

    private fun createSpaceKey(label: String, weight: Float): View {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            background = createKeyDrawable(keyBg, keyBgPressed)
            isClickable = true
            isFocusable = true
            addView(TextView(context).apply {
                text = label
                textSize = 12f
                setTextColor(textHint)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                currentInputConnection?.commitText(" ", 1)
            }
        }
    }

    private fun createEnterKey(weight: Float): View {
        return FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(48), weight).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            background = createKeyDrawable(keySpecialBg, keyBgPressed)
            isClickable = true
            isFocusable = true
            addView(TextView(context).apply {
                text = "↵"
                textSize = 22f
                setTextColor(tealAccent)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val editorInfo = currentInputEditorInfo
                val imeOptions = editorInfo?.imeOptions ?: 0
                val action = imeOptions and EditorInfo.IME_MASK_ACTION
                if (action != EditorInfo.IME_ACTION_NONE && (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
                    currentInputConnection?.performEditorAction(action)
                } else {
                    currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                    currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
            }
        }
    }

    private fun createKeyDrawable(normalColor: Int, pressedColor: Int, cornerRadiusDp: Float = 6f): StateListDrawable {
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(cornerRadiusDp)
            setColor(normalColor)
        }
        val pressed = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(cornerRadiusDp)
            setColor(pressedColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    private fun createPillDrawable(active: Boolean, cornerRadiusDp: Float = 16f, customBorderColor: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(cornerRadiusDp)
            if (active) {
                setColor(Color.parseColor("#16323B"))
                setStroke(dp(1), tealAccent)
            } else if (customBorderColor != null) {
                setColor(Color.parseColor("#181A20"))
                setStroke(dp(1), customBorderColor)
            } else {
                setColor(Color.TRANSPARENT)
            }
        }
    }

    // ---- Speech Recognition Engine (Safe On-Device with Fallback) ----

    private fun createSpeechRecognizerSafely(): SpeechRecognizer? {
        // ALWAYS use universal createSpeechRecognizer. Never call createOnDeviceSpeechRecognizer
        // because it throws "IllegalArgumentException: on-device recognition is not available"
        // when Google on-device speech packs are not pre-installed on the user's phone.
        return try {
            SpeechRecognizer.createSpeechRecognizer(this)
        } catch (_: Throwable) {
            null
        }
    }

    private fun toggleListening() {
        if (listening) {
            recognizer?.stopListening()
            setIdleUi()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusText.text = "Microphone permission required"
            Toast.makeText(this, "Open Whispr AI once to allow the microphone", Toast.LENGTH_LONG).show()
            return
        }
        startRecognition()
    }

    private fun startRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.text = "no speech service on this device"
            return
        }
        rules = DictionaryStore.rules(this)

        recognizer?.destroy()
        recognizer = createSpeechRecognizerSafely()
        if (recognizer == null) {
            statusText.text = "no speech service on this device"
            setIdleUi()
            return
        }
        recognizer?.setRecognitionListener(this)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (language == "ar") {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("ar-SA", "ar-EG", "ar-AE", "ar"))
            } else if (language == "en") {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "en-GB"))
            }
        }
        listening = true
        dictationButton.text = "● STOP"
        dictationButton.setTextColor(Color.WHITE)
        dictationButton.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpF(16f)
            setColor(recRed)
        }
        statusText.text = "Listening… Speak now"
        statusText.setTextColor(recRedLit)

        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            statusText.text = e.localizedMessage ?: "failed to start"
            setIdleUi()
        }
    }

    private fun setIdleUi() {
        listening = false
        dictationButton.text = "🎙️ Whispr"
        dictationButton.setTextColor(tealAccent)
        dictationButton.background = createPillDrawable(active = false, cornerRadiusDp = 16f, customBorderColor = tealAccent)
        statusText.text = "on-device · bilingual"
        statusText.setTextColor(textHint)
    }

    // ---- RecognitionListener ----

    override fun onPartialResults(partialResults: Bundle?) {
        val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        if (list.isNotEmpty()) {
            val partial = list[0]
            statusText.text = "🗣️ $partial"
            statusText.setTextColor(tealAccent)
        }
    }

    override fun onResults(results: Bundle?) {
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val raw = list?.firstOrNull().orEmpty()
        if (raw.isEmpty()) {
            statusText.text = "no speech heard"
            setIdleUi()
            return
        }

        // Apply custom dictionary corrections and bilingual grammar cleanups
        val (dictCorrected, applied) = DictionaryCorrector.apply(raw, rules)
        val cleaned = BilingualGrammarCorrector.correct(dictCorrected, language)

        // Directly commit the corrected text into the focused field
        currentInputConnection?.commitText(cleaned + " ", 1)
        DictionaryStore.appendTranscript(this, cleaned, applied)

        val note = if (applied.isNotEmpty()) " (${applied.sumOf { it.count }} corrected)" else ""
        statusText.text = "Inserted: $cleaned$note"
        setIdleUi()
    }

    override fun onError(error: Int) {
        statusText.text = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
            SpeechRecognizer.ERROR_NETWORK -> "network needed (offline engine unavailable)"
            else -> "speech error ($error)"
        }
        setIdleUi()
    }

    override fun onRmsChanged(rmsdB: Float) {
        // level meter hook
    }

    override fun onReadyForSpeech(params: Bundle?) { statusText.text = "Listening…" }
    override fun onBeginningOfSpeech() { statusText.text = "Hearing you…" }
    override fun onEndOfSpeech() { statusText.text = "Processing…" }
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        backspaceHandler.removeCallbacks(backspaceRunnable)
        recognizer?.destroy()
        super.onDestroy()
    }
}
