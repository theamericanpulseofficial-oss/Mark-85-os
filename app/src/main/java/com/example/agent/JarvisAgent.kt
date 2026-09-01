package com.example.agent

import android.app.KeyguardManager
import android.content.Context
import android.util.Log
import com.example.ai.AIProvider
import com.example.ai.ChatMessage
import com.example.ai.NvidiaProvider
import com.example.settings.JarvisSettings
import com.example.tools.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Core Agent orchestrating conversation, NVIDIA AI integration,
 * tool calling, lock-screen awareness, and sensitive action confirmations.
 */
class JarvisAgent(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    private val aiProvider: AIProvider = NvidiaProvider()
) {

    private val _state = MutableStateFlow(AgentState.OFFLINE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    private val _lastResponse = MutableStateFlow("")
    val lastResponse: StateFlow<String> = _lastResponse.asStateFlow()

    private val conversationHistory = mutableListOf<ChatMessage>()
    private var pendingConfirmationTool: Pair<String, String>? = null // (toolName, argumentsJson)

    init {
        resetConversation()
    }

    fun setState(newState: AgentState) {
        _state.value = newState
    }

    fun resetConversation() {
        conversationHistory.clear()
        conversationHistory.add(ChatMessage.system(SYSTEM_PROMPT))
        pendingConfirmationTool = null
    }

    /**
     * Processes a user speech transcript.
     * Returns the text to be spoken by TTS, and triggers any requested phone actions.
     */
    suspend fun processUserSpeech(
        transcript: String,
        settings: JarvisSettings
    ): String {
        _lastTranscript.value = transcript
        val trimmed = transcript.trim()

        if (trimmed.isBlank()) {
            return "I am listening, sir."
        }

        // Handle active pending confirmation if user answers Yes/No
        if (pendingConfirmationTool != null) {
            val (toolName, rawArgs) = pendingConfirmationTool!!
            if (isAffirmative(trimmed)) {
                pendingConfirmationTool = null
                _state.value = AgentState.EXECUTING
                val confirmedArgs = injectConfirmation(rawArgs)
                val result = toolRegistry.executeTool(toolName, confirmedArgs)
                _state.value = AgentState.SPEAKING
                val responseText = result.speechResponse ?: result.message
                _lastResponse.value = responseText
                return responseText
            } else if (isNegative(trimmed)) {
                pendingConfirmationTool = null
                _state.value = AgentState.SPEAKING
                val responseText = "Understood, sir. Action canceled."
                _lastResponse.value = responseText
                return responseText
            }
        }

        // Check if phone is locked and if user is attempting device commands
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isDeviceLocked = keyguardManager?.isKeyguardLocked == true

        _state.value = AgentState.THINKING
        conversationHistory.add(ChatMessage.user(trimmed))

        // Trim conversation history to prevent context window overflow
        if (conversationHistory.size > 15) {
            val systemMsg = conversationHistory.first()
            val recentMsgs = conversationHistory.takeLast(10)
            conversationHistory.clear()
            conversationHistory.add(systemMsg)
            conversationHistory.addAll(recentMsgs)
        }

        val tools = toolRegistry.getAllDefinitions()

        val aiResult = aiProvider.generateResponse(
            messages = conversationHistory,
            tools = tools,
            model = settings.nvidiaModel,
            apiKey = settings.nvidiaApiKey,
            endpoint = settings.nvidiaEndpoint,
            timeoutSeconds = settings.timeoutSeconds
        )

        return aiResult.fold(
            onSuccess = { response ->
                handleAIResponse(response, isDeviceLocked)
            },
            onFailure = { error ->
                Log.e(TAG, "AI request failed: ${error.message}")
                _state.value = AgentState.ERROR
                val voiceError = if (settings.nvidiaApiKey.isBlank()) {
                    "Sir, please configure your NVIDIA API key in the settings."
                } else {
                    "Sir, I can't reach the AI server right now."
                }
                _lastResponse.value = voiceError
                voiceError
            }
        )
    }

    private suspend fun handleAIResponse(
        response: com.example.ai.AIResponse,
        isDeviceLocked: Boolean
    ): String {
        val toolCalls = response.toolCalls

        if (toolCalls.isNotEmpty()) {
            _state.value = AgentState.EXECUTING
            val toolCall = toolCalls.first()
            val tool = toolRegistry.getTool(toolCall.functionName)

            if (tool != null && tool.requiresConfirmation && isDeviceLocked) {
                _state.value = AgentState.SPEAKING
                val speech = "Sir, please unlock your device before executing this action."
                _lastResponse.value = speech
                return speech
            }

            val executionResult = toolRegistry.executeTool(
                name = toolCall.functionName,
                argumentsJson = toolCall.argumentsJson
            )

            if (executionResult.requiresUserConfirmation) {
                pendingConfirmationTool = Pair(toolCall.functionName, executionResult.pendingActionPayload ?: toolCall.argumentsJson)
                _state.value = AgentState.SPEAKING
                val speech = executionResult.speechResponse ?: "Would you like me to proceed, sir?"
                _lastResponse.value = speech
                return speech
            }

            // Record assistant tool call and tool result in conversation history
            conversationHistory.add(
                ChatMessage.assistant(
                    content = response.content ?: "",
                    toolCalls = toolCalls
                )
            )
            conversationHistory.add(
                ChatMessage.tool(
                    toolCallId = toolCall.id,
                    name = toolCall.functionName,
                    content = executionResult.message
                )
            )

            _state.value = AgentState.SPEAKING
            val speech = executionResult.speechResponse
                ?: response.content
                ?: "Action completed, sir."
            _lastResponse.value = speech
            return speech
        } else {
            val textReply = response.content ?: "At your service, sir."
            conversationHistory.add(ChatMessage.assistant(textReply))
            _state.value = AgentState.SPEAKING
            _lastResponse.value = textReply
            return textReply
        }
    }

    private fun isAffirmative(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("yes") || lower.contains("proceed") || lower.contains("confirm") ||
                lower.contains("do it") || lower.contains("sure") || lower.contains("affirmative")
    }

    private fun isNegative(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("no") || lower.contains("cancel") || lower.contains("stop") ||
                lower.contains("don't") || lower.contains("abort") || lower.contains("nevermind")
    }

    private fun injectConfirmation(rawJson: String): String {
        return try {
            val json = JSONObject(rawJson)
            json.put("isConfirmed", true)
            json.toString()
        } catch (e: Exception) {
            "{\"isConfirmed\": true}"
        }
    }

    companion object {
        private const val TAG = "JarvisAgent"

        private const val SYSTEM_PROMPT = """
You are J.A.R.V.I.S., a sophisticated, highly capable, and polite AI phone assistant.
Address the user respectfully as "sir" when appropriate.
Keep your verbal spoken answers brief, natural, elegant, and actionable.

You have access to Android tools to control the user's device:
- open_app: Launch installed apps (e.g. YouTube, YouTube Studio, Camera, Spotify, Maps, Settings)
- launch_url: Open websites or web links
- phone_call: Dial or call a phone number (requires confirmation if placing a call)
- set_alarm: Set alarms with hour and minute
- set_timer: Set countdown timers in seconds or minutes
- control_media: Control playback (play, pause, next, previous, stop)
- create_notification: Post reminders or alerts
- open_settings: Open Wi-Fi, Bluetooth, Display, Battery, Sound, Apps, or General settings
- search_contact: Search contacts address book by name
- web_search: Search web or YouTube

Guidelines:
1. When asked to perform an action on the phone, invoke the corresponding tool.
2. For sensitive actions (calling, publishing, deleting, changing major settings), verify with the user first.
3. If an action requires unlocking the phone, note that politely.
4. Keep spoken responses concise for voice output without Markdown bullet lists or symbols.
"""
    }
}
