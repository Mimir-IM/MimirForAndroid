package com.revertron.mimir

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles "Mark as Read" action from message notifications.
 * Marks all messages as read in the database and cancels the corresponding
 * notification directly.
 */
class NotificationMarkReadReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifMarkReadReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val isGroup = intent.getBooleanExtra("is_group", false)
        val storage = App.app.mimirStorage!!

        if (isGroup) {
            val chatId = intent.getLongExtra("chat_id", 0)
            if (chatId == 0L) return
            storage.markAllGroupRead(chatId)
            Log.i(TAG, "Marked all messages read in group $chatId")
        } else {
            val contactId = intent.getLongExtra("contact_id", 0)
            if (contactId == 0L) return
            storage.markAllRead(contactId)
            Log.i(TAG, "Marked all messages read for contact $contactId")
        }
    }
}
