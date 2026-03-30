package com.revertron.mimir

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput

/**
 * Handles inline replies from message notifications.
 * Sends the reply via ConnectionService (which handles DB storage)
 * and dismisses the notification.
 */
class NotificationReplyReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "NotifReplyReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        val replyText = remoteInput?.getCharSequence(NotificationHelper.EXTRA_REPLY)?.toString()
        if (replyText.isNullOrBlank()) return

        val isGroup = intent.getBooleanExtra("is_group", false)

        if (isGroup) {
            handleGroupReply(context, intent, replyText)
        } else {
            handleDirectReply(context, intent, replyText)
        }
    }

    private fun handleDirectReply(context: Context, intent: Intent, replyText: String) {
        val pubkey = intent.getByteArrayExtra("pubkey") ?: return
        val contactId = intent.getLongExtra("contact_id", 0)
        if (contactId == 0L) return

        // Send via ConnectionService (it handles DB storage internally)
        val serviceIntent = Intent(context, ConnectionService::class.java).apply {
            putExtra("command", "send")
            putExtra("pubkey", pubkey)
            putExtra("message", replyText)
            putExtra("replyTo", 0L)
        }
        context.startService(serviceIntent)

        Log.i(TAG, "Reply sent to contact $contactId")

        // Dismiss the notification and clear the cache
        NotificationHelper.cancelMessageNotification(context, contactId)
    }

    private fun handleGroupReply(context: Context, intent: Intent, replyText: String) {
        val chatId = intent.getLongExtra("chat_id", 0)
        if (chatId == 0L) return

        val storage = App.app.storage
        val messageBytes = replyText.toByteArray()
        val sendTime = getUtcTimeMs()
        val guid = storage.generateGuid(sendTime, messageBytes)

        // Store locally first (ConnectionService's mediator_send expects this)
        val myPubkey = storage.getMyPubKey()
        storage.addGroupMessage(chatId, null, guid, myPubkey, sendTime, 0, false, messageBytes, 0L)

        // Send via ConnectionService
        val serviceIntent = Intent(context, ConnectionService::class.java).apply {
            putExtra("command", "mediator_send")
            putExtra("chat_id", chatId)
            putExtra("guid", guid)
            putExtra("reply_to", 0L)
            putExtra("send_time", sendTime)
            putExtra("type", 0)
            putExtra("message", replyText)
        }
        context.startService(serviceIntent)

        Log.i(TAG, "Reply sent to group $chatId")

        // Dismiss the notification and clear the cache
        NotificationHelper.cancelGroupChatNotification(context, chatId)
    }
}
