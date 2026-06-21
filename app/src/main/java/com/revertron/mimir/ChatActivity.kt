package com.revertron.mimir

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.net.MSG_TYPE_REACTION
import com.revertron.mimir.net.PeerStatus
import com.revertron.mimir.ui.ChatListItem
import com.revertron.mimir.ui.Contact
import com.revertron.mimir.ui.DisplayMessage
import com.revertron.mimir.ui.MessageAdapter
import com.revertron.mimir.ui.MimirMessageProvider
import org.bouncycastle.util.encoders.Hex
import java.io.File
import java.lang.Thread.sleep


class ChatActivity : BaseChatActivity() {

    companion object {
        const val TAG = "ChatActivity"
    }

    private lateinit var contact: Contact
    private var isSavedMessages = false
    private val messageProvider by lazy { MimirMessageProvider(getMimir(), this) }

    private val peerStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == "ACTION_PEER_STATUS") {
                val statusExtra = intent.getSerializableExtra("status")
                val status: PeerStatus = when (statusExtra) {
                    is PeerStatus -> statusExtra
                    is Int -> PeerStatus.entries[statusExtra]
                    else -> return
                }
                val from = intent.getStringExtra("contact")
                if (from != null && contact.pubkey.contentEquals(Hex.decode(from))) {
                    showOnlineState(status)
                    // A dropped 1:1 connection means any in-flight download in this
                    // chat is dead (e.g. the request went out over a stale socket).
                    // Reset their spinners back to download buttons so the user can
                    // retry, rather than leaving them stuck "Downloading…".
                    if (status == PeerStatus.NotConnected || status == PeerStatus.ErrorConnecting) {
                        adapter.resetActiveDownloads()
                    }
                }
            }
        }
    }

    private val audioPlaybackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == AudioPlaybackService.ACTION_PLAYBACK_STATE_CHANGED) {
                val isPlaying = intent.getBooleanExtra(AudioPlaybackService.EXTRA_IS_PLAYING, false)
                val messageId = intent.getLongExtra(AudioPlaybackService.EXTRA_CURRENT_MESSAGE_ID, -1)
                adapter.updateAudioPlaybackState(isPlaying, messageId)
            }
        }
    }

    private val fileDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                "ACTION_FILE_DOWNLOADING" -> {
                    val guid = intent.getLongExtra("guid", 0L)
                    if (guid != 0L) adapter.markFileDownloading(guid)
                }
                "ACTION_FILE_DOWNLOADED" -> {
                    val guid = intent.getLongExtra("guid", 0L)
                    if (guid != 0L) adapter.markFileDownloaded(guid)
                }
                "ACTION_FILE_PROGRESS" -> {
                    val guid = intent.getLongExtra("guid", 0L)
                    if (guid == 0L) return
                    val bytes = intent.getLongExtra("bytes", 0L)
                    val total = intent.getLongExtra("total", 0L)
                    val isUpload = intent.getBooleanExtra("is_upload", false)
                    adapter.updateFileProgress(guid, bytes, total, isUpload)
                }
                "ACTION_FILE_UPLOADED" -> {
                    val guid = intent.getLongExtra("guid", 0L)
                    if (guid != 0L) adapter.markFileUploaded(guid)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Check if this is saved messages
        isSavedMessages = intent.getBooleanExtra("savedMessages", false)

        // Extract contact info before calling super.onCreate()
        val pubkey: ByteArray
        val id: Long
        val name = intent.getStringExtra("name").apply { if (this == null) finish() }!!

        if (isSavedMessages) {
            // For saved messages, use special ID and current user's pubkey
            id = ChatListItem.SAVED_MESSAGES_CONTACT_ID
            pubkey = getMimir().getAccount(1)?.pubkey ?: ByteArray(0)
        } else {
            // Normal contact flow
            pubkey = intent.getByteArrayExtra("pubkey").apply { if (this == null) finish() }!!
            id = getMimir().getContactId(pubkey)
        }

        val avatarPic = if (isSavedMessages) {
            ContextCompat.getDrawable(this, R.drawable.ic_saved_messages)
        } else {
            getMimir().getContact(id)?.avatar?.let { loadRoundedAvatar(this, it) }
        }
        contact = Contact(id, pubkey, name, avatarPic)

        super.onCreate(savedInstanceState)

        // Set title and avatar
        setToolbarTitle(contact.name)
        setupAvatar(contact.avatar)

        // Hide online indicator for saved messages
        if (isSavedMessages) {
            val statusImage = findViewById<AppCompatImageView>(R.id.status_image)
            statusImage?.visibility = View.GONE
        }

        // Handle shared content if any
        val sharedUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (sharedUri != null) {
            val mimeType = contentResolver.getType(sharedUri)
            when {
                mimeType?.startsWith("image/") == true -> getImageFromUri(sharedUri)
                else -> getFileFromUri(sharedUri)
            }
        } else {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                val editText = findViewById<AppCompatEditText>(R.id.message_edit)
                editText.setText(sharedText)
                editText.setSelection(sharedText.length)
            }
        }

        // Setup message list
        setupMessageList()

        // Setup empty view for saved messages
        if (isSavedMessages) {
            setupEmptyView()
        }

        // Setup broadcast receivers
        setupBroadcastReceivers()

        // Skip connection for saved messages
        if (!isSavedMessages) {
            // Show initial connection state
            showOnlineState(PeerStatus.Connecting)
            fetchStatus(this, contact.pubkey)

            // Connect to peer
            Thread {
                sleep(500)
                connect(this@ChatActivity, contact.pubkey)
            }.start()
        }

        // Handle forward mode if present
        if (intent.getBooleanExtra("FORWARD_MODE", false)) {
            handleForwardMode()
        }
    }

    // BaseChatActivity abstract method implementations

    override fun getLayoutResId(): Int = R.layout.activity_chat

    override fun getChatId(): Long {
        return if (isSavedMessages) {
            ChatListItem.SAVED_MESSAGES_CONTACT_ID
        } else {
            contact.id
        }
    }

    override fun getChatName(): String = contact.name

    override fun isGroupChat(): Boolean = false

    override fun getAvatarColorSeed(): ByteArray = contact.pubkey

    override fun createMessageAdapter(fontSize: Int): MessageAdapter {
        return MessageAdapter(
            messageProvider,
            contact.id,
            groupChat = false,
            contact.name,
            myName,
            this,
            onClickOnReply(),
            onClickOnPicture(),
            fontSize,
            onAvatarClick = null,
            onReactionsMarkedSeen = { updateReactionFabVisibility() }
        )
    }

    override fun getFirstUnreadMessageId(): Long? {
        return getMimir().getFirstUnreadId(contact.id)
    }

    override fun deleteMessageByIdOrGuid(messageId: Long, guid: Long) {
        val attachmentFileName = getMimir().deleteMessage(messageId)
        // Delete attachment files if present
        if (attachmentFileName != null) {
            File(File(filesDir, "files"), attachmentFileName).delete()
            File(File(cacheDir, "files"), attachmentFileName).delete()
        }
    }

    override fun getMessageForReply(guid: Long): Pair<String, String>? {
        val stored = getMimir().getMessageByGuid(guid) ?: return null
        val message = DisplayMessage.from(stored)
        val authorName = if (message.incoming) contact.name else myName
        return Pair(authorName, message.getText(this))
    }

    override fun getMessageFromStorage(messageId: Long): DisplayMessage? {
        val stored = getMimir().getMessage(messageId) ?: return null
        return DisplayMessage.from(stored)
    }

    override fun handleReaction(targetGuid: Long, emoji: String, currentEmoji: String?) {
        // Toggle behavior: if clicking the same emoji, remove it
        val finalEmoji = if (emoji == currentEmoji) "" else emoji

        // Create reaction message data as JSON
        val reactionData = org.json.JSONObject().apply {
            put("emoji", finalEmoji)
            put("replyTo", targetGuid)
        }.toString()

        // Generate GUID for the reaction message
        val sendTime = getUtcTime()
        val guid = getMimir().generateGuid(sendTime, reactionData.toByteArray())

        // Store reaction locally (type = 10)
        getMimir().addMessage(
            contactPubkey = contact.pubkey,
            guid = guid,
            replyTo = targetGuid,
            incoming = false,
            delivered = false,
            sendTime = sendTime,
            editTime = 0L,
            msgType = 10,
            data = reactionData.toByteArray()
        )

        // Send reaction to peer via ConnectionService
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "send")
        intent.putExtra("pubkey", contact.pubkey)
        intent.putExtra("replyTo", targetGuid)
        intent.putExtra("type", 10)
        intent.putExtra("message", reactionData)
        startService(intent)

        // Refresh the target message to show the updated reactions row
        adapter.notifyMessageChanged(targetGuid)
    }

    override fun getUserCurrentReaction(targetGuid: Long): String? {
        return messageProvider.getUserReaction(contact.id, targetGuid, false)
    }

    override fun getUnseenReactionsCount(): Int {
        return getMimir().getUnseenReactionsCount(contact.id).toInt()
    }

    override fun scrollToFirstUnseenReaction(): Boolean {
        val target = getMimir().getFirstUnseenReaction(contact.id) ?: return false
        val position = adapter.getMessageGuidPosition(target.targetGuid)
        if (position < 0) return false
        recyclerView.smoothScrollToPosition(position)
        getMimir().markReactionAsSeen(contact.id, target.reactionGuid)
        adapter.notifyMessageChanged(target.targetGuid)
        return true
    }

    override fun sendMessage(text: String, replyTo: Long) {
        val app = App.app.mimirApp ?: run {
            Log.w(TAG, "sendMessage: MimirApp not ready yet")
            return
        }
        // Detach the staged attachment on the UI thread so the trailing
        // clearAttachment() call can't delete the temp file out from under
        // the async send.
        val staged = takeAttachmentForSending()
        Thread {
            try {
                // The row is inserted in Phase 1 inside Rust and the
                // on_outgoing_message_stored callback already broadcast
                // ACTION_MESSAGE_SENT — the adapter has the pending row before
                // this thread has started on the upload/dispatch work.
                app.sendContactMessage(contact.pubkey, replyTo, text, staged?.spec)
            } catch (e: Exception) {
                Log.e(TAG, "sendContactMessage failed: ${e.message}", e)
                // Best-effort auto-reconnect on failure — same fallback as before.
                try { app.connectToContact(contact.pubkey) } catch (_: Exception) {}
            } finally {
                // Composer-owned blobs get cleaned up here regardless of send result;
                // forwarded attachments (stagedFile == null) stay on disk.
                staged?.stagedFile?.delete()
            }
        }.start()
    }

    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            when (intent.action) {
                "ACTION_MESSAGE_SENT" -> {
                    val msgId = intent.getLongExtra("message_id", 0)
                    val contactId = intent.getLongExtra("contact_id", 0)
                    val type = intent.getIntExtra("type", 0)
                    val replyTo = intent.getLongExtra("reply_to", 0L)
                    if (contactId == contact.id) {
                        onMessageSent(msgId, contactId, type, replyTo)
                    }
                }
                "ACTION_MESSAGE_RECEIVED" -> {
                    val msgId = intent.getLongExtra("message_id", 0)
                    val contactId = intent.getLongExtra("contact_id", 0)
                    val msgType = intent.getIntExtra("msg_type", 0)
                    val replyTo = intent.getLongExtra("reply_to", 0L)
                    if (contactId == contact.id) {
                        onMessageReceived(msgId, contactId, msgType, replyTo)
                    }
                }
                "ACTION_MESSAGE_DELIVERED" -> {
                    val pubkey = intent.getByteArrayExtra("pubkey") ?: return
                    val guid = intent.getLongExtra("guid", 0)
                    if (contact.pubkey.contentEquals(pubkey)) {
                        val msg = getMimir().getMessageByGuid(guid)
                        if (msg != null) {
                            onMessageDelivered(msg.id, true)
                        }
                    }
                }
            }
        }
    }

    override fun setupBroadcastReceivers() {
        LocalBroadcastManager.getInstance(this).registerReceiver(
            peerStatusReceiver,
            IntentFilter("ACTION_PEER_STATUS")
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            audioPlaybackReceiver,
            IntentFilter(AudioPlaybackService.ACTION_PLAYBACK_STATE_CHANGED)
        )
        val fileFilter = IntentFilter().apply {
            addAction("ACTION_FILE_DOWNLOADING")
            addAction("ACTION_FILE_DOWNLOADED")
            addAction("ACTION_FILE_PROGRESS")
            addAction("ACTION_FILE_UPLOADED")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(fileDownloadReceiver, fileFilter)
        val msgFilter = IntentFilter().apply {
            addAction("ACTION_MESSAGE_SENT")
            addAction("ACTION_MESSAGE_RECEIVED")
            addAction("ACTION_MESSAGE_DELIVERED")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(messageReceiver, msgFilter)
    }

    override fun onToolbarClick() {
        val intent = Intent(this, ContactActivity::class.java)
        intent.putExtra("pubkey", contact.pubkey)
        intent.putExtra("name", contact.name)
        startActivity(intent, animFromRight.toBundle())
    }

    // Menu handling

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_contact, menu)
        if (isSavedMessages) {
            menu.findItem(R.id.contact_call)?.isVisible = false
            menu.findItem(R.id.mute_contact)?.isVisible = false
            menu.findItem(R.id.block_contact)?.isVisible = false
            menu.findItem(R.id.remove_contact)?.isVisible = false
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)
        if (!isSavedMessages) {
            val isMuted = getMimir().isContactMuted(contact.id)
            menu.findItem(R.id.mute_contact)?.title = if (isMuted) {
                getString(R.string.unmute)
            } else {
                getString(R.string.mute)
            }
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.contact_call -> {
                checkAndRequestAudioPermission()
                return true
            }
            R.id.clear_history -> {
                showClearHistoryConfirmDialog()
                return true
            }
            R.id.mute_contact -> {
                toggleMuteContact()
                return true
            }
            R.id.remove_contact -> {
                showRemoveContactConfirmDialog()
                return true
            }
            else -> {
                if (item.itemId != android.R.id.home) {
                    Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        Log.i(TAG, "Clicked on ${item?.itemId}")
        when (item?.itemId) {
            R.id.contact_call -> {
                checkAndRequestAudioPermission()
            }
            R.id.clear_history -> {
                showClearHistoryConfirmDialog()
            }
            R.id.mute_contact -> {
                toggleMuteContact()
            }
            R.id.remove_contact -> {
                showRemoveContactConfirmDialog()
            }
            else -> {
                Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    private fun toggleMuteContact() {
        val isMuted = getMimir().isContactMuted(contact.id)
        val newMutedStatus = !isMuted
        getMimir().setContactMuted(contact.id, newMutedStatus)
        val message = if (newMutedStatus) {
            getString(R.string.mute) + ": " + contact.name
        } else {
            getString(R.string.unmute) + ": " + contact.name
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        invalidateOptionsMenu()
    }

    // Audio call functionality

    private fun checkAndRequestAudioPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                makeCall()
            }
            else -> {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                makeCall()
            }
        }

    private fun makeCall() {
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "call")
        intent.putExtra("pubkey", contact.pubkey)
        startService(intent)

        val storage = getMimir()
        val contactId = storage.getContactId(contact.pubkey)
        val name = storage.getContact(contactId)?.name ?: contact.name

        val callIntent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("pubkey", contact.pubkey)
            putExtra("name", name)
            putExtra("outgoing", true)
        }
        startActivity(callIntent, animFromRight.toBundle())
    }

    // Clear history functionality

    private fun showRemoveContactConfirmDialog() {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder = AlertDialog.Builder(wrapper)
        builder.setTitle(getString(R.string.remove_contact))
        builder.setMessage(getString(R.string.remove_contact_confirm))
        builder.setIcon(R.drawable.ic_contact_remove)
        builder.setPositiveButton(getString(R.string.menu_delete)) { _, _ ->
            removeContact()
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }

    private fun removeContact() {
        Thread {
            try {
                getMimir().removeContact(contact.id)
                Log.i(TAG, "Removed contact ${contact.id}")
                runOnUiThread { finish() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove contact", e)
            }
        }.start()
    }

    private fun showClearHistoryConfirmDialog() {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder = AlertDialog.Builder(wrapper)
        builder.setTitle(getString(R.string.clear_history))
        builder.setMessage(getString(R.string.clear_history_confirm_text))
        builder.setIcon(R.drawable.ic_clean_chat_outline)
        builder.setPositiveButton(getString(R.string.clear)) { _, _ ->
            clearHistory()
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }

    private fun clearHistory() {
        Thread {
            try {
                val attachments = getMimir().clearContactHistory(contact.id)
                val filesDir = File(filesDir, "files")
                val cacheFilesDir = File(cacheDir, "files")
                for (name in attachments) {
                    File(filesDir, name).delete()
                    File(cacheFilesDir, name).delete()
                }
                Log.i(TAG, "Cleared history for contact ${contact.id} (${attachments.size} attachments)")

                runOnUiThread {
                    adapter.clearAllMessages()
                    Toast.makeText(
                        this,
                        getString(R.string.history_cleared),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing history", e)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.error_clearing_history),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    // Empty view handling

    private fun setupEmptyView() {
        updateEmptyView()

        // Register observer to update empty view when adapter data changes
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                updateEmptyView()
            }

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                updateEmptyView()
            }

            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) {
                updateEmptyView()
            }
        })
    }

    private fun updateEmptyView() {
        val emptyView = findViewById<View>(R.id.empty_view)
        val messagesCount = adapter.itemCount

        // Keep RecyclerView always visible for proper keyboard handling
        // Just overlay the empty view on top when needed
        if (messagesCount == 0) {
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
        }
    }

    // Forward mode handling

    private fun handleForwardMode() {
        val messageText = intent.getStringExtra("FORWARD_MESSAGE_TEXT") ?: ""
        val messageType = intent.getIntExtra("FORWARD_MESSAGE_TYPE", 0)
        val messageJson = intent.getStringExtra("FORWARD_MESSAGE_JSON")

        // Show forwarded message in reply panel
        showReplyPanel(
            getString(R.string.forwarded_message),
            messageText,
            0L  // No actual GUID - this is a forward, not a reply
        )

        // Store forward data for sending
        when (messageType) {
            1 -> {
                // Image message - recreate attachment
                messageJson?.let { json ->
                    try {
                        val meta = org.json.JSONObject(json)
                        val filename = meta.optString("name", "")
                        if (filename.isNotEmpty()) {
                            val attachmentsDir = java.io.File(filesDir, "files")
                            val staged = stageForwardedAttachment(
                                context = this,
                                attachmentsDir = attachmentsDir,
                                storageFileName = filename,
                                originalName = meta.optString("originalName", filename),
                                mimeType = meta.optString("mimeType", "image/jpeg"),
                                msgType = messageType,
                            )
                            if (staged != null) {
                                staged.caption = meta.optString("text", "")
                                attachment = staged
                                val preview = android.graphics.BitmapFactory.decodeFile(
                                    java.io.File(attachmentsDir, filename).absolutePath,
                                )
                                attachmentPreview.setImageBitmap(preview)
                                attachmentPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                attachmentName.text = staged.spec.originalName
                                attachmentSize.text = formatFileSize(staged.size)
                                attachmentPanel.visibility = android.view.View.VISIBLE
                            } else {
                                Log.e(TAG, "Forwarded file not found: $filename")
                                Toast.makeText(this, "Media file not found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse forwarded media", e)
                    }
                }
            }
            3 -> {
                // File message - recreate attachment
                messageJson?.let { json ->
                    try {
                        val meta = org.json.JSONObject(json)
                        val filename = meta.optString("name", "")
                        if (filename.isNotEmpty()) {
                            val attachmentsDir = java.io.File(filesDir, "files")
                            val mimeType = meta.optString("mimeType", "application/octet-stream")
                            val staged = stageForwardedAttachment(
                                context = this,
                                attachmentsDir = attachmentsDir,
                                storageFileName = filename,
                                originalName = meta.optString("originalName", filename),
                                mimeType = mimeType,
                                msgType = messageType,
                            )
                            if (staged != null) {
                                staged.caption = meta.optString("text", "")
                                attachment = staged
                                attachmentPreview.setImageResource(getFileIconForMimeType(mimeType))
                                attachmentPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                                attachmentName.text = staged.spec.originalName
                                attachmentSize.text = formatFileSize(staged.size)
                                attachmentPanel.visibility = android.view.View.VISIBLE
                            } else {
                                Log.e(TAG, "Forwarded file not found: $filename")
                                Toast.makeText(this, "Media file not found", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse forwarded media", e)
                    }
                }
            }
            else -> {
                // Text message - stored in reply panel already
            }
        }

        updateSendButtonIcon()
    }

    // Peer status display

    private fun showOnlineState(status: PeerStatus) {
        val imageView = findViewById<AppCompatImageView>(R.id.status_image)
        when (status) {
            PeerStatus.NotConnected -> imageView.setImageResource(R.drawable.status_badge_red)
            PeerStatus.Connecting -> imageView.setImageResource(R.drawable.status_badge_yellow)
            PeerStatus.Connected -> imageView.setImageResource(R.drawable.status_badge_green)
            PeerStatus.ErrorConnecting -> imageView.setImageResource(R.drawable.status_badge_red)
        }
    }

    // Message lifecycle callbacks dispatched from messageReceiver (LocalBroadcastManager).

    fun onMessageSent(id: Long, contactId: Long, type: Int, replyTo: Long) {
        runOnUiThread {
            Log.i(TAG, "Message $id sent to $contactId")
            if (contact.id == contactId) {
                if (type == MSG_TYPE_REACTION) {
                    adapter.notifyMessageChanged(replyTo)
                    return@runOnUiThread
                }
                val isAtEnd = recyclerView.isAtEnd()
                adapter.addMessageId(id, false)
                if (isAtEnd) {
                    recyclerView.scrollToEnd()
                }
            }
        }
    }

    fun onMessageDelivered(id: Long, delivered: Boolean) {
        Log.i(TAG, "Message $id delivered = $delivered")
        runOnUiThread {
            adapter.setMessageDelivered(id, delivered)
            startShortSound(R.raw.message_sent)
        }
    }

    fun onMessageReceived(id: Long, contactId: Long, type: Int, replyTo: Long): Boolean {
        Log.i(TAG, "Message $id from $contactId")
        if (contact.id == contactId) {
            runOnUiThread {
                if (type == MSG_TYPE_REACTION) {
                    adapter.notifyMessageChanged(replyTo)
                    return@runOnUiThread
                }
                Log.i(TAG, "Adding message")
                val isAtEnd = recyclerView.isAtEnd()
                adapter.addMessageId(id, true)
                if (isChatVisible) {
                    if (isAtEnd) {
                        recyclerView.scrollToEnd()
                    } else {
                        startShortSound(R.raw.message_more)
                    }
                }
            }
            return isChatVisible
        }
        return false
    }

    // Lifecycle

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(peerStatusReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(audioPlaybackReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(fileDownloadReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(messageReceiver)
        super.onDestroy()
    }
}