package dev.whispr.whispr_ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

                    "startDictation" -> startDictation(result)

                    "openImeSettings" -> {
                        startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
                        result.success(null)
                    }

                    else -> result.notImplemented()
                }
            }
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
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            result.success(mapOf("error" to "no_recognizer"))
            return
        }
        rules = DictionaryStore.rules(this)
        inAppFinal = ""
        recognizer?.destroy()
        recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        recognizer?.setRecognitionListener(this)
        recognizerResult = result
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer?.startListening(intent)
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
            val cleaned = raw.trim().replace(Regex("\\s+"), " ")
            val (corrected, applied) = DictionaryCorrector.apply(cleaned, rules)
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
        recognizerResult?.success(mapOf("error" to "error_$error"))
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
