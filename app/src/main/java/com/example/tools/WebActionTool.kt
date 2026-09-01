package com.example.tools

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * Tool for performing web and video searches (Google, YouTube).
 */
class WebActionTool : PhoneTool {
    override val name: String = "web_search"
    override val description: String =
        "Performs a web search or YouTube query for specific terms, topics, or videos."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "The search terms or query, e.g. 'latest space news' or 'quantum computing'"
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
        val (query, target) = try {
            val json = JSONObject(argumentsJson)
            Pair(
                json.optString("query", ""),
                json.optString("target", "web").lowercase()
            )
        } catch (e: Exception) {
            Pair("", "web")
        }

        if (query.isBlank()) {
            return ToolExecutionResult(
                success = false,
                message = "Search query is empty.",
                speechResponse = "Sir, what would you like me to look up?"
            )
        }

        return try {
            val intent = if (target == "youtube") {
                val ytUrl = "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
                Intent(Intent.ACTION_VIEW, Uri.parse(ytUrl)).apply {
                    setPackage("com.google.android.youtube")
                }
            } else {
                Intent(Intent.ACTION_WEB_SEARCH).apply {
                    putExtra(SearchManager.QUERY, query)
                }
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // Fallback for YouTube app not installed
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            }

            ToolExecutionResult(
                success = true,
                message = "Searching $target for: $query",
                speechResponse = "Searching for $query now, sir."
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                message = "Search failed: ${e.message}",
                speechResponse = "Sir, I couldn't perform that search."
            )
        }
    }
}
