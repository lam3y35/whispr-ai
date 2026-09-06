package dev.whispr.whispr_ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Bundle
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/**
 * Whispr AI keyboard. A minimal, keyboard-less IME: a record button, a live preview,
 * and an Insert button that commits the corrected transcript into whatever field has
 * focus. This is the same mechanism Gboard voice typing uses, which is why it can
 * dictate into any app.
 */
class ImeDictationService : InputMethodService(), RecognitionListener {

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var finalText = ""
    private var rules: List<DictionaryCorrector.Rule> = emptyList()

    private lateinit var root: LinearLayout
    private lateinit var micButton: Button
    private lateinit var insertButton: Button
    private lateinit var statusText: TextView
    private lateinit var previewText: TextView

    // ---- palette: the sober end of the 1980s recorder ----
    private val face = Color.parseColor("#1C1B18")
    private val panel = Color.parseColor("#2A2823")
    private val ink = Color.parseColor("#EDE6D6")
    private val muted = Color.parseColor("#8F8A7C")
    private val recRed = Color.parseColor("#C0392B")
    private val recLit = Color.parseColor("#E74C3C")
    private val amber = Color.parseColor("#D9A441")

    override fun onCreateInputView(): View {
        rules = DictionaryStore.rules(this)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(face)
            setPadding(24, 20, 24, 24)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        micButton = Button(this).apply {
            text = "\u25CF  TAP TO DICTATE"
            setBackgroundColor(recRed)
            setTextColor(ink)
            isAllCaps = true
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { toggleListening() }
        }

        statusText = TextView(this).apply {
            text = "on-device \u00b7 offline-first"
            setTextColor(muted)
            textSize = 12f
            setPadding(24, 0, 0, 0)
        }

        topRow.addView(micButton)
        topRow.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        previewText = TextView(this).apply {
            setTextColor(ink)
            textSize = 16f
            setPadding(8, 24, 8, 24)
            text = "\u2014"
        }
        val previewScroll = ScrollView(this).apply { addView(previewText) }

        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        insertButton = Button(this).apply {
            text = "Insert text"
            setBackgroundColor(panel)
            setTextColor(amber)
            isEnabled = false
            setOnClickListener { insertIntoField() }
        }
        val clearButton = Button(this).apply {
            text = "Clear"
            setBackgroundColor(panel)
            setTextColor(ink)
            setOnClickListener { resetSession() }
        }
        bottomRow.addView(insertButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bottomRow.addView(clearButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(topRow)
        root.addView(previewScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomRow)
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        if (!this::statusText.isInitialized) return
        // Password fields (all variants) are not a place for dictation.
        val it = attribute?.inputType ?: return
        val variation = it and InputType.TYPE_MASK_VARIATION
        val isPassword = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        if (isPassword) {
            statusText.text = "password field \u00b7 dictation off"
        }
    }

    private fun toggleListening() {
        if (listening) {
            recognizer?.stopListening()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // An IME is a Service, not an Activity, so it cannot show the permission
            // dialog. The main app requests the grant; the IME merely guides.
            statusText.text = "mic not granted \u00b7 open the Whispr AI app once"
            Toast.makeText(this, "Open Whispr AI once to allow the microphone",
                Toast.LENGTH_LONG).show()
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
        finalText = ""
        previewText.text = ""

        recognizer?.destroy()
        recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        recognizer?.setRecognitionListener(this)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        listening = true
        micButton.text = "\u25CF  LISTENING \u2014 TAP TO STOP"
        micButton.setBackgroundColor(recLit)
        statusText.text = "speak now"
        insertButton.isEnabled = false
        recognizer?.startListening(intent)
    }

    private fun insertIntoField() {
        if (finalText.isEmpty()) return
        currentInputConnection?.commitText(finalText, 1)
            ?: Toast.makeText(this, "no text field focused", Toast.LENGTH_SHORT).show()
        resetSession()
    }

    private fun resetSession() {
        finalText = ""
        previewText.text = "\u2014"
        insertButton.isEnabled = false
        setIdleUi()
    }

    private fun setIdleUi() {
        listening = false
        micButton.text = "\u25CF  TAP TO DICTATE"
        micButton.setBackgroundColor(recRed)
        statusText.text = "on-device \u00b7 offline-first"
    }

    private fun cleanup(raw: String): String {
        var t = raw.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty()) return t
        t = t.replaceFirstChar { it.uppercase(Locale.getDefault()) }
        val last = t.last()
        if (last.isLetterOrDigit()) t += "."
        return t
    }

    // ---- RecognitionListener ----

    override fun onPartialResults(partialResults: Bundle?) {
        val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?: return
        if (list.isNotEmpty()) previewText.text = list[0]
    }

    override fun onResults(results: Bundle?) {
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val raw = list?.firstOrNull().orEmpty()
        if (raw.isEmpty()) {
            statusText.text = "no speech heard"
            setIdleUi()
            return
        }
        val cleaned = cleanup(raw)
        val (corrected, applied) = DictionaryCorrector.apply(cleaned, rules)
        finalText = corrected
        previewText.text = corrected
        insertButton.isEnabled = true
        statusText.text = if (applied.isEmpty()) "ready \u00b7 tap Insert"
        else "ready \u00b7 ${applied.sumOf { it.count }} correction(s) applied"
        DictionaryStore.appendTranscript(this, corrected, applied)
        setIdleUi()
    }

    override fun onError(error: Int) {
        statusText.text = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH -> "no speech heard"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission missing"
            SpeechRecognizer.ERROR_NETWORK -> "network needed (offline engine unavailable)"
            else -> "error $error"
        }
        setIdleUi()
    }

    override fun onRmsChanged(rmsdB: Float) {
        // level meter hook; intentionally cheap
    }

    override fun onReadyForSpeech(params: Bundle?) { statusText.text = "listening" }
    override fun onBeginningOfSpeech() { statusText.text = "hearing you\u2026" }
    override fun onEndOfSpeech() { statusText.text = "processing\u2026" }
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }


}
