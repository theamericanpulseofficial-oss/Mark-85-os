package com.example.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Tool for making phone calls or dialing numbers.
 * Requires user confirmation before placing sensitive direct calls.
 */
class PhoneCallTool : PhoneTool {
    override val name: String = "phone_call"
    override val description: String =
        "Places a phone call or opens the phone dialer with the specified contact or phone number."
    override val requiresConfirmation: Boolean = true

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "phoneNumber": {
                    "type": "string",
                    "description": "Phone number or digits to call, e.g. '+1234567890'"
                },
                "contactName": {
                    "type": "string",
                    "description": "Optional name of the contact being called, e.g. 'Mom'"
                },
                "isConfirmed": {
                    "type": "boolean",
                    "description": "True if the user has verbally or explicitly confirmed making the call"
                }
            },
            "required": ["phoneNumber"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val (phoneNumber, contactName, isConfirmed) = try {
            val json = JSONObject(argumentsJson)
            Triple(
                json.optString("phoneNumber", ""),
                json.optString("contactName", ""),
                json.optBoolean("isConfirmed", false)
            )
        } catch (e: Exception) {
            Triple("", "", false)
        }

        if (phoneNumber.isBlank()) {
            return ToolExecutionResult(
                success = false,
                message = "Phone number was not provided.",
                speechResponse = "Sir, who would you like me to call?"
            )
        }

        val displayName = if (contactName.isNotBlank()) contactName else phoneNumber

        // If not confirmed yet, ask user confirmation
        if (!isConfirmed) {
            return ToolExecutionResult(
                success = false,
                requiresUserConfirmation = true,
                pendingActionPayload = argumentsJson,
                message = "Confirmation required before calling $displayName.",
                speechResponse = "Should I call $displayName at $phoneNumber, sir?"
            )
        }

        // Action confirmed: Execute call intent
        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            val intent = if (hasCallPermission) {
                Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(phoneNumber)}"))
            } else {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phoneNumber)}"))
            }.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            ToolExecutionResult(
                success = true,
                message = "Calling $displayName ($phoneNumber).",
                speechResponse = "Calling $displayName now, sir."
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                message = "Failed to place call: ${e.message}",
                speechResponse = "Sir, I was unable to place the call."
            )
        }
    }
}
