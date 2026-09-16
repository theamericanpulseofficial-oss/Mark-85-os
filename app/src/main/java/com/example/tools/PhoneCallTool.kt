package com.example.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Tool for placing direct, immediate phone calls to a contact or phone number without opening dial codes.
 */
class PhoneCallTool : PhoneTool {
    override val name: String = "phone_call"
    override val description: String =
        "Directly places a phone call to a contact name (e.g. 'Papa', 'Mom') or a phone number without showing dial codes."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "phoneNumber": {
                    "type": "string",
                    "description": "Phone number digits to call, e.g. '+919876543210' or '9876543210'"
                },
                "contactName": {
                    "type": "string",
                    "description": "Name of the contact being called, e.g. 'Papa', 'Mom', 'Rahul'"
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

        // Clean target contact name of Hindi postpositions / filler words ("Papa ko" -> "Papa")
        if (targetName.isNotBlank()) {
            targetName = targetName
                .replace(Regex("""(?i)\s+(?:ko|ji|par|pe|sahab|sir)$"""), "")
                .replace(Regex("""(?i)^(?:call|phone|to)\s+"""), "")
                .trim()
        }

        // If we have a contact name, resolve their actual numeric phone number from contacts
        if (targetPhone.isBlank() && targetName.isNotBlank()) {
            val resolvedPhone = resolveContactNumber(context, targetName)
            if (resolvedPhone != null) {
                targetPhone = resolvedPhone
            }
        }

        // Sanitize phone number to digits and leading plus sign only
        val cleanPhone = targetPhone.replace(Regex("[^0-9+]"), "").trim()

        // If after contact resolution we still have no numeric phone number, DO NOT dial words/codes!
        if (cleanPhone.isBlank()) {
            val notFoundTarget = if (targetName.isNotBlank()) targetName else "the requested contact"
            return ToolExecutionResult(
                success = false,
                message = "Contact '$notFoundTarget' was not found or has no phone number in device contacts.",
                speechResponse = "Sir, $notFoundTarget ka phone number contacts me nahi mila."
            )
        }

        val displayName = if (targetName.isNotBlank()) targetName else cleanPhone

        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        return try {
            val callUri = Uri.parse("tel:$cleanPhone")
            val intent = if (hasCallPermission) {
                Intent(Intent.ACTION_CALL, callUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                Intent(Intent.ACTION_DIAL, callUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }

            // Launch directly with NEW_TASK flag for background & foreground reliability
            var launched = false
            try {
                context.startActivity(intent)
                launched = true
            } catch (e: Exception) {
                Log.w(TAG, "Direct startActivity failed, trying AppLauncherHelper: ${e.message}")
            }

            if (!launched) {
                launched = AppLauncherHelper.launchIntent(context, intent, "Direct Call to $displayName")
            }

            if (launched) {
                val speech = if (hasCallPermission) {
                    "$displayName ko call lagaya ja raha hai, sir."
                } else {
                    "$displayName ko dial kiya ja raha hai, sir."
                }
                ToolExecutionResult(
                    success = true,
                    message = "Direct call initiated to $displayName ($cleanPhone).",
                    speechResponse = speech
                )
            } else {
                ToolExecutionResult(
                    success = false,
                    message = "Could not start call intent.",
                    speechResponse = "Sir, call connect nahi ho payi."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error placing call: ${e.message}", e)
            ToolExecutionResult(
                success = false,
                message = "Failed to place call: ${e.message}",
                speechResponse = "Sir, call lagane me dikkat aayi."
            )
        }
    }

    /**
     * Resolves the phone number for a given name query with case-insensitivity,
     * prefix matching, and fuzzy fallback.
     */
    private fun resolveContactNumber(context: Context, nameQuery: String): String? {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "READ_CONTACTS permission not granted. Cannot query contacts.")
            return null
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val cleanQuery = nameQuery.trim()

        try {
            // 1. Exact or prefix match
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$cleanQuery%")

            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, ${ContactsContract.CommonDataKinds.Phone.STARRED} DESC"
            )

            var bestNumber: String? = null
            var exactMatchNumber: String? = null

            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val name = if (nameIndex >= 0) it.getString(nameIndex) ?: "" else ""
                    val number = if (numberIndex >= 0) it.getString(numberIndex) ?: "" else ""

                    if (number.isNotBlank()) {
                        if (name.equals(cleanQuery, ignoreCase = true)) {
                            exactMatchNumber = number
                            break
                        }
                        if (bestNumber == null) {
                            bestNumber = number
                        }
                    }
                }
            }

            return exactMatchNumber ?: bestNumber
        } catch (e: Exception) {
            Log.e(TAG, "Error querying contacts: ${e.message}", e)
            return null
        }
    }

    companion object {
        private const val TAG = "PhoneCallTool"
    }
}
