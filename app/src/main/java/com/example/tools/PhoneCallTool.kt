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
 * Tool for immediately placing phone calls or dialing numbers directly without interruptions.
 */
class PhoneCallTool : PhoneTool {
    override val name: String = "phone_call"
    override val description: String =
        "Directly places a phone call to a contact name (e.g. 'Papa', 'Mom') or a phone number without blocking."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "phoneNumber": {
                    "type": "string",
                    "description": "Phone number or digits to call, e.g. '+919876543210' or digits"
                },
                "contactName": {
                    "type": "string",
                    "description": "Optional name of the contact being called, e.g. 'Papa', 'Mom', 'Rahul'"
                }
            }
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val (rawPhone, rawContact) = try {
            val json = JSONObject(argumentsJson)
            Pair(
                json.optString("phoneNumber", "").trim(),
                json.optString("contactName", "").trim()
            )
        } catch (e: Exception) {
            Pair("", "")
        }

        var targetName = rawContact
        var targetPhone = rawPhone

        // If phone number contains letters (e.g. user passed "Papa" in phoneNumber parameter)
        if (targetName.isBlank() && targetPhone.any { it.isLetter() }) {
            targetName = targetPhone
            targetPhone = ""
        }

        // If we have a contact name, try to resolve their actual phone number from device contacts
        if (targetPhone.isBlank() && targetName.isNotBlank()) {
            val resolvedPhone = resolveContactNumber(context, targetName)
            if (resolvedPhone != null) {
                targetPhone = resolvedPhone
            }
        }

        if (targetPhone.isBlank() && targetName.isBlank()) {
            return ToolExecutionResult(
                success = false,
                message = "Phone number or contact was not provided.",
                speechResponse = "Sir, who would you like me to call?"
            )
        }

        val displayName = if (targetName.isNotBlank()) targetName else targetPhone
        val numberToDial = if (targetPhone.isNotBlank()) targetPhone else targetName

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            val intent = if (hasCallPermission) {
                Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(numberToDial)}"))
            } else {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(numberToDial)}"))
            }.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            ToolExecutionResult(
                success = true,
                message = "Placing direct call to $displayName ($numberToDial).",
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
