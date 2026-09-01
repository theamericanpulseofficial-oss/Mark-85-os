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

    private val _testState = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)
    val testState: StateFlow<ConnectionTestState> = _testState.asStateFlow()

    fun toggleAssistant(start: Boolean) {
        if (start) {
            JarvisForegroundService.startService(context)
        } else {
            JarvisForegroundService.stopService(context)
        }
    }

    fun triggerManualListen() {
        JarvisForegroundService.triggerManualListen(context)
    }

    fun saveSettings(newSettings: JarvisSettings) {
        _settings.value = newSettings
        JarvisSettings.save(context, newSettings)
    }

    fun testNvidiaConnection() {
        val currentSettings = _settings.value
        if (currentSettings.nvidiaApiKey.isBlank()) {
            _testState.value = ConnectionTestState.Error("Please enter your NVIDIA API key first.")
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

    fun clearAllStoredKeys() {
        val prefs = SecurePreferencesHelper(context)
        prefs.clearAllSecrets()
        val defaultSettings = JarvisSettings()
        _settings.value = defaultSettings
        JarvisSettings.save(context, defaultSettings)
    }
}
