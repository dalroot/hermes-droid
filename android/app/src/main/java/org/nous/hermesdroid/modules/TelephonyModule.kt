package org.nous.hermesdroid.modules

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import org.nous.hermesdroid.model.SmsItem

class TelephonyModule(private val context: Context) {

    fun readSms(limit: Int = 10, senderFilter: String? = null, queryFilter: String? = null): List<SmsItem> {
        val list = mutableListOf<SmsItem>()
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ
        )

        val selectionList = mutableListOf<String>()
        val selectionArgs = mutableListOf<String>()

        if (!senderFilter.isNullOrBlank()) {
            selectionList.add("${Telephony.Sms.ADDRESS} LIKE ?")
            selectionArgs.add("%$senderFilter%")
        }

        if (!queryFilter.isNullOrBlank()) {
            selectionList.add("${Telephony.Sms.BODY} LIKE ?")
            selectionArgs.add("%$queryFilter%")
        }

        val selection = if (selectionList.isNotEmpty()) selectionList.joinToString(" AND ") else null
        val sortOrder = "${Telephony.Sms.DATE} DESC LIMIT $limit"

        try {
            val cursor = context.contentResolver.query(
                uri,
                projection,
                selection,
                selectionArgs.toTypedArray(),
                sortOrder
            )

            cursor?.use {
                val idIdx = it.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIdx = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIdx = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val readIdx = it.getColumnIndexOrThrow(Telephony.Sms.READ)

                while (it.moveToNext()) {
                    list.add(
                        SmsItem(
                            id = it.getString(idIdx) ?: "",
                            sender = it.getString(addressIdx) ?: "",
                            body = it.getString(bodyIdx) ?: "",
                            timestamp = it.getLong(dateIdx),
                            isRead = it.getInt(readIdx) == 1
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun sendSms(recipient: String, message: String): Boolean {
        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
                ?: SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(recipient, null, message, null, null)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun makeCall(phoneNumber: String, direct: Boolean = false): Boolean {
        return try {
            val action = if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL
            val intent = Intent(action).apply {
                data = Uri.parse("tel:$phoneNumber")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
