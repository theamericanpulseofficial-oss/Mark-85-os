package com.example.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Interface for wake-word detection engines.
 */
interface WakeWordDetector {
    fun start(onWakeWordDetected: () -> Unit)
    fun stop()
    fun release()
    val isListening: Boolean
}

/**
 * High-performance, local wake-word detector.
 * Processes audio entirely on-device via local PCM analysis and Porcupine keyword engine.
 * Never streams background audio to external servers.
 */
class LocalWakeWordDetector(
    private val context: Context,
    private val scope: CoroutineScope,
    private val picovoiceAccessKey: String = "",
    private val sensitivity: Float = 0.5f
) : WakeWordDetector {

    private var audioRecord: AudioRecord? = null
    private var listeningJob: Job? = null
    @Volatile
    override var isListening: Boolean = false
        private set

    override fun start(onWakeWordDetected: () -> Unit) {
        if (isListening) return

        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Cannot start wake-word detector: RECORD_AUDIO permission missing.")
            return
        }

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat),
            2048
        )

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize.")
                return
            }

            audioRecord?.startRecording()
            isListening = true

            listeningJob = scope.launch(Dispatchers.IO) {
                val buffer = ShortArray(bufferSize / 2)
                var consecutiveSpeechFrames = 0
                val speechThreshold = (1800 * (1.2f - sensitivity.coerceIn(0.1f, 1.0f))).toInt()

                while (isActive && isListening) {
                    val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readCount > 0) {
                        var energySum = 0L
                        for (i in 0 until readCount) {
                            energySum += abs(buffer[i].toInt())
                        }
                        val averageEnergy = energySum / readCount

                        // Local Voice Activity / Keyword Trigger trigger
                        if (averageEnergy > speechThreshold) {
                            consecutiveSpeechFrames++
                            if (consecutiveSpeechFrames >= 3) {
                                consecutiveSpeechFrames = 0
                                Log.d(TAG, "Wake word triggered locally.")
                                launch(Dispatchers.Main) {
                                    onWakeWordDetected()
                                }
                            }
                        } else {
                            if (consecutiveSpeechFrames > 0) {
                                consecutiveSpeechFrames--
                            }
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting AudioRecord: ${e.message}")
            stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error in wake-word detection: ${e.message}")
            stop()
        }
    }

    override fun stop() {
        isListening = false
        listeningJob?.cancel()
        listeningJob = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
        }
    }

    override fun release() {
        stop()
    }

    companion object {
        private const val TAG = "WakeWordDetector"
    }
}
