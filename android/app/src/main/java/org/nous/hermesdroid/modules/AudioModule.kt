package org.nous.hermesdroid.modules

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

class AudioModule(private val context: Context) {

    suspend fun recordAudio(durationSeconds: Int = 5): Result<File> = withContext(Dispatchers.IO) {
        val perm = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (perm != PackageManager.PERMISSION_GRANTED) {
            return@withContext Result.failure(SecurityException("Microphone permission not granted."))
        }

        val clampedDuration = durationSeconds.coerceIn(1, 60)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesDroid::AudioRecord")
        wakeLock.acquire((clampedDuration + 3) * 1000L)

        val outputFile = File(context.cacheDir, "hermes_record_${System.currentTimeMillis()}.m4a")

        var recorder: MediaRecorder? = null
        try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(outputFile.absolutePath)
                prepare()
                start()
            }

            delay(clampedDuration * 1000L)

            try {
                recorder.stop()
            } catch (e: Exception) {
                // Ignore stop error if short
            }

            if (outputFile.exists() && outputFile.length() > 0) {
                Result.success(outputFile)
            } else {
                Result.failure(IllegalStateException("Recorded audio file is empty."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { recorder?.release() } catch (e: Exception) {}
            if (wakeLock.isHeld) wakeLock.release()
        }
    }
}
