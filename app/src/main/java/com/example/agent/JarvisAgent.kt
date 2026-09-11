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
        val liveDateTime = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy, hh:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        val effectivePrompt = if (settings.customInstructions.isNotBlank()) {
            "$basePrompt\n\nCURRENT LIVE TIME & DATE:\n- Real-Time Timestamp: $liveDateTime\n- Always accurately state the current real-time when asked about the time or date.\n\nUSER CUSTOM INSTRUCTIONS & PERSONA (OBEY STRICTLY):\n${settings.customInstructions}"
        } else {
            "$basePrompt\n\nCURRENT LIVE TIME & DATE:\n- Real-Time Timestamp: $liveDateTime\n- Always accurately state the current real-time when asked about the time or date."
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

        val cleaned = cleanSpeechCommand(trimmed)

        // Local instant time / date response
        val timeOrDateResponse = checkTimeOrDateQuery(cleaned)
        if (timeOrDateResponse != null) {
            _state.value = AgentState.SPEAKING
            _lastResponse.value = timeOrDateResponse
            conversationHistory.add(ChatMessage.user(trimmed))
            conversationHistory.add(ChatMessage.assistant(timeOrDateResponse))
            return timeOrDateResponse
        }

        // COMPOUND MULTI-STEP COMMAND FAST-PATH:
        // Handles chained multi-intent voice requests (e.g. "open chrome and open recents then clear all apps")
        val compoundCommands = parseCompoundCommands(cleaned).ifEmpty { parseCompoundCommands(trimmed) }
        if (compoundCommands.size > 1) {
            val matchedCalls = compoundCommands.mapNotNull { subCmd -> matchFastIntent(subCmd) }
            if (matchedCalls.size == compoundCommands.size) {
                _state.value = AgentState.EXECUTING
                val responses = mutableListOf<String>()
                for ((index, toolCall) in matchedCalls.withIndex()) {
                    if (index > 0) {
                        kotlinx.coroutines.delay(650L)
                    }
                    val result = toolRegistry.executeTool(toolCall.functionName, toolCall.argumentsJson)
                    val speech = result.speechResponse ?: result.message
                    responses.add(speech)
                }
                _state.value = AgentState.SPEAKING
                val combinedResponse = synthesizeCompoundResponse(responses)
                _lastResponse.value = combinedResponse
                conversationHistory.add(ChatMessage.user(trimmed))
                conversationHistory.add(ChatMessage.assistant(combinedResponse))
                return combinedResponse
            }
        }

        // ZERO-LATENCY FAST-PATH: Instant local execution for common voice commands (torch, call, apps, volume, media)
        val fastToolCall = matchFastIntent(cleaned) ?: matchFastIntent(trimmed)
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
            val speechResponses = mutableListOf<String>()

            for ((idx, toolCall) in toolCalls.withIndex()) {
                val tool = toolRegistry.getTool(toolCall.functionName)

                if (tool != null && tool.requiresConfirmation && isDeviceLocked) {
                    _state.value = AgentState.SPEAKING
                    val speech = "Sir, please unlock your device before executing this action."
                    _lastResponse.value = speech
                    return speech
                }

                if (idx > 0) {
                    kotlinx.coroutines.delay(650L)
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

                conversationHistory.add(
                    ChatMessage.tool(
                        toolCallId = toolCall.id,
                        name = toolCall.functionName,
                        content = executionResult.message
                    )
                )

                val speech = executionResult.speechResponse ?: executionResult.message
                speechResponses.add(speech)
            }

            conversationHistory.add(
                ChatMessage.assistant(
                    content = response.content ?: "",
                    toolCalls = toolCalls
                )
            )

            _state.value = AgentState.SPEAKING
            val combinedSpeech = if (speechResponses.size > 1) {
                synthesizeCompoundResponse(speechResponses)
            } else {
                speechResponses.firstOrNull() ?: response.content ?: "Action completed, sir."
            }
            _lastResponse.value = combinedSpeech
            return combinedSpeech
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
     * Cleans conversational and wake-word prefixes so local commands match reliably.
     */
    private fun cleanSpeechCommand(raw: String): String {
        var text = raw.lowercase().trim()
        val wakeWordPrefixes = listOf(
            "hey jarvis", "hi jarvis", "hello jarvis", "ok jarvis", "okay jarvis",
            "oye jarvis", "aye jarvis", "bhai jarvis", "jarvis please", "jarvis"
        )
        for (prefix in wakeWordPrefixes) {
            if (text.startsWith(prefix)) {
                text = text.removePrefix(prefix).trim()
                break
            }
        }
        text = text.trim(',', '.', '!', '?', ' ')
        val politePrefixes = listOf("please ", "zara ", "ek baar ", "bhai ", "sir ", "can you ", "could you ")
        for (polite in politePrefixes) {
            if (text.startsWith(polite)) {
                text = text.removePrefix(polite).trim()
            }
        }
        return text
    }

    /**
     * Instant local date & time responses without requiring AI model roundtrips.
     */
    private fun checkTimeOrDateQuery(text: String): String? {
        val lower = text.lowercase().trim()
        val isHindi = lower.contains("kya") || lower.contains("batao") || lower.contains("hai") ||
                lower.contains("aaj") || lower.contains("samay") || lower.contains("tarikh") ||
                lower.contains("kitne") || lower.contains("baje") || lower.contains("din")

        val isTimeQuery = lower == "time" || lower == "what time" || lower == "what time is it" ||
                lower.contains("time kya") || lower.contains("kya time") || lower.contains("kitne baje") ||
                lower.contains("samay kya") || lower.contains("kya samay") || lower.contains("current time") ||
                lower.contains("time batao") || lower.contains("kitna baja") || lower.contains("tell me the time") ||
                lower.contains("ghadi me kya") || lower == "time please"
        if (isTimeQuery) {
            val sdf = java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault())
            val formatted = sdf.format(java.util.Date())
            return if (isHindi) {
                "Sir, abhi samay $formatted hua hai."
            } else {
                "The current time is $formatted, sir."
            }
        }

        val isDateQuery = lower == "date" || lower == "what is the date" || lower == "what date is it" ||
                lower.contains("date kya") || lower.contains("kya date") || lower.contains("konsi date") ||
                lower.contains("tarikh kya") || lower.contains("kya tarikh") || lower.contains("today's date") ||
                lower.contains("aaj kya tarikh") || lower.contains("aaj konsa din") || lower.contains("aaj ka din") ||
                lower.contains("date batao") || lower.contains("what day is it") || lower.contains("aaj ki date")
        if (isDateQuery) {
            val sdf = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.getDefault())
            val formatted = sdf.format(java.util.Date())
            return if (isHindi) {
                "Sir, aaj $formatted hai."
            } else {
                "Today is $formatted, sir."
            }
        }

        return null
    }

    /**
     * Matches obvious local commands (scroll, whatsapp, apps, media, alarms, navigation)
     * instantly without waiting for cloud LLM roundtrips, reducing latency to <50ms.
     */
    private fun matchFastIntent(text: String): com.example.ai.ToolCall? {
        val lower = cleanSpeechCommand(text)

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

        // 2. Overview / Recents & Task Management Fast-Paths (Hindi + English)
        if (lower == "recents" || lower == "open recents" || lower == "recent apps" ||
            lower == "recent apps kholo" || lower.contains("recents kholo") ||
            lower.contains("recents open") || lower.contains("recent screen") ||
            lower.contains("recent app kholo") || lower == "overview"
        ) {
            return com.example.ai.ToolCall(
                id = "call_fast_recents",
                type = "function",
                functionName = "app_management",
                argumentsJson = "{\"action\":\"recents\"}"
            )
        }

        if (lower == "clear all" || lower == "clear all apps" || lower == "close all" ||
            lower == "close all apps" || lower == "dismiss all" ||
            lower.contains("clear all apps") || lower.contains("close all apps") ||
            lower.contains("clear all") || lower.contains("close all") ||
            lower.contains("sab apps band karo") || lower.contains("sab band karo") ||
            lower.contains("recents clear") || lower.contains("clear recents") ||
            lower.contains("sab hatao") || lower.contains("sab clear karo")
        ) {
            return com.example.ai.ToolCall(
                id = "call_fast_clear_all",
                type = "function",
                functionName = "app_management",
                argumentsJson = "{\"action\":\"clear_all\"}"
            )
        }

        // Close / Clear a specific app (e.g. "clear chrome", "close chrome", "chrome band karo")
        val closeMatch = Regex("""^(?:clear|close|band\s+karo)\s+([a-zA-Z0-9\s]+)$|^([a-zA-Z0-9\s]+)\s+(?:band\s+karo|clear\s+karo|hatao)$""").find(lower)
        if (closeMatch != null) {
            val app = (closeMatch.groupValues[1].ifBlank { closeMatch.groupValues[2] }).trim()
            if (app.isNotBlank() && app != "all" && app != "all apps" && app != "recents" && app != "overview") {
                return com.example.ai.ToolCall(
                    id = "call_fast_close_app",
                    type = "function",
                    functionName = "app_management",
                    argumentsJson = "{\"action\":\"close_app\",\"appName\":\"$app\"}"
                )
            }
        }

        // Delete / Uninstall an app (e.g. "delete chrome", "delete koi app", "uninstall whatsapp", "whatsapp delete karo")
        val deleteMatch = Regex("""^(?:delete|uninstall)\s+(?:app\s+)?([a-zA-Z0-9\s]+)$|^([a-zA-Z0-9\s]+)\s+(?:delete\s+karo|uninstall\s+karo|delete\s+kar\s+do)$""").find(lower)
        if (deleteMatch != null) {
            val app = (deleteMatch.groupValues[1].ifBlank { deleteMatch.groupValues[2] }).trim()
            if (app.isNotBlank()) {
                return com.example.ai.ToolCall(
                    id = "call_fast_uninstall_app",
                    type = "function",
                    functionName = "app_management",
                    argumentsJson = "{\"action\":\"uninstall_app\",\"appName\":\"$app\"}"
                )
            }
        }

        // App storage / cache settings (e.g. "clear data of chrome", "chrome ka data clear karo")
        val clearDataMatch = Regex("""(?:clear\s+data\s+of|data\s+clear\s+karo)\s+([a-zA-Z0-9\s]+)|([a-zA-Z0-9\s]+)\s+ka\s+data\s+clear""").find(lower)
        if (clearDataMatch != null) {
            val app = (clearDataMatch.groupValues[1].ifBlank { clearDataMatch.groupValues[2] }).trim()
            if (app.isNotBlank()) {
                return com.example.ai.ToolCall(
                    id = "call_fast_app_settings",
                    type = "function",
                    functionName = "app_management",
                    argumentsJson = "{\"action\":\"app_settings\",\"appName\":\"$app\"}"
                )
            }
        }

        // 3. Direct App Launch (e.g. WhatsApp, YouTube, Camera, Chrome, Spotify, Settings, or any app)
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
            "maps" to "Google Maps",
            "calculator" to "Calculator"
        )
        for ((trigger, appName) in appMap) {
            if (lower == "open $trigger" || lower.startsWith("open $trigger") ||
                lower.contains("$trigger kholo") || lower.contains("$trigger khol") ||
                lower.contains("$trigger open") || lower.contains("$trigger chalao") ||
                lower.contains("$trigger app")
            ) {
                return com.example.ai.ToolCall(
                    id = "call_fast_open_app",
                    type = "function",
                    functionName = "open_app",
                    argumentsJson = "{\"appName\":\"$appName\"}"
                )
            }
        }

        // Dynamic launch for any other app (e.g. "open telegram", "open netflix", "snapchat kholo")
        val genericOpenMatch = Regex("""^(?:open|launch)\s+([a-zA-Z0-9\s]+)$|^([a-zA-Z0-9\s]+)\s+(?:kholo|khol|chalao|open\s+karo)$""").find(lower)
        if (genericOpenMatch != null) {
            val target = (genericOpenMatch.groupValues[1].ifBlank { genericOpenMatch.groupValues[2] }).trim()
            if (target.isNotBlank() && target != "recents" && target != "overview" && target != "wifi" && target != "torch") {
                val resolvedName = appMap[target] ?: target
                return com.example.ai.ToolCall(
                    id = "call_fast_open_generic_app",
                    type = "function",
                    functionName = "open_app",
                    argumentsJson = "{\"appName\":\"$resolvedName\"}"
                )
            }
        }

        // 3. Media Controls (play, pause, next, stop music)
        if (lower == "pause" || lower.contains("pause music") || lower.contains("stop music") ||
            lower.contains("gana roko") || lower.contains("gana band") || lower == "roko" ||
            lower.contains("music roko") || lower.contains("music band")
        ) {
            return com.example.ai.ToolCall(
                id = "call_fast_media_pause",
                type = "function",
                functionName = "control_media",
                argumentsJson = "{\"action\":\"pause\"}"
            )
        }
        if (lower == "play" || lower.contains("play music") || lower.contains("gana bajao") ||
            lower.contains("gana chalao") || lower.contains("music chalao") || lower.contains("resume")
        ) {
            return com.example.ai.ToolCall(
                id = "call_fast_media_play",
                type = "function",
                functionName = "control_media",
                argumentsJson = "{\"action\":\"play\"}"
            )
        }
        if (lower == "next song" || lower.contains("next song") || lower.contains("agla gana") ||
            lower.contains("change song") || lower.contains("next track")
        ) {
            return com.example.ai.ToolCall(
                id = "call_fast_media_next",
                type = "function",
                functionName = "control_media",
                argumentsJson = "{\"action\":\"next\"}"
            )
        }

        // 4. Flashlight / Torch Control (Instant Hardware Access)
        val isTorchOn = lower.contains("torch on") || lower.contains("torch jala") || lower.contains("torch chalu") ||
                lower.contains("flashlight on") || lower.contains("flash on") || lower.contains("light on") ||
                lower.contains("light jala") || lower.contains("light chalu")
        if (isTorchOn) {
            return com.example.ai.ToolCall(
                id = "call_fast_torch_on",
                type = "function",
                functionName = "control_flashlight",
                argumentsJson = "{\"action\":\"on\"}"
            )
        }

        val isTorchOff = lower.contains("torch off") || lower.contains("torch band") || lower.contains("torch bujha") ||
                lower.contains("flashlight off") || lower.contains("flash off") || lower.contains("light off") ||
                lower.contains("light band") || lower.contains("light bujha")
        if (isTorchOff) {
            return com.example.ai.ToolCall(
                id = "call_fast_torch_off",
                type = "function",
                functionName = "control_flashlight",
                argumentsJson = "{\"action\":\"off\"}"
            )
        }

        // 5. Battery & Volume Device Controls
        if (lower.contains("battery") || lower.contains("charge kitna") || lower.contains("charging kitni")) {
            return com.example.ai.ToolCall(
                id = "call_fast_battery",
                type = "function",
                functionName = "control_device",
                argumentsJson = "{\"command\":\"battery_status\"}"
            )
        }
        if (lower.contains("volume up") || lower.contains("volume badh") || lower.contains("awaz badh") ||
            lower.contains("awaz bada") || lower.contains("sound badh") || lower.contains("volume tej") ||
            lower.contains("awaz tej") || lower.contains("volume full") || lower.contains("full volume")
        ) {
            return com.example.ai.ToolCall(
                id = "call_fast_volume_up",
                type = "function",
                functionName = "control_device",
                argumentsJson = "{\"command\":\"volume_up\"}"
            )
        }
        if (lower.contains("volume down") || lower.contains("volume kam") || lower.contains("awaz kam") ||
            lower.contains("sound kam") || lower.contains("volume dheere") || lower.contains("awaz dheere")
        ) {
            return com.example.ai.ToolCall(
                id = "call_fast_volume_down",
                type = "function",
                functionName = "control_device",
                argumentsJson = "{\"command\":\"volume_down\"}"
            )
        }
        if (lower.contains("mute") || lower.contains("silent") || lower.contains("awaz band") || lower.contains("shant karo")) {
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
        val callKoRegex = Regex("""^(.+?)\s+ko\s+(?:call karo|call lagao|phone lagao|phone karo|call|phone)$""")
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

        // 8. Real-Time Live Weather Fast-Path
        val isWeatherRequest = lower.contains("mausam kaisa") || lower.contains("aaj ka mausam") ||
                lower.contains("weather kaisa") || lower.contains("weather kya") ||
                lower.contains("temperature kitna") || lower.contains("taapmaan kitna") ||
                (lower.contains("weather") && (lower.contains("today") || lower.contains("now") || lower.contains("batao")))
        if (isWeatherRequest) {
            return com.example.ai.ToolCall(
                id = "call_fast_weather",
                type = "function",
                functionName = "live_internet_info",
                argumentsJson = "{\"query\":\"$text\",\"category\":\"weather\"}"
            )
        }

        // 9. Screenshot Fast-Path (Hindi + English)
        val isScreenshotRequest = lower.contains("screenshot") || lower.contains("screen shot") ||
                lower.contains("screen capture") || lower.contains("screen photo") ||
                lower.contains("photo lo") || lower.contains("screenshot le") || lower.contains("screenshot lo")
        if (isScreenshotRequest) {
            return com.example.ai.ToolCall(
                id = "call_fast_screenshot",
                type = "function",
                functionName = "take_screenshot",
                argumentsJson = "{}"
            )
        }

        // 10. Wi-Fi Control Fast-Path (Hindi + English)
        val isWifiOn = lower.contains("wifi on") || lower.contains("wi-fi on") ||
                lower.contains("wifi chalu") || lower.contains("wifi start") || lower.contains("turn on wifi")
        if (isWifiOn) {
            return com.example.ai.ToolCall(
                id = "call_fast_wifi_on",
                type = "function",
                functionName = "control_wifi",
                argumentsJson = "{\"action\":\"on\"}"
            )
        }

        val isWifiOff = lower.contains("wifi off") || lower.contains("wi-fi off") ||
                lower.contains("wifi band") || lower.contains("turn off wifi")
        if (isWifiOff) {
            return com.example.ai.ToolCall(
                id = "call_fast_wifi_off",
                type = "function",
                functionName = "control_wifi",
                argumentsJson = "{\"action\":\"off\"}"
            )
        }

        val isWifiToggle = lower.contains("wifi toggle") || lower == "wifi" || lower == "wi-fi"
        if (isWifiToggle) {
            return com.example.ai.ToolCall(
                id = "call_fast_wifi_toggle",
                type = "function",
                functionName = "control_wifi",
                argumentsJson = "{\"action\":\"toggle\"}"
            )
        }

        return null
    }

    /**
     * Splits compound / chained voice commands joined by 'and', 'then', 'aur', 'fir', 'ke baad'
     * into discrete atomic sub-command strings.
     */
    private fun parseCompoundCommands(transcript: String): List<String> {
        val lower = cleanSpeechCommand(transcript).lowercase().trim()
        if (lower.isBlank()) return emptyList()

        // Delimiters: "and then", "then", "and", "aur fir", "fir", "aur", "ke baad", "uske baad"
        val delimiterRegex = Regex("""\s+(?:and\s+then|then|and|aur\s+fir|aur|fir|ke\s+baad|uske\s+baad)\s+""")
        if (!delimiterRegex.containsMatchIn(lower)) {
            return emptyList()
        }

        val parts = lower.split(delimiterRegex).map { it.trim() }.filter { it.isNotBlank() }
        return if (parts.size >= 2) parts else emptyList()
    }

    /**
     * Synthesizes multiple sequential tool responses into a single natural spoken phrase.
     */
    private fun synthesizeCompoundResponse(responses: List<String>): String {
        if (responses.isEmpty()) return "Actions completed, sir."
        if (responses.size == 1) return responses.first()

        // Clean redundant greetings and endings from intermediate steps
        val cleanedSteps = responses.map { r ->
            r.replace(Regex("""^(?:Sir,\s*|Opening\s+|Launching\s+)""", RegexOption.IGNORE_CASE), "")
                .replace(Regex(""",?\s*sir\.?$""", RegexOption.IGNORE_CASE), "")
                .trim()
                .replaceFirstChar { it.lowercase() }
        }

        val allExceptLast = cleanedSteps.dropLast(1).joinToString(", ")
        val last = cleanedSteps.last()
        return "Executed: $allExceptLast, and $last, sir."
    }

    companion object {
        private const val TAG = "JarvisAgent"

        private const val SYSTEM_PROMPT = """
You are MARK 85 OS (J.A.R.V.I.S.), an advanced, polite, and razor-sharp AI phone operating assistant inspired by Tony Stark's Mark 85 nanotech armor systems.
Address the user respectfully as "sir" when appropriate.
Keep your verbal spoken answers brief, natural, elegant, confident, and actionable.

CRITICAL IDENTITY RULE: You are JARVIS / MARK 85 OS. NEVER refer to yourself as a robot or say "As a robot" or "I am an AI robot" or speak mechanically when you cannot perform an action. If an action fails, is restricted, or requires permission, speak calmly and naturally in your own persona: "Sir, I am unable to perform that right now" or "Sir, please grant accessibility permission to proceed."

You have access to Android tools to control the user's device:
- app_management: Manages tasks and overview: opens recents overview ('recents'), clears all recent apps ('clear_all'), closes an app ('close_app'), uninstalls/deletes an app ('uninstall_app'), or opens app storage settings ('app_settings')
- take_screenshot: Takes a full screenshot of the device screen immediately
- control_wifi: Turns Wi-Fi on or off, toggles Wi-Fi state (action: 'on', 'off', 'toggle', 'status')
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
- live_internet_info: Fetches live real-time internet data including current weather, temperature, news, score, people, and live facts. Use this to verbally answer live internet queries.
- send_message: Sends a WhatsApp message or SMS to a contact name or number, and automatically presses send via accessibility service
- scroll_screen: Scroll the current open screen up/down/left/right or navigate back/home

Guidelines:
1. When asked to perform an action on the phone, invoke the corresponding tool immediately.
2. If user provides a multi-step command (e.g. "open chrome and open recents then clear all apps"), invoke all appropriate tools sequentially in the exact requested order.
3. For calls ("call Papa", "call Rahul"), call them immediately with phone_call. Do not stop to repeat the number.
4. For search requests, extract and analyze the true keyword topic before invoking web_search.
5. When asked for live information, current weather, or internet facts, invoke live_internet_info and speak the result directly.
6. Keep spoken responses concise for voice output without Markdown bullet lists or symbols.
"""
    }
}
