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

    fun resetConversation(customPrompt: String = SYSTEM_PROMPT) {
        conversationHistory.clear()
        conversationHistory.add(ChatMessage.system(customPrompt.ifBlank { SYSTEM_PROMPT }))
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

        val basePrompt = settings.systemPrompt.ifBlank { SYSTEM_PROMPT }
        val effectivePrompt = if (settings.customInstructions.isNotBlank()) {
            "$basePrompt\n\nUSER CUSTOM INSTRUCTIONS & PERSONA (OBEY STRICTLY):\n${settings.customInstructions}"
        } else {
            basePrompt
        }

        if (conversationHistory.isEmpty()) {
            conversationHistory.add(ChatMessage.system(effectivePrompt))
        } else {
            // Keep system prompt updated with custom user instructions
            conversationHistory[0] = ChatMessage.system(effectivePrompt)
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

        // ZERO-LATENCY FAST-PATH: Instant local execution for common voice commands (scroll, whatsapp, apps, etc.)
        val fastToolCall = matchFastIntent(trimmed)
        if (fastToolCall != null) {
            _state.value = AgentState.EXECUTING
            val result = toolRegistry.executeTool(fastToolCall.functionName, fastToolCall.argumentsJson)
            _state.value = AgentState.SPEAKING
            val responseText = result.speechResponse ?: result.message
            _lastResponse.value = responseText
            conversationHistory.add(ChatMessage.user(trimmed))
            conversationHistory.add(ChatMessage.assistant(responseText))
            return responseText
        }

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
                Log.e(TAG, "AI request failed: ${error.message}", error)
                _state.value = AgentState.ERROR
                val voiceError = if (settings.nvidiaApiKey.isBlank()) {
                    "Sir, please configure your AI API key in the settings."
                } else {
                    val raw = error.message ?: ""
                    when {
                        raw.contains("401") || raw.contains("Invalid API key") ->
                            "Sir, your API key appears to be invalid or unauthorized."
                        raw.contains("429") || raw.contains("Rate limit") || raw.contains("quota") ->
                            "Sir, the neural server rate limit or quota has been reached."
                        raw.contains("404") ->
                            "Sir, the requested model was not found on this endpoint."
                        raw.contains("timeout") || raw.contains("timed out") ->
                            "Sir, the neural server timed out. Please try your request again."
                        else ->
                            "Sir, I encountered an issue connecting to the AI server: ${raw.take(60)}."
                    }
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

            // Fallback tool call extraction: if model outputted tool instructions or JSON in text
            val detectedTool = tryParseToolFromText(textReply)
            if (detectedTool != null) {
                _state.value = AgentState.EXECUTING
                val tool = toolRegistry.getTool(detectedTool.functionName)
                if (tool != null && tool.requiresConfirmation && isDeviceLocked) {
                    _state.value = AgentState.SPEAKING
                    val speech = "Sir, please unlock your device before executing this action."
                    _lastResponse.value = speech
                    return speech
                }
                val executionResult = toolRegistry.executeTool(
                    name = detectedTool.functionName,
                    argumentsJson = detectedTool.argumentsJson
                )
                val speech = executionResult.speechResponse ?: "Action completed, sir."
                conversationHistory.add(ChatMessage.assistant(speech))
                _state.value = AgentState.SPEAKING
                _lastResponse.value = speech
                return speech
            }

            conversationHistory.add(ChatMessage.assistant(textReply))
            _state.value = AgentState.SPEAKING
            _lastResponse.value = textReply
            return textReply
        }
    }

    private fun tryParseToolFromText(text: String): com.example.ai.ToolCall? {
        val trimmed = text.trim()
        val jsonPattern = Regex("""\{[\s\S]*?"(?:action|tool|function)"\s*:\s*"([a-zA-Z0-9_]+)"[\s\S]*?\}""")
        val match = jsonPattern.find(trimmed)
        if (match != null) {
            try {
                val json = JSONObject(match.value)
                val actionName = json.optString("action").ifBlank { json.optString("tool").ifBlank { json.optString("function") } }
                val args = json.optJSONObject("args")?.toString()
                    ?: json.optJSONObject("parameters")?.toString()
                    ?: json.optJSONObject("arguments")?.toString()
                    ?: json.toString()
                if (actionName.isNotBlank() && toolRegistry.getTool(actionName) != null) {
                    return com.example.ai.ToolCall(
                        id = "call_text_${System.currentTimeMillis()}",
                        type = "function",
                        functionName = actionName,
                        argumentsJson = args
                    )
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }
        }
        return null
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

    /**
     * Matches obvious local commands (scroll, whatsapp, apps, media, alarms, navigation)
     * instantly without waiting for cloud LLM roundtrips, reducing latency to <50ms.
     */
    private fun matchFastIntent(text: String): com.example.ai.ToolCall? {
        val lower = text.lowercase().trim()

        // 1. Screen Scrolling & Navigation (Hindi + English)
        if (lower == "scroll" || lower == "scroll kar" || lower == "scroll down" ||
            lower.contains("niche scroll") || lower.contains("neeche scroll") ||
            lower.contains("scroll niche") || lower.contains("scroll neeche") ||
            lower.contains("scroll down kar") || lower.contains("niche karo") ||
            lower.contains("page scroll")
        ) {
            return com.example.ai.ToolCall(
                id = "call_fast_scroll",
                type = "function",
                functionName = "scroll_screen",
                argumentsJson = "{\"direction\":\"down\"}"
            )
        }
        if (lower == "scroll up" || lower.contains("upar scroll") || lower.contains("uupal scroll") ||
            lower.contains("scroll upar") || lower.contains("upar karo")
        ) {
            return com.example.ai.ToolCall(
                id = "call_fast_scroll_up",
                type = "function",
                functionName = "scroll_screen",
                argumentsJson = "{\"direction\":\"up\"}"
            )
        }
        if (lower == "go back" || lower == "back" || lower == "back jao" || lower == "back kar" || lower == "peeche jao") {
            return com.example.ai.ToolCall(
                id = "call_fast_back",
                type = "function",
                functionName = "scroll_screen",
                argumentsJson = "{\"direction\":\"back\"}"
            )
        }
        if (lower == "go home" || lower == "home screen" || lower == "home screen jao") {
            return com.example.ai.ToolCall(
                id = "call_fast_home",
                type = "function",
                functionName = "scroll_screen",
                argumentsJson = "{\"direction\":\"home\"}"
            )
        }

        // 2. Direct App Launch (e.g. WhatsApp, YouTube, Camera, Spotify, Settings)
        val appMap = mapOf(
            "whatsapp" to "WhatsApp",
            "youtube" to "YouTube",
            "camera" to "Camera",
            "chrome" to "Chrome",
            "browser" to "Chrome",
            "spotify" to "Spotify",
            "instagram" to "Instagram",
            "settings" to "Settings",
            "gallery" to "Gallery",
            "maps" to "Google Maps"
        )
        for ((trigger, appName) in appMap) {
            if (lower == "open $trigger" || lower == "$trigger kholo" ||
                lower == "$trigger open kar" || lower == "open $trigger app" ||
                lower == "$trigger app kholo"
            ) {
                return com.example.ai.ToolCall(
                    id = "call_fast_open_app",
                    type = "function",
                    functionName = "open_app",
                    argumentsJson = "{\"appName\":\"$appName\"}"
                )
            }
        }

        // 3. Media Controls (play, pause, next, stop music)
        if (lower == "pause" || lower == "pause music" || lower == "stop music" || lower == "gana roko" || lower == "roko") {
            return com.example.ai.ToolCall(
                id = "call_fast_media_pause",
                type = "function",
                functionName = "control_media",
                argumentsJson = "{\"action\":\"pause\"}"
            )
        }
        if (lower == "play" || lower == "play music" || lower == "resume" || lower == "gana bajao" || lower == "chalao") {
            return com.example.ai.ToolCall(
                id = "call_fast_media_play",
                type = "function",
                functionName = "control_media",
                argumentsJson = "{\"action\":\"play\"}"
            )
        }
        if (lower == "next song" || lower == "next" || lower == "agla gana") {
            return com.example.ai.ToolCall(
                id = "call_fast_media_next",
                type = "function",
                functionName = "control_media",
                argumentsJson = "{\"action\":\"next\"}"
            )
        }

        // 4. Flashlight / Torch Control (Instant Hardware Access)
        if (lower == "torch on" || lower == "torch jalao" || lower == "torch chalao" ||
            lower == "torch on karo" || lower == "flashlight on" || lower == "turn on flashlight" ||
            lower == "turn on torch" || lower == "flash on" || lower == "light on" || lower == "light on karo"
        ) {
            return com.example.ai.ToolCall(
                id = "call_fast_torch_on",
                type = "function",
                functionName = "control_flashlight",
                argumentsJson = "{\"action\":\"on\"}"
            )
        }
        if (lower == "torch off" || lower == "torch band karo" || lower == "torch bujhao" ||
            lower == "torch off karo" || lower == "flashlight off" || lower == "turn off flashlight" ||
            lower == "turn off torch" || lower == "flash off" || lower == "light off" || lower == "light band karo"
        ) {
            return com.example.ai.ToolCall(
                id = "call_fast_torch_off",
                type = "function",
                functionName = "control_flashlight",
                argumentsJson = "{\"action\":\"off\"}"
            )
        }

        // 5. Battery & Volume Device Controls
        if (lower == "battery" || lower == "battery kitni hai" || lower == "check battery" ||
            lower == "battery percentage" || lower == "battery percent" || lower == "battery status" ||
            lower == "charge kitna hai"
        ) {
            return com.example.ai.ToolCall(
                id = "call_fast_battery",
                type = "function",
                functionName = "control_device",
                argumentsJson = "{\"command\":\"battery_status\"}"
            )
        }
        if (lower == "volume up" || lower == "volume badhao" || lower == "awaz badhao" || lower == "awaz badao") {
            return com.example.ai.ToolCall(
                id = "call_fast_volume_up",
                type = "function",
                functionName = "control_device",
                argumentsJson = "{\"command\":\"volume_up\"}"
            )
        }
        if (lower == "volume down" || lower == "volume kam karo" || lower == "awaz kam karo") {
            return com.example.ai.ToolCall(
                id = "call_fast_volume_down",
                type = "function",
                functionName = "control_device",
                argumentsJson = "{\"command\":\"volume_down\"}"
            )
        }
        if (lower == "mute" || lower == "silent karo" || lower == "awaz band karo") {
            return com.example.ai.ToolCall(
                id = "call_fast_mute",
                type = "function",
                functionName = "control_device",
                argumentsJson = "{\"command\":\"mute\"}"
            )
        }

        // 6. Direct Phone Call Fast-Path (Hindi + English)
        val callRegex = Regex("""^(?:call karo|call|phone lagao|phone karo)\s+(?:to\s+)?(.+)$""")
        val callMatch = callRegex.find(lower)
        if (callMatch != null) {
            val target = callMatch.groupValues[1].trim()
            if (target.isNotBlank()) {
                val isDigits = target.replace(Regex("[^0-9+]"), "").length >= 7 && !target.any { it.isLetter() }
                val args = if (isDigits) {
                    "{\"phoneNumber\":\"$target\"}"
                } else {
                    "{\"contactName\":\"$target\"}"
                }
                return com.example.ai.ToolCall(
                    id = "call_fast_phone_call",
                    type = "function",
                    functionName = "phone_call",
                    argumentsJson = args
                )
            }
        }
        val callKoRegex = Regex("""^(.+?)\s+ko\s+(?:call karo|call lagao|phone lagao|phone karo)$""")
        val callKoMatch = callKoRegex.find(lower)
        if (callKoMatch != null) {
            val target = callKoMatch.groupValues[1].trim()
            if (target.isNotBlank()) {
                return com.example.ai.ToolCall(
                    id = "call_fast_phone_call_ko",
                    type = "function",
                    functionName = "phone_call",
                    argumentsJson = "{\"contactName\":\"$target\"}"
                )
            }
        }

        // 7. Intelligent Analyzed Search Fast-Path
        val searchRegex = Regex("""^(?:search karo ki|search karo|google pe search karo|google search|dhoondo|look up)\s+(.+)$""")
        val searchMatch = searchRegex.find(lower)
        if (searchMatch != null) {
            val raw = searchMatch.groupValues[1].trim()
            val refined = com.example.tools.WebActionTool.cleanAndRefineQuery(raw)
            if (refined.isNotBlank()) {
                return com.example.ai.ToolCall(
                    id = "call_fast_search",
                    type = "function",
                    functionName = "web_search",
                    argumentsJson = "{\"query\":\"$refined\",\"target\":\"web\"}"
                )
            }
        }

        return null
    }

    companion object {
        private const val TAG = "JarvisAgent"

        private const val SYSTEM_PROMPT = """
You are MARK 85 OS (J.A.R.V.I.S.), an advanced, polite, and razor-sharp AI phone operating assistant inspired by Tony Stark's Mark 85 nanotech armor systems.
Address the user respectfully as "sir" when appropriate.
Keep your verbal spoken answers brief, natural, elegant, confident, and actionable.

You have access to Android tools to control the user's device:
- control_flashlight: Turns device flashlight / torch on or off immediately (action: 'on', 'off', 'toggle')
- control_device: Controls volume, mute, or checks battery reserve status (command: 'battery_status', 'volume_up', 'volume_down', 'mute')
- phone_call: Directly dials or calls a contact name or phone number immediately without asking redundant questions
- open_app: Launch installed apps (e.g. YouTube, Camera, WhatsApp, Spotify, Settings)
- launch_url: Open websites or web links
- set_alarm: Set alarms with hour and minute
- set_timer: Set countdown timers in seconds or minutes
- control_media: Control playback (play, pause, next, previous, stop)
- create_notification: Post reminders or alerts
- open_settings: Open Wi-Fi, Bluetooth, Display, Battery, Sound, Apps, or General settings
- search_contact: Search contacts address book by name
- web_search: Search web or YouTube. CRITICAL: Analyze the query to extract the core subject keywords rather than searching the user's raw conversational phrase.
- send_message: Send or compose a WhatsApp message or SMS to a contact name or number
- scroll_screen: Scroll the current open screen up/down/left/right or navigate back/home

Guidelines:
1. When asked to perform an action on the phone, invoke the corresponding tool immediately.
2. For calls ("call Papa", "call Rahul"), call them immediately with phone_call. Do not stop to repeat the number.
3. For search requests, extract and analyze the true keyword topic before invoking web_search.
4. Keep spoken responses concise for voice output without Markdown bullet lists or symbols.
"""
    }
}
