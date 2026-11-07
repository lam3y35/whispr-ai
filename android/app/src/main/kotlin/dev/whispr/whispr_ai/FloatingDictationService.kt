package dev.whispr.whispr_ai

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Floating / Flying Dictation Button Service.
 * Renders a draggable overlay button over any app on screen.
 * Tapping it records your speech, shows the live transcript in real-time as you speak,
 * corrects grammar (stutter removal, punctuation, Arabic & English grammar),
 * and copies the clean text to the clipboard so it can be pasted into any active text field.
 */
class FloatingDictationService : Service(), RecognitionListener {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout
    private lateinit var floatingMicButton: FrameLayout
    private lateinit var liveTranscriptBubble: TextView
    private lateinit var micIconText: TextView

    private var recognizer: SpeechRecognizer? = null
    private var isRecording = false
    private var rules: List<DictionaryCorrector.Rule> = emptyList()
    private var language: String = "auto"

    private val tealColor = Color.parseColor("#00E5FF")
    private val darkBg = Color.parseColor("#15171E")
    private val redRec = Color.parseColor("#E53935")
    private val amberPrep = Color.parseColor("#FFA000")
    private val textWhite = Color.parseColor("#FFFFFF")
    private val textHint = Color.parseColor("#9E9E9E")

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isSmartMode = true

    companion object {
        var isRunning = false
            private set
        @Volatile
        var instance: FloatingDictationService? = null
            private set

        private const val NOTIF_CHANNEL_ID = "whispr_floating_service"
        private const val NOTIF_ID = 2001

        /**
         * Called by WhisprAccessibilityService when an editable text field is focused
         * or when the soft keyboard appears / disappears.
         */
        fun onInputActivityChanged(active: Boolean) {
            instance?.applyInputActivity(active)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        rules = DictionaryStore.rules(this)

        val prefs = getSharedPreferences("whispr_ime_prefs", Context.MODE_PRIVATE)
        language = prefs.getString("language", "auto") ?: "auto"
        isSmartMode = prefs.getBoolean("smart_flying_button", true)

        startForegroundServiceNotification()
        createFloatingWidget()
    }

    fun applyInputActivity(active: Boolean) {
        if (!isSmartMode) return
        mainHandler.post {
            if (active) {
                if (::overlayView.isInitialized) {
                    overlayView.visibility = View.VISIBLE
                }
            } else {
                if (!isRecording && ::overlayView.isInitialized) {
                    overlayView.visibility = View.GONE
                }
            }
        }
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "Whispr Flying Button",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Whispr AI floating dictation widget"
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }

        val notificationBuilder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NOTIF_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = notificationBuilder
            .setContentTitle("Whispr AI Floating Button")
            .setContentText("Tap the floating mic anytime to dictate with grammar")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createFloatingWidget() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.RIGHT
            x = dp(12)
            y = resources.displayMetrics.heightPixels / 3
        }

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(4), dp(4), dp(4), dp(4))
            visibility = if (isSmartMode) View.GONE else View.VISIBLE
        }

        // Live transcript preview bubble (appears next to the floating button when speaking)
        liveTranscriptBubble = TextView(this).apply {
            text = "Speak now…"
            textSize = 13f
            setTextColor(textWhite)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpF(12f)
                setColor(darkBg)
                setStroke(dp(1), tealColor)
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            isClickable = true
            setOnClickListener {
                if (isRecording) {
                    stopRecording()
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                dp(210),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, dp(8), 0)
            }
        }

        // The circular draggable floating button
        floatingMicButton = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(54), dp(54))
            background = createCircleDrawable(darkBg, tealColor)
            isClickable = true
            isFocusable = true
        }

        micIconText = TextView(this).apply {
            text = "🎙️"
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        floatingMicButton.addView(micIconText)

        overlayView.addView(liveTranscriptBubble)
        overlayView.addView(floatingMicButton)

        // Dragging & Click Handling
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isClick = false

        floatingMicButton.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isClick = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > dp(5) || Math.abs(dy) > dp(5)) {
                        isClick = false
                    }
                    params.x = (initialX - dx).coerceAtLeast(0)
                    params.y = (initialY + dy).coerceAtLeast(0)
                    try {
                        windowManager.updateViewLayout(overlayView, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isClick) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        toggleRecording()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun createCircleDrawable(fillColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            setStroke(dp(2), strokeColor)
        }
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        rules = DictionaryStore.rules(this)
        val prefs = getSharedPreferences("whispr_ime_prefs", Context.MODE_PRIVATE)
        language = prefs.getString("language", "auto") ?: "auto"

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition is not available on this device", Toast.LENGTH_SHORT).show()
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(this)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Extra silence timeouts so pauses don't cut off natural dictation
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            if (language == "ar") {
                // Egyptian Arabic prioritized for natural dialect recognition
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-EG")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("ar-EG", "en-US", "ar-SA", "ar-AE", "ar"))
            } else if (language == "en") {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "ar-EG", "en-GB", "ar"))
            }
        }

        isRecording = true
        // Show amber "Preparing" state until audio hardware is actually ready
        floatingMicButton.background = createCircleDrawable(amberPrep, Color.WHITE)
        micIconText.text = "⏳"
        liveTranscriptBubble.text = "⏳ جاري فتح المايك... / Preparing mic..."
        liveTranscriptBubble.setTextColor(amberPrep)
        liveTranscriptBubble.visibility = View.VISIBLE

        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            resetIdleState()
            Toast.makeText(this, "Failed to start speech: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        isRecording = false
        recognizer?.stopListening()
        liveTranscriptBubble.text = "Processing grammar…"
    }

    private fun handleFinalText(raw: String) {
        if (raw.isBlank()) {
            liveTranscriptBubble.text = "No speech detected"
            resetIdleState()
            return
        }

        // Apply bilingual grammar and dictionary corrections
        val (dictCorrected, applied) = DictionaryCorrector.apply(raw, rules)
        val cleaned = BilingualGrammarCorrector.correct(dictCorrected, language)

        // 1. Try direct typing into active input field first
        val typedDirectly = WhisprAccessibilityService.insertText(cleaned)

        // 2. Always copy cleaned text to clipboard as guaranteed fallback
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Whispr AI Dictation", cleaned))

        DictionaryStore.appendTranscript(this, cleaned, applied)

        if (typedDirectly) {
            liveTranscriptBubble.text = "✓ Written: $cleaned"
            liveTranscriptBubble.setTextColor(Color.parseColor("#4CAF50"))
            Toast.makeText(this, "✓ Dictated & Written automatically!", Toast.LENGTH_SHORT).show()
        } else {
            liveTranscriptBubble.text = "✓ Copied: $cleaned"
            liveTranscriptBubble.setTextColor(Color.parseColor("#4CAF50"))
            Toast.makeText(this, "✓ Dictated & Copied! Paste anywhere", Toast.LENGTH_SHORT).show()
        }

        resetIdleState()
    }

    private fun resetIdleState() {
        isRecording = false
        floatingMicButton.background = createCircleDrawable(darkBg, tealColor)
        micIconText.text = "🎙️"
        mainHandler.postDelayed({
            liveTranscriptBubble.visibility = View.GONE
        }, 2500)
    }

    // ---- Speech Recognition Listener Callbacks ----

    override fun onReadyForSpeech(params: Bundle?) {
        // Microphone hardware is now warm and actively capturing samples!
        mainHandler.post {
            if (isRecording) {
                floatingMicButton.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                floatingMicButton.background = createCircleDrawable(redRec, Color.WHITE)
                micIconText.text = "⏹️"
                liveTranscriptBubble.text = "🎙️ تحدث الآن... أنا أسمعك / Speak now!"
                liveTranscriptBubble.setTextColor(textWhite)
            }
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        if (list.isNotEmpty()) {
            val partial = list[0]
            liveTranscriptBubble.text = partial
            liveTranscriptBubble.setTextColor(tealColor)
        }
    }

    override fun onResults(results: Bundle?) {
        val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val raw = list?.firstOrNull().orEmpty()
        handleFinalText(raw)
    }

    override fun onError(error: Int) {
        val errorMsg = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission missing"
            SpeechRecognizer.ERROR_NETWORK -> "Network or offline pack needed"
            else -> "Speech error ($error)"
        }
        liveTranscriptBubble.text = errorMsg
        liveTranscriptBubble.setTextColor(Color.parseColor("#FF5252"))
        resetIdleState()
    }

    override fun onBeginningOfSpeech() {}
    override fun onEndOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onDestroy() {
        isRunning = false
        if (instance == this) {
            instance = null
        }
        recognizer?.destroy()
        try {
            if (::overlayView.isInitialized) {
                windowManager.removeView(overlayView)
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
