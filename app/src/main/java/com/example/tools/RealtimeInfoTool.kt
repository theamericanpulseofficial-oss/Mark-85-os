package com.example.tools

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Real-time live internet information tool.
 * Provides live weather, current news, facts, and live knowledge
 * directly to the AI agent to speak aloud without opening an external browser.
 */
class RealtimeInfoTool : PhoneTool {

    override val name: String = "live_internet_info"
    override val description: String =
        "Fetches live, up-to-the-minute real-time information from the internet, including live weather, temperature, news, people, and factual search results to answer the user verbally."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The exact subject or topic to check (e.g. 'Delhi weather', 'current gold price', 'who is Narendra Modi', 'today news headlines')."
                },
                "category": {
                    "type": "string",
                    "enum": ["weather", "knowledge", "news", "auto"],
                    "description": "The category of live internet information requested."
                }
            },
            "required": ["query"]
        }
    """.trimIndent()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult = withContext(Dispatchers.IO) {
        val (query, category) = try {
            val json = JSONObject(argumentsJson)
            Pair(
                json.optString("query", "").trim(),
                json.optString("category", "auto").lowercase().trim()
            )
        } catch (e: Exception) {
            Pair("", "auto")
        }

        if (query.isBlank()) {
            return@withContext ToolExecutionResult(
                success = false,
                message = "Empty query provided.",
                speechResponse = "Sir, what live information would you like me to look up?"
            )
        }

        val lower = query.lowercase()
        val isWeather = category == "weather" ||
                lower.contains("weather") || lower.contains("mausam") ||
                lower.contains("temperature") || lower.contains("taapmaan") ||
                lower.contains("rain") || lower.contains("barish")

        if (isWeather) {
            return@withContext fetchLiveWeather(query)
        }

        return@withContext fetchLiveKnowledge(query)
    }

    private fun fetchLiveWeather(query: String): ToolExecutionResult {
        val location = extractLocation(query)
        val formattedLocation = Uri.encode(location)
        val url = "https://wttr.in/$formattedLocation?format=%l:+%C,+Temperature:+%t,+Humidity:+%h,+Wind:+%w"

        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; MarkOS-Jarvis)")
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()?.trim() ?: ""

            if (response.isSuccessful && body.isNotBlank() && !body.contains("Unknown location", ignoreCase = true)) {
                ToolExecutionResult(
                    success = true,
                    message = "Live weather report: $body",
                    speechResponse = "Sir, live weather update for $location is $body."
                )
            } else {
                // Fallback format
                ToolExecutionResult(
                    success = false,
                    message = "Could not locate live weather for $location.",
                    speechResponse = "Sir, I couldn't fetch the current weather for $location."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Weather fetch error: ${e.message}")
            ToolExecutionResult(
                success = false,
                message = "Live weather check failed: ${e.message}",
                speechResponse = "Sir, unable to connect to the live weather satellite right now."
            )
        }
    }

    private fun fetchLiveKnowledge(query: String): ToolExecutionResult {
        // 1. First try DuckDuckGo Instant Answer API
        try {
            val encodedQuery = Uri.encode(cleanQueryTerms(query))
            val ddgUrl = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1"
            val request = Request.Builder()
                .url(ddgUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; MarkOS-Jarvis)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: ""
                val json = JSONObject(jsonStr)

                val abstractText = json.optString("AbstractText", "").trim()
                val answer = json.optString("Answer", "").trim()
                val heading = json.optString("Heading", "").trim()

                val info = when {
                    answer.isNotBlank() -> answer
                    abstractText.isNotBlank() -> abstractText
                    else -> null
                }

                if (!info.isNullOrBlank()) {
                    return ToolExecutionResult(
                        success = true,
                        message = "Live Internet Fact ($heading): $info",
                        speechResponse = "Sir, according to live network data: $info"
                    )
                }

                // Check related topics if primary abstract is blank
                val relatedTopics = json.optJSONArray("RelatedTopics")
                if (relatedTopics != null && relatedTopics.length() > 0) {
                    val firstTopic = relatedTopics.optJSONObject(0)
                    val text = firstTopic?.optString("Text", "")?.trim()
                    if (!text.isNullOrBlank()) {
                        return ToolExecutionResult(
                            success = true,
                            message = "Live Internet Result: $text",
                            speechResponse = "Sir, network records show: $text"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DuckDuckGo search error: ${e.message}")
        }

        // 2. Fallback to Wikipedia Summary API
        try {
            val wikiQuery = cleanQueryTerms(query).replace(" ", "_")
            val wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/${Uri.encode(wikiQuery)}"
            val request = Request.Builder()
                .url(wikiUrl)
                .header("User-Agent", "MarkOS-Jarvis-VoiceAssistant/2.0 (Android)")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: ""
                val json = JSONObject(jsonStr)
                val extract = json.optString("extract", "").trim()
                val title = json.optString("title", query)

                if (extract.isNotBlank()) {
                    val summary = extract.split(".").take(2).joinToString(".") + "."
                    return ToolExecutionResult(
                        success = true,
                        message = "Live Wikipedia ($title): $summary",
                        speechResponse = "Sir, according to live records for $title: $summary"
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia fetch error: ${e.message}")
        }

        return ToolExecutionResult(
            success = true,
            message = "Live internet search completed for '$query'. No instant short summary found.",
            speechResponse = "Sir, I searched the internet for $query. Would you like me to open the web results on your screen?"
        )
    }

    private fun extractLocation(query: String): String {
        val lower = query.lowercase().trim()
        val removeWords = listOf(
            "weather", "mausam", "temperature", "taapmaan", "barish", "rain",
            "kaisa hai", "kya hai", "batao", "today", "aaj", "current", "live", "me", "in", "ka", "ki"
        )
        var cleaned = lower
        for (w in removeWords) {
            cleaned = cleaned.replace(w, " ")
        }
        val candidate = cleaned.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")
        return if (candidate.length >= 2) candidate.capitalizeWords() else "Delhi"
    }

    private fun cleanQueryTerms(query: String): String {
        val lower = query.lowercase()
        val stopPhrases = listOf(
            "search karo", "google pe search karo", "dhoondo", "batao", "what is", "who is",
            "who was", "kaun hai", "kya hai", "konsa hai", "tell me about", "live", "current"
        )
        var cleaned = lower
        for (p in stopPhrases) {
            cleaned = cleaned.replace(p, " ")
        }
        val res = cleaned.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.joinToString(" ")
        return if (res.isNotBlank()) res else query
    }

    private fun String.capitalizeWords(): String =
        split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    companion object {
        private const val TAG = "RealtimeInfoTool"
    }
}
