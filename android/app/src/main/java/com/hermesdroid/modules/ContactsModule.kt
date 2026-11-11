package com.hermesdroid.modules

import android.content.Context
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

class ContactsModule(private val context: Context) {

    fun searchContacts(query: String = "", limit: Int = 20): JSONArray {
        val result = JSONArray()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )

        val (selection, selectionArgs) = if (query.isNotBlank()) {
            val s = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            s to arrayOf("%$query%", "%$query%")
        } else {
            null to null
        }

        // On Android 15, do NOT include LIMIT in sortOrder string
        val sortOrder = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"

        try {
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            cursor?.use {
                val nameIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val name = it.getString(nameIdx) ?: "Unnamed"
                    val number = it.getString(numIdx) ?: ""
                    val obj = JSONObject().apply {
                        put("name", name)
                        put("number", number)
                    }
                    result.put(obj)
                    count++
                }
            }
        } catch (e: SecurityException) {
            val errObj = JSONObject().apply {
                put("error", "Permission READ_CONTACTS is denied. Grant it in Hermes Droid -> Access.")
            }
            result.put(errObj)
        } catch (e: Exception) {
            val errObj = JSONObject().apply {
                put("error", "Contacts query failed: ${e.message}")
            }
            result.put(errObj)
        }
        return result
    }
}
