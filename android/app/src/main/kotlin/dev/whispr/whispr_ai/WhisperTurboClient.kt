package dev.whispr.whispr_ai

import android.content.Context
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Client for Whisper Large-v3-Turbo (Groq / OpenAI API).
 * Provides lightning-fast (0.15s), human-accuracy speech recognition
 * for conversational Arabic (Egyptian, Gulf, Levantine, Maghrebi), English,
 * and mixed bilingual speech with automated native punctuation.
 */
object WhisperTurboClient {

    private const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/audio/transcriptions"

    fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences("whispr_ime_prefs", Context.MODE_PRIVATE)
        return prefs.getString("whisper_api_key", "")?.trim().orEmpty()
    }

    fun setApiKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences("whispr_ime_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("whisper_api_key", key.trim()).apply()
    }

    fun hasApiKey(context: Context): Boolean {
        return getApiKey(context).isNotEmpty()
    }

    fun getModelPreference(context: Context): String {
        val prefs = context.getSharedPreferences("whispr_ime_prefs", Context.MODE_PRIVATE)
        return prefs.getString("model_preference", "whisper_turbo") ?: "whisper_turbo"
    }

    fun setModelPreference(context: Context, pref: String) {
        val prefs = context.getSharedPreferences("whispr_ime_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("model_preference", pref).apply()
    }

    sealed class Result {
        data class Success(val text: String) : Result()
        data class Error(val message: String, val code: Int? = null) : Result()
    }

    /**
     * Uploads the recorded audio file to Whisper Large-v3-Turbo and returns the transcribed text.
     */
    fun transcribe(
        context: Context,
        audioFile: File,
        language: String = "auto"
    ): Result {
        val apiKey = getApiKey(context)
        if (apiKey.isEmpty()) {
            return Result.Error("API Key missing. Enter your free Groq key in Whispr AI.")
        }

        val isGroq = apiKey.startsWith("gsk_") || !apiKey.startsWith("sk-")
        val endpointUrl = if (isGroq) GROQ_ENDPOINT else OPENAI_ENDPOINT
        val model = if (isGroq) "whisper-large-v3-turbo" else "whisper-1"

        val boundary = "===WhisprBoundary${System.currentTimeMillis()}==="
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        var conn: HttpsURLConnection? = null
        try {
            val url = URL(endpointUrl)
            conn = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                doInput = true
                doOutput = true
                useCaches = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }

            val dos = DataOutputStream(conn.outputStream)

            // 1. Model field
            writeFormField(dos, boundary, "model", model)

            // 2. Response format
            writeFormField(dos, boundary, "response_format", "json")

            // 3. Prompt for punctuation & Egyptian/Arabic dialect clarity
            val prompt = if (language == "ar") {
                "كتابة باللغة العربية والعامية المصرية مع علامات الترقيم الصحيحة والهمزات وبدون أخطاء إملائية."
            } else if (language == "en") {
                "Punctuate properly with capital letters, commas, and questions."
            } else {
                "Arabic, Egyptian Arabic, English, natural conversation, punctuated."
            }
            writeFormField(dos, boundary, "prompt", prompt)

            // 4. Language parameter (if specified)
            if (language == "ar") {
                writeFormField(dos, boundary, "language", "ar")
            } else if (language == "en") {
                writeFormField(dos, boundary, "language", "en")
            }

            // 5. Audio file
            dos.writeBytes("$twoHyphens$boundary$lineEnd")
            dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"$lineEnd")
            dos.writeBytes("Content-Type: audio/m4a$lineEnd")
            dos.writeBytes(lineEnd)

            val fileInputStream = FileInputStream(audioFile)
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                dos.write(buffer, 0, bytesRead)
            }
            fileInputStream.close()
            dos.writeBytes(lineEnd)

            // Final boundary
            dos.writeBytes("$twoHyphens$boundary$twoHyphens$lineEnd")
            dos.flush()
            dos.close()

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseStr)
                val text = json.optString("text", "").trim()
                return Result.Success(text)
            } else {
                val errorStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                val errMessage = try {
                    val errJson = JSONObject(errorStr)
                    errJson.optJSONObject("error")?.optString("message", errorStr) ?: errorStr
                } catch (_: Exception) {
                    errorStr
                }
                return Result.Error(errMessage, responseCode)
            }
        } catch (e: Exception) {
            return Result.Error(e.localizedMessage ?: "Connection failed")
        } finally {
            conn?.disconnect()
            try {
                audioFile.delete()
            } catch (_: Exception) {}
        }
    }

    /**
     * Verifies if the provided API key is valid.
     */
    fun testKey(apiKey: String): Result {
        if (apiKey.isBlank()) return Result.Error("Key is empty")
        val isGroq = apiKey.startsWith("gsk_") || !apiKey.startsWith("sk-")
        val testUrl = if (isGroq) "https://api.groq.com/openai/v1/models" else "https://api.openai.com/v1/models"
        var conn: HttpsURLConnection? = null
        return try {
            val url = URL(testUrl)
            conn = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            }
            val code = conn.responseCode
            if (code == 200) {
                Result.Success("Key verified successfully! (المفتاح صالح وجاهز للاستخدام)")
            } else {
                val errorStr = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                Result.Error("Verification failed ($code): $errorStr", code)
            }
        } catch (e: Exception) {
            Result.Error(e.localizedMessage ?: "Network error testing key")
        } finally {
            conn?.disconnect()
        }
    }

    private fun writeFormField(dos: DataOutputStream, boundary: String, name: String, value: String) {
        val lineEnd = "\r\n"
        val twoHyphens = "--"
        dos.writeBytes("$twoHyphens$boundary$lineEnd")
        dos.writeBytes("Content-Disposition: form-data; name=\"$name\"$lineEnd")
        dos.writeBytes("Content-Type: text/plain; charset=UTF-8$lineEnd")
        dos.writeBytes(lineEnd)
        dos.write(value.toByteArray(Charsets.UTF_8))
        dos.writeBytes(lineEnd)
    }
}
