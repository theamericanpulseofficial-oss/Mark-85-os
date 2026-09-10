package com.example.agent

/**
 * Intelligent generator of instant verbal acknowledgment phrases ("filler words")
 * that are spoken immediately when a voice query is captured.
 *
 * Drastically reduces perceived voice latency from 2-3 seconds down to near zero,
 * letting the user know Mark OS / Jarvis heard their command and is executing it.
 */
object AckPhraseGenerator {

    private val actionAcksHinglish = listOf(
        "Ok sir, abhi karta hoon.",
        "Ji sir, bilkul.",
        "Haanji sir, ek second.",
        "Bilkul sir.",
        "Ji sir, kar raha hoon."
    )

    private val actionAcksEnglish = listOf(
        "Right away, sir.",
        "On it, sir.",
        "Right on it, sir.",
        "Certainly, sir."
    )

    private val queryAcksHinglish = listOf(
        "Ji sir, dekh raha hoon.",
        "Ek second sir, check karta hoon.",
        "Dekhta hoon sir...",
        "Ji sir, pata karta hoon.",
        "Haanji sir, ek second rukiye."
    )

    private val queryAcksEnglish = listOf(
        "Checking now, sir.",
        "Looking into that, sir.",
        "One moment, sir.",
        "Right away, sir."
    )

    private val conversationAcksHinglish = listOf(
        "Ji sir.",
        "Haanji sir, kahiye.",
        "Sun raha hoon sir.",
        "Ji sir, boliye."
    )

    private val conversationAcksEnglish = listOf(
        "Yes, sir.",
        "Listening, sir.",
        "At your service, sir."
    )

    /**
     * Determines if a spoken phrase is an exit, dismissal, or farewell command.
     */
    fun isExitOrClosingCommand(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower in listOf(
            "bye", "goodbye", "good bye", "alvida", "chup", "chup ho jao", "chup raho",
            "bas", "bas karo", "band karo", "stop", "khatam", "kuch nahi", "kuch nhi",
            "nothing", "nevermind", "never mind", "shanti", "cancel", "dismiss"
        ) || lower.startsWith("chup") || lower.startsWith("alvida") || lower.startsWith("bye")
    }

    /**
     * Returns a courteous farewell message when the conversation is dismissed.
     */
    fun getFarewellResponse(text: String): String {
        val lower = text.lowercase().trim()
        val isHindi = containsHindiOrHinglish(lower)
        return if (isHindi) {
            when {
                lower.contains("shukriya") || lower.contains("dhanyawad") || lower.contains("thanks") ->
                    "Aapka swagat hai sir. Jab bhi zaroorat ho, bas yaad kariyega."
                lower.contains("chup") || lower.contains("band") ->
                    "Theek hai sir, standby mode me ja raha hoon."
                else ->
                    "Alvida sir, jab zaroorat ho bas 'Hey Jarvis' keh dijiyega."
            }
        } else {
            when {
                lower.contains("thank") ->
                    "You are very welcome, sir. Standing by."
                lower.contains("stop") || lower.contains("quiet") || lower.contains("shut") ->
                    "Understood, sir. Standing by."
                else ->
                    "Goodbye, sir. Call me anytime with 'Hey Jarvis'."
            }
        }
    }

    /**
     * Analyzes the user's transcript in under 1 millisecond and returns the most suitable
     * immediate verbal acknowledgment phrase, or null if no acknowledgment is appropriate (e.g. exit/dismissal).
     */
    fun getInstantAck(transcript: String): String? {
        val lower = transcript.lowercase().trim()
        if (lower.isBlank() || isExitOrClosingCommand(lower)) {
            return null
        }

        val isHindi = containsHindiOrHinglish(lower)

        // 1. Device Action / Execution Intents
        val isAction = lower.contains("torch") || lower.contains("flashlight") ||
                lower.contains("camera") || lower.contains("call") || lower.contains("phone") ||
                lower.contains("dial") || lower.contains("message") || lower.contains("sms") ||
                lower.contains("whatsapp") || lower.contains("kholo") || lower.contains("open") ||
                lower.contains("launch") || lower.contains("chalao") || lower.contains("play") ||
                lower.contains("pause") || lower.contains("volume") || lower.contains("awaz") ||
                lower.contains("scroll") || lower.contains("alarm") || lower.contains("timer") ||
                lower.contains("bluetooth") || lower.contains("wifi") || lower.contains("setting") ||
                lower.contains("laga do") || lower.contains("lagaao") || lower.contains("on karo") ||
                lower.contains("off karo") || lower.contains("band karo")

        if (isAction) {
            return if (isHindi) {
                actionAcksHinglish.random()
            } else {
                actionAcksEnglish.random()
            }
        }

        // 2. Question / Inquiry / Real-time Internet / Knowledge Search
        val isQuery = lower.contains("weather") || lower.contains("mausam") ||
                lower.contains("barish") || lower.contains("rain") || lower.contains("temperature") ||
                lower.contains("taapmaan") || lower.contains("time") || lower.contains("samay") ||
                lower.contains("date") || lower.contains("tarikh") || lower.contains("search") ||
                lower.contains("dhoondo") || lower.contains("google") || lower.contains("check") ||
                lower.contains("news") || lower.contains("samachar") || lower.contains("khabar") ||
                lower.contains("score") || lower.contains("match") || lower.contains("price") ||
                lower.contains("rate") || lower.contains("kya") || lower.contains("kaun") ||
                lower.contains("kab") || lower.contains("kahan") || lower.contains("kaisa") ||
                lower.contains("kyun") || lower.contains("kaise") || lower.contains("who") ||
                lower.contains("what") || lower.contains("where") || lower.contains("when") ||
                lower.contains("why") || lower.contains("how") || lower.contains("meaning") ||
                lower.contains("batao") || lower.contains("tell me")

        if (isQuery) {
            return if (isHindi) {
                queryAcksHinglish.random()
            } else {
                queryAcksEnglish.random()
            }
        }

        // 3. Conversational / Greetings / Ambient
        return if (isHindi) {
            conversationAcksHinglish.random()
        } else {
            conversationAcksEnglish.random()
        }
    }

    private fun containsHindiOrHinglish(text: String): Boolean {
        val hindiKeywords = listOf(
            "karo", "karna", "kholo", "batao", "bataiye", "kya", "kaun", "kab", "kahan",
            "kaisa", "kaisi", "kaise", "kyun", "hai", "hain", "hoon", "nahi", "nhi",
            "aaj", "kal", "abhi", "mausam", "samay", "tarikh", "awaz", "chalao", "lagao",
            "sun", "suno", "ji", "ha", "haan", "theek", "shukriya", "dhanyawad", "yaar"
        )
        return hindiKeywords.any { text.contains(it) }
    }
}
