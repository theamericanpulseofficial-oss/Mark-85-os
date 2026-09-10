package com.example.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.agent.AckPhraseGenerator
import com.example.agent.AgentState
import com.example.agent.JarvisAgent
import com.example.audio.AndroidSpeechRecognizerEngine
import com.example.audio.InworldKokoroTtsEngine
import com.example.audio.SpeechToTextEngine
import com.example.audio.TextToSpeechEngine
import com.example.settings.JarvisSettings
import com.example.tools.ToolRegistry
import com.example.wakeword.LocalWakeWordDetector
import com.example.wakeword.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android Foreground Service maintaining JARVIS background operation,
 * local wake-word monitoring, voice capture pipeline, agent reasoning, and phone actions.
 */
class JarvisForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var agent: JarvisAgent
    private lateinit var ttsEngine: TextToSpeechEngine
    private lateinit var sttEngine: SpeechToTextEngine
    private var wakeWordDetector: WakeWordDetector? = null

    private var settings: JarvisSettings = JarvisSettings()

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()

        settings = JarvisSettings.load(this)
        toolRegistry = ToolRegistry(this)
        agent = JarvisAgent(this, toolRegistry)
        ttsEngine = InworldKokoroTtsEngine(
            context = this,
            settings = settings
        )
        sttEngine = AndroidSpeechRecognizerEngine(this)

        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        // Always ensure freshest settings are loaded
        settings = JarvisSettings.load(this)

        when (action) {
            ACTION_START -> {
                startForegroundWithNotification()
                _isRunning.value = true
                startStandbyWakeWord()
            }
            ACTION_STOP -> {
                stopAssistant()
            }
            ACTION_TRIGGER_LISTEN -> {
                onWakeWordTriggered()
            }
            ACTION_SEND_TEXT -> {
                val text = intent?.getStringExtra(EXTRA_TEXT) ?: ""
                if (text.isNotBlank()) {
                    if (!_isRunning.value) {
                        startForegroundWithNotification()
                        _isRunning.value = true
                    }
                    handleUserTranscript(text)
                }
            }
            ACTION_RELOAD_SETTINGS -> {
                Log.d(TAG, "Reloading settings from storage...")
                (ttsEngine as? InworldKokoroTtsEngine)?.updateSettings(settings)
                ttsEngine.setRate(settings.ttsSpeed)
                ttsEngine.setPitch(settings.ttsPitch)
            }
        }

        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification(AgentState.LISTENING_FOR_WAKE_WORD.label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startStandbyWakeWord() {
        sttEngine.stopListening()
        agent.setState(AgentState.LISTENING_FOR_WAKE_WORD)
        _agentStateFlow.value = AgentState.LISTENING_FOR_WAKE_WORD
        updateNotification(AgentState.LISTENING_FOR_WAKE_WORD.label)

        if (settings.wakeWordEnabled && _isRunning.value) {
            wakeWordDetector?.stop()
            serviceScope.launch {
                kotlinx.coroutines.delay(150)
                if (!_isRunning.value) return@launch
                wakeWordDetector = LocalWakeWordDetector(
                    context = this@JarvisForegroundService,
                    scope = serviceScope,
                    picovoiceAccessKey = settings.picovoiceAccessKey,
                    sensitivity = settings.wakeWordSensitivity,
                    filterPhoneSpeakerAudio = settings.filterPhoneSpeakerAudio
                ).apply {
                    start { directCommand ->
                        onWakeWordTriggered(directCommand)
                    }
                }
            }
        }
    }

    private fun onWakeWordTriggered(directCommand: String? = null) {
        Log.d(TAG, "Wake word detected (Jarvis / Hey Jarvis / Ok Jarvis). Direct command: $directCommand")
        triggerWakeHaptic()
        wakeWordDetector?.stop()

        if (!directCommand.isNullOrBlank()) {
            // User already spoke the command with the wake word (e.g. "Hey Jarvis torch on karo")
            handleUserTranscript(directCommand)
            return
        }

        agent.setState(AgentState.LISTENING)
        _agentStateFlow.value = AgentState.LISTENING
        updateNotification("Awake. Listening...")

        // Spoken acknowledgment prompt so user knows Jarvis woke up and is waiting for their voice command
        val wakePrompt = if (settings.customInstructions.contains("Hindi", ignoreCase = true) ||
            settings.customInstructions.contains("Hinglish", ignoreCase = true)
        ) {
            "Ji sir, boliye?"
        } else {
            "Yes, sir?"
        }

        ttsEngine.speak(wakePrompt) {
            captureUserSpeechWithWatchdog(retryAttempt = 0)
        }
    }

    private fun captureUserSpeechWithWatchdog(retryAttempt: Int) {
        if (!_isRunning.value) return
        agent.setState(AgentState.LISTENING)
        _agentStateFlow.value = AgentState.LISTENING
        updateNotification(AgentState.LISTENING.label)

        serviceScope.launch {
            kotlinx.coroutines.delay(200)
            sttEngine.startListening(
                onResult = { transcript ->
                    handleUserTranscript(transcript)
                },
                onError = { errorMsg ->
                    Log.w(TAG, "Speech capture error/timeout: $errorMsg, attempt: $retryAttempt")
                    if (retryAttempt == 0 && (errorMsg.contains("No speech", ignoreCase = true) || errorMsg.contains("time", ignoreCase = true))) {
                        // User paused: prompt once gently instead of abruptly closing
                        val reprompt = if (settings.customInstructions.contains("Hindi", ignoreCase = true) ||
                            settings.customInstructions.contains("Hinglish", ignoreCase = true)
                        ) {
                            "Sir, sun raha hoon. Boliye?"
                        } else {
                            "I'm listening, sir."
                        }
                        ttsEngine.speak(reprompt) {
                            captureUserSpeechWithWatchdog(retryAttempt = 1)
                        }
                    } else {
                        // Gracefully return to wake-word standby mode, staying alive in background
                        serviceScope.launch {
                            kotlinx.coroutines.delay(350)
                            startStandbyWakeWord()
                        }
                    }
                }
            )
        }
    }

    private fun handleUserTranscript(transcript: String) {
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) return

        serviceScope.launch {
            // Always reload the freshest settings (including new API keys and models)
            settings = JarvisSettings.load(this@JarvisForegroundService)
            (ttsEngine as? InworldKokoroTtsEngine)?.updateSettings(settings)
            _liveTranscript.value = trimmed

            // 1. Check if user wants to close / exit conversation
            if (AckPhraseGenerator.isExitOrClosingCommand(trimmed)) {
                val farewell = AckPhraseGenerator.getFarewellResponse(trimmed)
                _liveResponse.value = farewell
                agent.setState(AgentState.SPEAKING)
                _agentStateFlow.value = AgentState.SPEAKING
                updateNotification(AgentState.SPEAKING.label)
                ttsEngine.speak(farewell) {
                    serviceScope.launch {
                        kotlinx.coroutines.delay(250)
                        startStandbyWakeWord()
                    }
                }
                return@launch
            }

            // 2. Immediate Verbal Acknowledgment ("Filler Phrase") to slash perceived latency to ~0ms
            val instantAck = if (settings.instantAcknowledgment) {
                AckPhraseGenerator.getInstantAck(trimmed)
            } else null

            if (instantAck != null) {
                Log.d(TAG, "Speaking instant verbal acknowledgment: '$instantAck'")
                ttsEngine.speak(instantAck, TextToSpeech.QUEUE_FLUSH)
            }

            agent.setState(AgentState.THINKING)
            _agentStateFlow.value = AgentState.THINKING
            updateNotification(AgentState.THINKING.label)

            // Concurrently process the user speech through agent (fast intents, local time, or AI model)
            val spokenResponse = agent.processUserSpeech(trimmed, settings)
            _liveResponse.value = spokenResponse

            _agentStateFlow.value = agent.state.value
            updateNotification(agent.state.value.label)

            agent.setState(AgentState.SPEAKING)
            _agentStateFlow.value = AgentState.SPEAKING
            updateNotification(AgentState.SPEAKING.label)

            // Safety watchdog: If TTS hangs or completes without calling onComplete,
            // ensure the assistant proceeds reliably to follow-up or standby.
            val watchdogJob = serviceScope.launch {
                val waitTimeMs = (spokenResponse.length * 85L + 5000L).coerceIn(4000L, 16000L)
                kotlinx.coroutines.delay(waitTimeMs)
                if (_agentStateFlow.value == AgentState.SPEAKING) {
                    Log.d(TAG, "TTS watchdog reached timeout. Finishing speech turn.")
                    onSpeechTurnCompleted()
                }
            }

            // If an instant acknowledgment was played, queue the main response so it plays seamlessly right after it
            val queueMode = if (instantAck != null) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH

            ttsEngine.speak(spokenResponse, queueMode) {
                watchdogJob.cancel()
                onSpeechTurnCompleted()
            }
        }
    }

    private fun onSpeechTurnCompleted() {
        serviceScope.launch {
            // Brief delay to ensure phone speaker acoustic tail clears before opening mic
            kotlinx.coroutines.delay(350)
            if (settings.continuousListening && _isRunning.value) {
                startFollowUpListening()
            } else {
                startStandbyWakeWord()
            }
        }
    }

    private fun startFollowUpListening() {
        if (!_isRunning.value) return
        Log.d(TAG, "Entering continuous conversation follow-up listening mode...")
        wakeWordDetector?.stop()
        agent.setState(AgentState.LISTENING)
        _agentStateFlow.value = AgentState.LISTENING
        updateNotification("Listening... (Speak without wake word)")

        sttEngine.startListening(
            onResult = { followUpTranscript ->
                Log.d(TAG, "Follow-up speech captured: $followUpTranscript")
                if (followUpTranscript.isNotBlank()) {
                    handleUserTranscript(followUpTranscript)
                } else {
                    startStandbyWakeWord()
                }
            },
            onError = { errorMsg ->
                // User was silent or did not speak further -> smoothly close conversation back to standby wake word
                Log.d(TAG, "Follow-up silence/timeout ($errorMsg). Returning to standby wake word.")
                serviceScope.launch {
                    kotlinx.coroutines.delay(200)
                    startStandbyWakeWord()
                }
            }
        )
    }

    private fun stopAssistant() {
        _isRunning.value = false
        agent.setState(AgentState.OFFLINE)
        _agentStateFlow.value = AgentState.OFFLINE

        wakeWordDetector?.stop()
        sttEngine.stopListening()
        ttsEngine.stop()

        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, JarvisForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mark 85 OS - J.A.R.V.I.S.")
            .setContentText("Status: $statusText")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher_foreground, "DISENGAGE", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: Activity swiped/closed. Maintaining active background assistant.")
        if (_isRunning.value) {
            startForegroundWithNotification()
            acquireWakeLock()
            startStandbyWakeWord()
            val restartIntent = Intent(applicationContext, JarvisForegroundService::class.java).apply {
                action = ACTION_START
                setPackage(packageName)
            }
            val restartPendingIntent = PendingIntent.getService(
                applicationContext,
                1001,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmService = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmService?.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 500,
                        restartPendingIntent
                    )
                } else {
                    alarmService?.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 500,
                        restartPendingIntent
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule exact alarm: ${e.message}")
            }
        }
    }

    private fun triggerWakeHaptic() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(80, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(80)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    private fun updateNotification(statusText: String) {
        val notification = buildNotification(statusText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background Voice AI Phone Agent notification channel"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "JARVIS:BackgroundVoiceWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock: ${e.message}")
        } finally {
            wakeLock = null
        }
    }

    override fun onDestroy() {
        instance = null
        _isRunning.value = false
        wakeWordDetector?.release()
        sttEngine.destroy()
        ttsEngine.shutdown()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "JarvisService"
        const val CHANNEL_ID = "jarvis_assistant_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.jarvis.action.START"
        const val ACTION_STOP = "com.example.jarvis.action.STOP"
        const val ACTION_TRIGGER_LISTEN = "com.example.jarvis.action.TRIGGER_LISTEN"
        const val ACTION_SEND_TEXT = "com.example.jarvis.action.SEND_TEXT"
        const val ACTION_RELOAD_SETTINGS = "com.example.jarvis.action.RELOAD_SETTINGS"
        const val EXTRA_TEXT = "com.example.jarvis.extra.TEXT"

        private var instance: JarvisForegroundService? = null

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _agentStateFlow = MutableStateFlow(AgentState.OFFLINE)
        val agentStateFlow: StateFlow<AgentState> = _agentStateFlow.asStateFlow()

        private val _liveTranscript = MutableStateFlow("")
        val liveTranscript: StateFlow<String> = _liveTranscript.asStateFlow()

        private val _liveResponse = MutableStateFlow("")
        val liveResponse: StateFlow<String> = _liveResponse.asStateFlow()

        fun startService(context: Context) {
            val intent = Intent(context, JarvisForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, JarvisForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun triggerManualListen(context: Context) {
            val intent = Intent(context, JarvisForegroundService::class.java).apply {
                action = ACTION_TRIGGER_LISTEN
            }
            context.startService(intent)
        }

        fun sendTextCommand(context: Context, text: String) {
            val intent = Intent(context, JarvisForegroundService::class.java).apply {
                action = ACTION_SEND_TEXT
                putExtra(EXTRA_TEXT, text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun reloadSettings(context: Context) {
            val intent = Intent(context, JarvisForegroundService::class.java).apply {
                action = ACTION_RELOAD_SETTINGS
            }
            context.startService(intent)
        }
    }
}
