package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.agent.AgentState
import com.example.ai.NvidiaProvider
import com.example.security.SecurePreferencesHelper
import com.example.service.JarvisForegroundService
import com.example.settings.JarvisSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data class Success(val message: String) : ConnectionTestState
    data class Error(val message: String) : ConnectionTestState
}

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private val _settings = MutableStateFlow(JarvisSettings.load(context))
    val settings: StateFlow<JarvisSettings> = _settings.asStateFlow()

    val isServiceRunning: StateFlow<Boolean> = JarvisForegroundService.isRunning
    val agentState: StateFlow<AgentState> = JarvisForegroundService.agentStateFlow
    val liveTranscript: StateFlow<String> = JarvisForegroundService.liveTranscript
    val liveResponse: StateFlow<String> = JarvisForegroundService.liveResponse

    private val _testState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val testState: StateFlow<ConnectionTestState> = _testState.asStateFlow()

    private val _ttsTestState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val ttsTestState: StateFlow<ConnectionTestState> = _ttsTestState.asStateFlow()

    fun toggleAssistant(start: Boolean) {
        if (start) {
            JarvisForegroundService.startService(context)
        } else {
            JarvisForegroundService.stopService(context)
        }
    }

    fun startService() {
        toggleAssistant(true)
    }

    fun stopService() {
        toggleAssistant(false)
    }

    fun triggerManualListen() {
        JarvisForegroundService.triggerManualListen(context)
    }

    fun sendTextCommand(text: String) {
        JarvisForegroundService.sendTextCommand(context, text)
    }

    fun saveSettings(newSettings: JarvisSettings) {
        _settings.value = newSettings
        JarvisSettings.save(context, newSettings)
        try {
            JarvisForegroundService.reloadSettings(context)
        } catch (e: Exception) {
            // Service may not be running yet
        }
    }

    fun testNvidiaConnection() {
        val currentSettings = _settings.value
        if (currentSettings.nvidiaApiKey.isBlank()) {
            _testState.value = ConnectionTestState.Error("Please enter your AI API key first.")
            return
        }

        _testState.value = ConnectionTestState.Testing
        viewModelScope.launch {
            val provider = NvidiaProvider(debugLogging = currentSettings.debugLogging)
            val result = provider.testConnection(
                apiKey = currentSettings.nvidiaApiKey,
                model = currentSettings.nvidiaModel,
                endpoint = currentSettings.nvidiaEndpoint,
                timeoutSeconds = currentSettings.timeoutSeconds
            )

            result.fold(
                onSuccess = { reply ->
                    _testState.value = ConnectionTestState.Success(reply)
                },
                onFailure = { error ->
                    _testState.value = ConnectionTestState.Error(
                        error.message ?: "Connection test failed."
                    )
                }
            )
        }
    }

    fun resetTestState() {
        _testState.value = ConnectionTestState.Idle
    }

    fun testInworldVoice(customPhrase: String? = null) {
        val currentSettings = _settings.value
        if (currentSettings.inworldApiKey.isBlank()) {
            _ttsTestState.value = ConnectionTestState.Error("Please enter your Inworld / Kokoro API key first.")
            return
        }

        _ttsTestState.value = ConnectionTestState.Testing
        viewModelScope.launch {
            val phrase = customPhrase ?: "Greetings, sir. Inworld neural voice pipeline is active and nominal."
            val result = com.example.audio.InworldKokoroTtsEngine.testVoiceSynthesis(
                context = context,
                settings = currentSettings,
                testText = phrase
            )

            result.fold(
                onSuccess = { reply ->
                    _ttsTestState.value = ConnectionTestState.Success(reply)
                },
                onFailure = { error ->
                    _ttsTestState.value = ConnectionTestState.Error(
                        error.message ?: "Voice synthesis test failed."
                    )
                }
            )
        }
    }

    fun resetTtsTestState() {
        _ttsTestState.value = ConnectionTestState.Idle
    }

    fun clearAllStoredKeys() {
        val prefs = SecurePreferencesHelper(context)
        prefs.clearAllSecrets()
        val defaultSettings = JarvisSettings()
        _settings.value = defaultSettings
        JarvisSettings.save(context, defaultSettings)
    }
}
