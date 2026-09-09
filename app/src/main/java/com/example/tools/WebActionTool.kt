package com.example.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * Tool for performing intelligent, analyzed web and video searches (Google, YouTube).
 * Automatically refines raw spoken sentences into optimized keyword queries.
 */
class WebActionTool : PhoneTool {
    override val name: String = "web_search"
    override val description: String =
        "Performs a refined, analyzed web search or YouTube query. Automatically filters conversational noise and searches for exact relevant keywords."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The analyzed, refined search query terms. Do not include filler words like 'search karo' or 'google pe dekh'. Extract the core subject (e.g. 'current gold price', 'how quantum computing works')."
                },
                "target": {
                    "type": "string",
                    "enum": ["web", "youtube"],
                    "description": "Where to search: general web or YouTube"
                }
            },
            "required": ["query"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val (rawQuery, target) = try {
            val json = JSONObject(argumentsJson)
            Pair(
                json.optString("query", ""),
                json.optString("target", "web").lowercase()
            )
        } catch (e: Exception) {
            Pair("", "web")
        }

        val refinedQuery = cleanAndRefineQuery(rawQuery)

        if (refinedQuery.isBlank()) {
            return ToolExecutionResult(
                success = false,
                message = "Search query is empty.",
                speechResponse = "Sir, what topic should I research for you?"
            )
        }

        return try {
            val intent = if (target == "youtube") {
                val ytUrl = "https://www.youtube.com/results?search_query=${Uri.encode(refinedQuery)}"
                Intent(Intent.ACTION_VIEW, Uri.parse(ytUrl)).apply {
                    setPackage("com.google.android.youtube")
                }
            } else {
                Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, refinedQuery)
                }
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Fallback for YouTube app not installed
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=${Uri.encode(refinedQuery)}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            }

            ToolExecutionResult(
                success = true,
                message = "Searching $target for: $refinedQuery",
                speechResponse = "Searching for $refinedQuery now, sir."
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                message = "Search failed: ${e.message}",
                speechResponse = "Sir, I couldn't perform that search."
            )
        }
    }

    companion object {
        /**
         * Analyzes and refines user query by stripping conversational filler words (Hindi + English)
         */
        fun cleanAndRefineQuery(input: String): String {
            var q = input.trim()
            // Remove leading conversational triggers
            val prefixes = listOf(
                "search karo ki", "search karo", "google pe dekh", "google par search karo",
                "google pe dhoondo", "google search", "dhoondho", "dhoondo",
                "search for", "search about", "look up", "find about", "find",
                "mujhe batao ki", "mujhe batao", "batao ki", "tell me about",
                "jarvis search", "hey jarvis search", "kuch search karo",
                "youtube pe chalao", "youtube pe search karo", "youtube pe dekh",
                "please search", "check karo"
            )

            for (prefix in prefixes) {
                if (q.lowercase().startsWith(prefix)) {
                    q = q.substring(prefix.length).trim()
                    break
                }
            }

            // Clean leading/trailing punctuation and quotes
            q = q.trim('"', '\'', ' ', '.', '?', '!', ',', ';', ':')
            return q.ifBlank { input.trim() }
        }
    }
}
