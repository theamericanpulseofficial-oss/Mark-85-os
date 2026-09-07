package com.example.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import org.json.JSONObject

/**
 * Tool for sending messages (SMS or WhatsApp).
 * Allows sending directly via SMS if permission granted, or launching WhatsApp/SMS prefilled chat.
 */
class SendMessageTool : PhoneTool {
    override val name: String = "send_message"
    override val description: String =
        "Sends or drafts a message via WhatsApp or SMS to a contact or phone number."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "platform": {
                    "type": "string",
                    "enum": ["whatsapp", "sms"],
                    "description": "Platform to send message through: 'whatsapp' or 'sms'"
                },
                "recipient": {
                    "type": "string",
                    "description": "Phone number with country code (e.g. '+919876543210') or contact name"
                },
                "message": {
                    "type": "string",
                    "description": "Text message content to send"
                }
            },
            "required": ["message"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val (platform, recipient, message) = try {
            val json = JSONObject(argumentsJson)
            Triple(
                json.optString("platform", "whatsapp").lowercase(),
                json.optString("recipient", ""),
                json.optString("message", "")
            )
        } catch (e: Exception) {
            Triple("whatsapp", "", "")
        }

        if (message.isBlank()) {
            return ToolExecutionResult(
                success = false,
                message = "Message text is empty.",
                speechResponse = "Sir, what message should I send?"
            )
        }

        return try {
            if (platform == "whatsapp") {
                // If clean phone number is provided (e.g. +91... or digits), open direct chat
                val digits = recipient.replace(Regex("[^0-9]"), "")
                val uri = if (digits.length >= 10) {
                    Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=${Uri.encode(message)}")
                } else {
                    Uri.parse("whatsapp://send?text=${Uri.encode(message)}")
                }

                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.whatsapp")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    context.startActivity(intent)
                    ToolExecutionResult(
                        success = true,
                        message = "Opened WhatsApp with message: \"$message\"",
                        speechResponse = if (recipient.isNotBlank()) "Opening WhatsApp chat for $recipient with your message, sir." else "Opening WhatsApp to send your message, sir."
                    )
                } catch (e: Exception) {
                    // Fallback to generic share or SMS if WhatsApp not installed
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, message)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "Send Message").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    ToolExecutionResult(
                        success = true,
                        message = "WhatsApp not available, opened message chooser.",
                        speechResponse = "Opening messaging app to send your text, sir."
                    )
                }
            } else {
                // SMS Intent
                val smsUri = if (recipient.isNotBlank()) {
                    val cleanNumber = recipient.replace(Regex("[^0-9+]"), "")
                    Uri.parse("smsto:$cleanNumber")
                } else {
                    Uri.parse("smsto:")
                }
                val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                    putExtra("sms_body", message)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)

                ToolExecutionResult(
                    success = true,
                    message = "Opened SMS composer with text: \"$message\"",
                    speechResponse = "Opening SMS composer with your message, sir."
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                message = "Failed to send message: ${e.message}",
                speechResponse = "Sir, I encountered an error preparing the message."
            )
        }
    }
}
