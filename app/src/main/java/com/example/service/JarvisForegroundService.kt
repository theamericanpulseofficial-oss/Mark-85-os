package com.example.service

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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
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
            val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startStandbyWakeWord() {
        agent.setState(AgentState.LISTENING_FOR_WAKE_WORD)
        _agentStateFlow.value = AgentState.LISTENING_FOR_WAKE_WORD
        updateNotification(AgentState.LISTENING_FOR_WAKE_WORD.label)

        if (settings.wakeWordEnabled) {
            wakeWordDetector?.stop()
            wakeWordDetector = LocalWakeWordDetector(
                context = this,
                scope = serviceScope,
                picovoiceAccessKey = settings.picovoiceAccessKey,
                sensitivity = settings.wakeWordSensitivity
            ).apply {
                start {
                    onWakeWordTriggered()
                }
            }
        }
    }

    private fun onWakeWordTriggered() {
        Log.d(TAG, "Wake word 'Hey Jarvis' detected. Starting speech capture.")
        wakeWordDetector?.stop()

        agent.setState(AgentState.LISTENING)
        _agentStateFlow.value = AgentState.LISTENING
        updateNotification(AgentState.LISTENING.label)

        // Capture voice request without any Google Assistant sound
        sttEngine.startListening(
            onResult = { transcript ->
                handleUserTranscript(transcript)
            },
            onError = { errorMsg ->
                Log.w(TAG, "Speech capture error: $errorMsg")
                startStandbyWakeWord()
            }
        )
    }

    private fun handleUserTranscript(transcript: String) {
        serviceScope.launch {
            // Always reload the freshest settings (including new API keys and models)
            settings = JarvisSettings.load(this@JarvisForegroundService)
            (ttsEngine as? InworldKokoroTtsEngine)?.updateSettings(settings)
            _liveTranscript.value = transcript
            agent.setState(AgentState.THINKING)
            _agentStateFlow.value = AgentState.THINKING
            updateNotification(AgentState.THINKING.label)

            val spokenResponse = agent.processUserSpeech(transcript, settings)
            _liveResponse.value = spokenResponse

            _agentStateFlow.value = agent.state.value
            updateNotification(agent.state.value.label)

            agent.setState(AgentState.SPEAKING)
            _agentStateFlow.value = AgentState.SPEAKING
            updateNotification(AgentState.SPEAKING.label)

            ttsEngine.speak(spokenResponse) {
                // Speech finished -> return to wake-word standby
                serviceScope.launch {
                    startStandbyWakeWord()
                }
            }
        }
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Mark 85 OS")
            .setContentText("Status: $statusText")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher_foreground, "DISENGAGE", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
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
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "JARVIS:BackgroundVoiceWakeLock"
            )?.apply {
                acquire(10 * 60 * 1000L /* 10 minutes max per active session */)
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
