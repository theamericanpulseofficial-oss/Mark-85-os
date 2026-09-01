package com.example.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Tool for looking up contact information (phone numbers, names) in the Android Contacts provider.
 */
class ContactsTool : PhoneTool {
    override val name: String = "search_contact"
    override val description: String =
        "Finds a contact's phone number from the device address book by contact name (e.g. 'Mom', 'John')."
    override val requiresConfirmation: Boolean = false

    override val parametersJson: String = """
        {
            "type": "object",
            "properties": {
                "name": {
                    "type": "string",
                    "description": "Name of the person or contact to search for"
                }
            },
            "required": ["name"]
        }
    """.trimIndent()

    override suspend fun execute(context: Context, argumentsJson: String): ToolExecutionResult {
        val queryName = try {
            val json = JSONObject(argumentsJson)
            json.optString("name", "")
        } catch (e: Exception) {
            ""
        }

        if (queryName.isBlank()) {
            return ToolExecutionResult(
                success = false,
                message = "Contact name was not specified.",
                speechResponse = "Sir, which contact should I look up?"
            )
        }

        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return ToolExecutionResult(
                success = false,
                message = "READ_CONTACTS permission not granted.",
                speechResponse = "Sir, I require Contacts permission to search your address book."
            )
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$queryName%")

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
                    val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                    val contactName = if (nameIndex >= 0) it.getString(nameIndex) else queryName
                    val phoneNumber = if (numberIndex >= 0) it.getString(numberIndex) else ""

                    if (phoneNumber.isNotBlank()) {
                        return ToolExecutionResult(
                            success = true,
                            message = "Found contact $contactName with phone number $phoneNumber",
                            speechResponse = "Found $contactName's number: $phoneNumber"
                        )
                    }
                }
            }

            ToolExecutionResult(
                success = false,
                message = "No contact found matching '$queryName'.",
                speechResponse = "Sir, I couldn't find anyone named $queryName in your contacts."
            )
        } catch (e: Exception) {
            ToolExecutionResult(
                success = false,
                message = "Failed to query contacts: ${e.message}",
                speechResponse = "Sir, I encountered an error searching your contacts."
            )
        }
    }
}
