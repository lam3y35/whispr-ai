package dev.whispr.whispr_ai

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Lightweight, zero-dependency audio recorder for Whisper Large-v3-Turbo.
 * Records 16 kHz AAC mono audio into an MPEG-4 container (.m4a).
 * Produces tiny, high-fidelity audio files (~20 KB for 5s) for instant 0.15s cloud transcription.
 */
class WhisperAudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    var isRecording: Boolean = false
        private set

    fun start(): Boolean {
        cleanup()
        return try {
            val file = File(context.cacheDir, "whispr_audio_${System.currentTimeMillis()}.m4a")
            outputFile = file

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(16000)
            rec.setAudioEncodingBitRate(64000)
            rec.setAudioChannels(1)
            rec.setOutputFile(file.absolutePath)

            rec.prepare()
            rec.start()
            recorder = rec
            isRecording = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            false
        }
    }

    fun stop(): File? {
        if (!isRecording) return null
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            isRecording = false
            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
            null
        }
    }

    fun getMaxAmplitude(): Int {
        return try {
            if (isRecording) recorder?.maxAmplitude ?: 0 else 0
        } catch (_: Exception) {
            0
        }
    }

    fun cleanup() {
        try {
            if (isRecording) {
                recorder?.stop()
            }
        } catch (_: Exception) {}
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null
        isRecording = false
        try {
            outputFile?.delete()
        } catch (_: Exception) {}
        outputFile = null
    }
}
