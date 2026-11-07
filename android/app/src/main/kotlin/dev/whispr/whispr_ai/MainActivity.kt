package dev.whispr.whispr_ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.net.Uri
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridges the Flutter UI to the native side:
 *  - dictionary read/write (same file the IME uses)
 *  - transcript history read/clear
 *  - in-app dictation session (same SpeechRecognizer the IME uses)
 *  - open the system dialog that enables the Whispr AI keyboard
 */
class MainActivity : FlutterActivity(), RecognitionListener {

    private var channel: MethodChannel? = null
    private var recognizer: SpeechRecognizer? = null
    private var rules: List<DictionaryCorrector.Rule> = emptyList()
    private var inAppFinal = ""

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).apply {
            setMethodCallHandler { call, result ->
                when (call.method) {
                    "getDictionary" -> result.success(
                        DictionaryStore.loadEntries(this@MainActivity).map { e ->
                            mapOf(
                                "id" to e.id,
                                "kind" to e.kind.name.lowercase(),
                                "write" to e.write,
                                "hear" to e.hear,
                                "isEnabled" to e.isEnabled,
                            )
                        }
                    )

                    "saveDictionary" -> {
                        @Suppress("UNCHECKED_CAST")
                        val list = call.arguments as? List<Map<String, Any?>> ?: emptyList()
                        val entries = list.map { m ->
                            DictionaryEntry(
                                id = m["id"] as? String ?: java.util.UUID.randomUUID().toString(),
                                kind = if ((m["kind"] as? String) == "correction")
                                    EntryKind.CORRECTION else EntryKind.TERM,
                                write = m["write"] as? String ?: "",
                                hear = m["hear"] as? String ?: "",
                                isEnabled = m["isEnabled"] as? Boolean ?: true,
                            )
                        }
                        DictionaryStore.saveEntries(this@MainActivity, entries)
                        result.success(null)
                    }

                    "getHistory" -> result.success(
                        DictionaryStore.readHistory(this@MainActivity).map { it.toString() }
                    )

                    "clearHistory" -> {
                        DictionaryStore.historyFile(this@MainActivity).delete()
                        result.success(null)
                    }

                    "getLanguage" -> {
                        val prefs = getSharedPreferences("whispr_ime_prefs", MODE_PRIVATE)
                        result.success(prefs.getString("language", "auto") ?: "auto")
                    }

                    "setLanguage" -> {
                        val lang = call.argument<String>("language") ?: "auto"
                        val prefs = getSharedPreferences("whispr_ime_prefs", MODE_PRIVATE)
                        prefs.edit().putString("language", lang).apply()
                        result.success(null)
                    }

                    "getDeviceInfo" -> {
                        val cores = Runtime.getRuntime().availableProcessors()
                        val maxMem = Runtime.getRuntime().maxMemory() / (1024 * 1024)
                        result.success(
                            mapOf(
                                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                                "os" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                                "cores" to cores,
                                "memoryMb" to maxMem,
                                "hasGpu" to false, // Phones preserve RAM and battery by prioritizing the lightweight model
                                "recommendedModel" to "Default (Lightweight)",
                                "summary" to "${Build.MANUFACTURER} ${Build.MODEL} · Mobile RAM Optimized · Android ${Build.VERSION.RELEASE}"
                            )
                        )
                    }

                    "startDictation" -> startDictation(result)

                    "stopDictation" -> stopDictation(result)

                    "openBrowserUrl" -> {
                        val urlStr = call.argument<String>("url") ?: "https://console.groq.com/keys"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlStr))
                        startActivity(intent)
                        result.success(null)
                    }

                    "openImeSettings" -> {
                        startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
                        result.success(null)
                    }

                    "isOverlayPermissionGranted" -> {
                        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            Settings.canDrawOverlays(this@MainActivity)
                        } else true
                        result.success(granted)
                    }

                    "requestOverlayPermission" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        }
                        result.success(null)
                    }

                    "startFloatingButton" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this@MainActivity)) {
                            result.success(false)
                        } else {
                            val intent = Intent(this@MainActivity, FloatingDictationService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                            result.success(true)
                        }
                    }

                    "stopFloatingButton" -> {
                        val intent = Intent(this@MainActivity, FloatingDictationService::class.java)
                        stopService(intent)
                        result.success(true)
                    }

                    "isFloatingButtonRunning" -> {
                        result.success(FloatingDictationService.isRunning)
                    }

                    "isAccessibilityServiceEnabled" -> {
                        val am = getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
                        val enabled = WhisprAccessibilityService.isRunning || (am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.any {
                            it.resolveInfo?.serviceInfo?.packageName == packageName
                        } ?: false)
                        result.success(enabled)
                    }

                    "openAccessibilitySettings" -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        result.success(null)
                    }

                    "isSmartMode" -> {
                        val prefs = getSharedPreferences("whispr_ime_prefs", MODE_PRIVATE)
                        result.success(prefs.getBoolean("smart_flying_button", true))
                    }

                    "setSmartMode" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        val prefs = getSharedPreferences("whispr_ime_prefs", MODE_PRIVATE)
                        prefs.edit().putBoolean("smart_flying_button", enabled).apply()
                        result.success(null)
                    }

                    else -> result.notImplemented()
                }
            }
        }
    }

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

    private val REQ_SPEECH_INTENT = 4001

    private fun startSpeechIntentFallback() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            startActivityForResult(intent, REQ_SPEECH_INTENT)
        } catch (_: Exception) {
            recognizerResult?.success(mapOf("error" to "no_recognizer"))
            recognizerResult = null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SPEECH_INTENT) {
            if (resultCode == RESULT_OK && data != null) {
                val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val raw = matches?.firstOrNull().orEmpty()
                if (raw.isNotEmpty()) {
                    rules = DictionaryStore.rules(this)
                    val (dictCorrected, applied) = DictionaryCorrector.apply(raw, rules)
                    val prefs = getSharedPreferences("whispr_ime_prefs", MODE_PRIVATE)
                    val lang = prefs.getString("language", "auto") ?: "auto"
                    val corrected = BilingualGrammarCorrector.correct(dictCorrected, lang)
                    DictionaryStore.appendTranscript(this, corrected, applied)
                    recognizerResult?.success(mapOf("text" to corrected))
                } else {
                    recognizerResult?.success(mapOf("error" to "no_speech"))
                }
            } else {
                recognizerResult?.success(mapOf("error" to "cancelled"))
            }
            recognizerResult = null
        }
    }

    private fun startDictation(result: MethodChannel.Result) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Stash the result first: the UI re-calls startDictation once the grant lands.
            pendingResult = result
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC
            )
            return
        }
        rules = DictionaryStore.rules(this)
        inAppFinal = ""

        recognizer?.destroy()
        recognizer = createSpeechRecognizerSafely()
        if (recognizer == null) {
            // Fall back to standard intent dialog so it never fails on any device
            recognizerResult = result
            startSpeechIntentFallback()
            return
        }
        recognizer?.setRecognitionListener(this)
        recognizerResult = result

        val prefs = getSharedPreferences("whispr_ime_prefs", MODE_PRIVATE)
        val lang = prefs.getString("language", "auto") ?: "auto"

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            if (lang == "ar") {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ar-EG")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("ar-EG", "en-US", "ar-SA", "ar-AE", "ar"))
            } else if (lang == "en") {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("en-US", "ar-EG", "en-GB", "ar"))
            }
        }
        try {
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            // If service throws startListening error, use intent dialog fallback
            startSpeechIntentFallback()
        }
    }

    private fun stopDictation(result: MethodChannel.Result) {
        recognizer?.stopListening()
        result.success(true)
    }

    private var recognizerResult: MethodChannel.Result? = null
    private var pendingResult: MethodChannel.Result? = null

    // ---- RecognitionListener (in-app dictation) ----

    override fun onResults(results: Bundle?) {
        val raw = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull().orEmpty()
        if (raw.isEmpty()) {
            recognizerResult?.success(mapOf("error" to "no_speech"))
        } else {
            val (dictCorrected, applied) = DictionaryCorrector.apply(raw, rules)
            val prefs = getSharedPreferences("whispr_ime_prefs", MODE_PRIVATE)
            val lang = prefs.getString("language", "auto") ?: "auto"
            val corrected = BilingualGrammarCorrector.correct(dictCorrected, lang)
            DictionaryStore.appendTranscript(this, corrected, applied)
            recognizerResult?.success(
                mapOf(
                    "text" to corrected,
                    "corrections" to applied.map {
                        mapOf("from" to it.from, "to" to it.to, "count" to it.count)
                    },
                )
            )
        }
        recognizerResult = null
    }

    override fun onError(error: Int) {
        val message = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "audio_recording_error"
            SpeechRecognizer.ERROR_CLIENT -> "client_error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "permission_missing"
            SpeechRecognizer.ERROR_NETWORK -> "network_error"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network_timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "no_speech"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
            SpeechRecognizer.ERROR_SERVER -> "server_error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no_speech"
            else -> "error_$error"
        }
        recognizerResult?.success(mapOf("error" to message))
        recognizerResult = null
    }

    override fun onRmsChanged(rmsdB: Float) {
        channel?.invokeMethod("level", rmsdB.toDouble())
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onEndOfSpeech() {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
    override fun onPartialResults(partialResults: Bundle?) {}

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        if (requestCode == REQ_MIC &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            // Resolve the original call first — a MethodChannel.Result answered
            // twice throws, and one left unanswered hangs the UI's await forever,
            // wedging the record button in its recording state.
            pendingResult?.success(null)
            pendingResult = null
            // Re-dispatch: the UI calls startDictation again now that the grant landed.
            channel?.invokeMethod("permissionGranted", true)
        } else {
            pendingResult?.success(mapOf("error" to "permission"))
            pendingResult = null
        }
    }

    override fun cleanUpFlutterEngine(engine: FlutterEngine) {
        recognizer?.destroy()
        super.cleanUpFlutterEngine(engine)
    }

    companion object {
        private const val CHANNEL = "dev.whispr.whispr_ai/bridge"
        private const val REQ_MIC = 4002
    }
}
