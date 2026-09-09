package com.example.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Tool for sending messages (SMS or WhatsApp).
 * Resolves contact names to phone numbers automatically.
 */
class SendMessageTool : PhoneTool {
    override val name: String = "send_message"
    override val description: String =
        "Sends or drafts a message via WhatsApp or SMS to a contact name (e.g. 'Papa', 'Rahul') or phone number."
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
                    "description": "Contact name (e.g. 'Papa', 'Rahul') or phone number with country code"
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
        val (platform, rawRecipient, message) = try {
            val json = JSONObject(argumentsJson)
            Triple(
                json.optString("platform", "whatsapp").lowercase(),
                json.optString("recipient", "").trim(),
                json.optString("message", "").trim()
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

        var resolvedPhone = ""
        var displayName = rawRecipient

        if (rawRecipient.isNotBlank()) {
            // Check if it's already digits
            val digitsOnly = rawRecipient.replace(Regex("[^0-9]"), "")
            if (digitsOnly.length >= 10 && !rawRecipient.any { it.isLetter() }) {
                resolvedPhone = digitsOnly
            } else {
                // Try resolving contact name
                val contactNumber = resolveContactNumber(context, rawRecipient)
                if (contactNumber != null) {
                    resolvedPhone = contactNumber.replace(Regex("[^0-9]"), "")
                }
            }
        }

        return try {
            if (platform == "whatsapp") {
                val uri = if (resolvedPhone.isNotBlank() && resolvedPhone.length >= 10) {
                    Uri.parse("https://api.whatsapp.com/send?phone=$resolvedPhone&text=${Uri.encode(message)}")
                } else {
                    Uri.parse("whatsapp://send?text=${Uri.encode(message)}")
                }

                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.whatsapp")
                }

                try {
                    AppLauncherHelper.launchIntent(context, intent, "WhatsApp to $displayName")
                    ToolExecutionResult(
                        success = true,
                        message = "Opened WhatsApp for $displayName with message: \"$message\"",
                        speechResponse = if (displayName.isNotBlank()) "Sending WhatsApp message to $displayName, sir." else "Opening WhatsApp to send your message, sir."
                    )
                } catch (e: Exception) {
                    // Fallback to generic share or SMS if WhatsApp not installed
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, message)
                    }
                    val chooser = Intent.createChooser(sendIntent, "Send Message")
                    AppLauncherHelper.launchIntent(context, chooser, "Message")
                    ToolExecutionResult(
                        success = true,
                        message = "WhatsApp not available, opened message chooser.",
                        speechResponse = "Opening messaging app to send your text, sir."
                    )
                }
            } else {
                // SMS Intent
                val target = if (resolvedPhone.isNotBlank()) resolvedPhone else displayName.replace(Regex("[^0-9+]"), "")
                val smsUri = if (target.isNotBlank()) Uri.parse("smsto:$target") else Uri.parse("smsto:")
                val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                    putExtra("sms_body", message)
                }
                AppLauncherHelper.launchIntent(context, intent, "SMS to $displayName")

                ToolExecutionResult(
                    success = true,
                    message = "Opened SMS composer for $displayName with text: \"$message\"",
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

    private fun resolveContactNumber(context: Context, nameQuery: String): String? {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return null

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$nameQuery%")

        return try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIndex >= 0) it.getString(numberIndex) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
