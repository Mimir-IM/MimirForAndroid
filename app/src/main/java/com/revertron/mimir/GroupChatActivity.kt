package com.revertron.mimir

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.revertron.mimir.net.MSG_TYPE_REACTION
import com.revertron.mimir.net.MediatorManager
import uniffi.mimir.SystemMessage
import uniffi.mimir.parseSystemMessage as mimirParseSystemMessage
import com.revertron.mimir.ui.DisplayMessage
import com.revertron.mimir.ui.GroupChat
import com.revertron.mimir.ui.MessageAdapter
import com.revertron.mimir.ui.MimirMessageProvider
import com.revertron.mimir.ui.SettingsData
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import java.io.File

/**
 * Group chat activity using ConnectionService for server-mediated group messaging.
 *
 * This activity manages:
 * - Connection to mediator server via ConnectionService
 * - Sending and receiving group messages via service intents
 * - Message persistence in local storage
 * - Real-time message push notifications via BroadcastReceiver
 * - Group chat UI and interactions
 *
 * Unlike the original design that used MediatorClient directly, this refactored version
 * follows the same pattern as NewChatActivity by delegating all mediator operations to
 * ConnectionService and receiving responses via LocalBroadcast.
 */
class GroupChatActivity : BaseChatActivity() {

    companion object {
        const val TAG = "GroupChatActivity"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_CHAT_NAME = "chat_name"
        const val EXTRA_CHAT_DESCRIPTION = "chat_description"
        const val EXTRA_IS_OWNER = "is_owner"
        const val EXTRA_MEDIATOR_ADDRESS = "mediator_address"

        private const val REQUEST_SELECT_CONTACT = 100
    }

    override fun getMaxFileSize(): Long = MAX_GROUP_FILE_SIZE
    override fun getFileTooLargeMessageResId(): Int = R.string.group_file_too_large

    private lateinit var groupChat: GroupChat
    private var mediatorAddress: ByteArray? = null
    private lateinit var publicKey: ByteArray
    private val mainHandler = Handler(Looper.getMainLooper())
    private val messageProvider by lazy { MimirMessageProvider(App.app.mimirStorage!!, this) }

    private val mediatorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ACTION_MEDIATOR_MESSAGE_SENT" -> {
                    val chatId = intent.getLongExtra("chat_id", 0)
                    val messageId = intent.getLongExtra("message_id", 0)
                    val guid = intent.getLongExtra("guid", 0L)
                    val type = intent.getIntExtra("type", 0)
                    val replyTo = intent.getLongExtra("replyTo", 0L)

                    if (chatId == groupChat.chatId) {
                        Log.i(TAG, "Message sent successfully: msgId=$messageId, guid=$guid")
                        mainHandler.post {
                            if (type == MSG_TYPE_REACTION) {
                                adapter.notifyMessageChanged(replyTo)
                                return@post
                            }
                            startShortSound(R.raw.message_sent)
                            adapter.addMessageId(messageId, false)
                            recyclerView.scrollToEnd()
                        }
                    }
                }
                "ACTION_MEDIATOR_CHAT_DELETED" -> {
                    val chatId = intent.getLongExtra("chat_id", 0)
                    if (chatId == groupChat.chatId) {
                        Log.i(TAG, "Chat $chatId deleted successfully, closing activity")
                        mainHandler.post {
                            finish()
                        }
                    }
                }
                "ACTION_MEDIATOR_LEFT_CHAT" -> {
                    val chatId = intent.getLongExtra("chat_id", 0)
                    if (chatId == groupChat.chatId) {
                        mainHandler.post {
                            Log.i(TAG, "Left chat $chatId")
                            finish()
                        }
                    }
                }
                "ACTION_MEDIATOR_ERROR" -> {
                    val operation = intent.getStringExtra("operation")
                    val error = intent.getStringExtra("error")

                    mainHandler.post {
                        val message = when (operation) {
                            "subscribe" -> getString(R.string.connection_failed)
                            "send" -> getString(R.string.message_send_failed)
                            "leave" -> getString(R.string.failed_to_leave_group)
                            "delete" -> getString(R.string.failed_to_delete_group)
                            else -> error ?: getString(R.string.operation_failed)
                        }
                        Toast.makeText(this@GroupChatActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
                "ACTION_GROUP_MESSAGE_RECEIVED" -> {
                    val chatId = intent.getLongExtra("chat_id", 0)
                    val messageId = intent.getLongExtra("message_id", 0)
                    val msgType = intent.getIntExtra("msg_type", 0)
                    val replyTo = intent.getLongExtra("reply_to", 0L)
                    if (chatId == groupChat.chatId) {
                        onGroupMessageReceived(chatId, messageId, 0, msgType, replyTo)
                    }
                }
                "ACTION_GROUP_MESSAGE_DELETED" -> {
                    val chatId = intent.getLongExtra("chat_id", 0)
                    val guid = intent.getLongExtra("guid", 0)
                    if (chatId == groupChat.chatId) {
                        val msg = getStorage().getGroupMessageByGuid(chatId, guid)
                        if (msg != null) {
                            onGroupMessageDeleted(chatId, msg.id)
                        }
                    }
                }
                "ACTION_GROUP_MESSAGE_DELIVERED" -> {
                    val chatId = intent.getLongExtra("chat_id", 0)
                    val guid = intent.getLongExtra("guid", 0L)
                    if (chatId == groupChat.chatId) {
                        val msg = getStorage().getGroupMessageByGuid(chatId, guid)
                        if (msg != null) {
                            mainHandler.post {
                                adapter.setMessageDelivered(msg.id, true)
                                startShortSound(R.raw.message_sent)
                            }
                        }
                    }
                }
            }
        }
    }

    private val groupChatStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val chatId = intent?.getLongExtra("chat_id", 0L) ?: return
            if (chatId != groupChat.chatId) return
            when (intent.action) {
                "ACTION_GROUP_CHAT_STATUS" -> {
                    val statusName = intent.getStringExtra("status") ?: return
                    try {
                        val status = MediatorManager.GroupChatStatus.valueOf(statusName)
                        mainHandler.post {
                            showConnectionStatus(status)
                            if (status == MediatorManager.GroupChatStatus.READ_ONLY && !groupChat.readOnly) {
                                groupChat.readOnly = true
                                applyReadOnlyMode()
                            } else if (status == MediatorManager.GroupChatStatus.SUBSCRIBED && groupChat.readOnly) {
                                groupChat.readOnly = false
                                exitReadOnlyMode()
                            }
                        }
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Invalid status received: $statusName", e)
                    }
                }
                "ACTION_MEMBERS_SYNCED" -> mainHandler.post { updateMemberCount() }
                "ACTION_MEMBER_ONLINE_STATUS" -> mainHandler.post { updateMemberCount() }
            }
        }
    }

    private val audioPlaybackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioPlaybackService.ACTION_PLAYBACK_STATE_CHANGED) {
                val isPlaying = intent.getBooleanExtra(AudioPlaybackService.EXTRA_IS_PLAYING, false)
                val messageId = intent.getLongExtra(AudioPlaybackService.EXTRA_CURRENT_MESSAGE_ID, -1)
                adapter.updateAudioPlaybackState(isPlaying, messageId)
            }
        }
    }

    private val fileProgressReceiver = object : BroadcastReceiver() {
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
        // Extract group chat info before calling super.onCreate()
        val chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0)
        val chatName = intent.getStringExtra(EXTRA_CHAT_NAME)
        val chatDescription = intent.getStringExtra(EXTRA_CHAT_DESCRIPTION) ?: ""
        val isOwner = intent.getBooleanExtra(EXTRA_IS_OWNER, false)
        mediatorAddress = intent.getByteArrayExtra(EXTRA_MEDIATOR_ADDRESS)
            ?: MediatorManager.getDefaultMediatorPubkey()

        val memberCount = getStorage().getMemberCount(chatId)
        val readOnly = getStorage().getGroupChat(chatId)?.readOnly ?: false

        if (chatId == 0L || chatName == null || mediatorAddress == null) {
            Log.e(TAG, "Missing required extras: chat_id, chat_name, or mediator_address")
            finish()
            return
        }

        // Get sender pubkey from account
        publicKey = getStorage().getAccount(1)?.pubkey ?: ByteArray(0)

        // Load avatar from storage — avatar path is on the group chat object
        val avatarPath = getStorage().getGroupChat(chatId)?.avatar ?: ""
        val avatar = if (avatarPath.isNotEmpty()) loadRoundedAvatar(this, avatarPath) else null
        groupChat = GroupChat(
            chatId = chatId,
            name = chatName,
            description = chatDescription,
            avatar = avatar,
            lastMessageText = null,
            lastMessageTime = 0L,
            unreadCount = 0,
            memberCount = memberCount,
            isOwner = isOwner,
            readOnly = readOnly
        )

        super.onCreate(savedInstanceState)

        // Set title, subtitle, and avatar
        setToolbarTitle(groupChat.name)
        setToolbarSubtitle(getString(R.string.member_count,
            groupChat.memberCount,
            getStorage().getOnlineMemberCount(groupChat.chatId)))
        setupAvatar(groupChat.avatar)

        // Setup message list
        setupMessageList()

        // Setup broadcast receivers
        setupBroadcastReceivers()

        // Initialize connection status badge
        if (groupChat.readOnly) {
            applyReadOnlyMode()
        } else {
            showConnectionStatus(MediatorManager.GroupChatStatus.CONNECTING)
        }
        requestGroupChatStatus()

        // Handle shared media if any (similar to ChatActivity:61-64)
        val sharedUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (sharedUri != null) {
            handleSharedMedia(sharedUri)
        } else {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                val editText = findViewById<AppCompatEditText>(R.id.message_edit)
                editText.setText(sharedText)
                editText.setSelection(sharedText.length)
            }
        }

        // Handle forward mode if present
        if (intent.getBooleanExtra("FORWARD_MODE", false)) {
            handleForwardMode()
        }
    }

    // BaseChatActivity abstract method implementations

    override fun getLayoutResId(): Int = R.layout.activity_group_chat

    override fun getChatId(): Long = groupChat.chatId

    override fun getChatName(): String = groupChat.name

    override fun isGroupChat(): Boolean = true

    override fun getAvatarColorSeed(): ByteArray = groupChat.chatId.toString().toByteArray()

    override fun createMessageAdapter(fontSize: Int): MessageAdapter {
        return MessageAdapter(
            messageProvider,
            groupChat.chatId,
            groupChat = true, // Enable group-chat mode to show sender names
            groupChat.name,
            myName,
            this,
            onClickOnReply(),
            onClickOnPicture(),
            fontSize,
            onAvatarClick = onClickOnAvatar(),
            onReactionsMarkedSeen = { updateReactionFabVisibility() }
        )
    }

    override fun getFirstUnreadMessageId(): Long? {
        return getStorage().getFirstUnreadGroupMessageId(groupChat.chatId)
    }

    override fun deleteMessageByIdOrGuid(messageId: Long, guid: Long) {
        // Ask ConnectionService to delete from mediator (it looks up the server message ID)
        startService(Intent(this, ConnectionService::class.java).apply {
            putExtra("command", "mediator_delete_message")
            putExtra("chat_id", groupChat.chatId)
            putExtra("guid", guid)
        })
        // Delete locally and clean up attachment
        val attachmentFileName = getStorage().deleteGroupMessageByGuid(groupChat.chatId, guid)
        if (attachmentFileName != null) {
            File(File(filesDir, "files"), attachmentFileName).delete()
            File(File(cacheDir, "files"), attachmentFileName).delete()
        }
    }

    override fun getMessageForReply(messageId: Long): Pair<String, String>? {
        val message = getStorage().getGroupMessageByGuid(groupChat.chatId, messageId) ?: return null
        val authorName = if (message.incoming) {
            val pubkey = getStorage().getMemberPubkey(groupChat.chatId, message.contactId)
            val member = if (pubkey != null) getStorage().getMemberInfo(groupChat.chatId, pubkey) else null
            member?.nickname ?: getString(R.string.unknown_nickname)
        } else {
            myName
        }
        val text = DisplayMessage.from(message).getText(this)
        return Pair(authorName, text)
    }

    override fun getMessageFromStorage(messageId: Long): DisplayMessage? {
        val msg = getStorage().getGroupMessage(groupChat.chatId, messageId) ?: return null
        return DisplayMessage.from(msg)
    }

    override fun handleReaction(targetGuid: Long, emoji: String, currentEmoji: String?) {
        // Toggle behavior: if clicking the same emoji, remove it
        val finalEmoji = if (emoji == currentEmoji) "" else emoji

        // Create reaction message data as JSON
        val reactionData = org.json.JSONObject().apply {
            put("emoji", finalEmoji)
            put("replyTo", targetGuid)
        }.toString()

        val sendTime = System.currentTimeMillis()
        val guid = getStorage().generateGuid(sendTime, reactionData.toByteArray())

        // Send reaction via ConnectionService — MimirApp handles storage + encryption
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "mediator_send")
        intent.putExtra("chat_id", groupChat.chatId)
        intent.putExtra("guid", guid)
        intent.putExtra("reply_to", targetGuid)
        intent.putExtra("send_time", sendTime)
        intent.putExtra("type", 10)
        intent.putExtra("message", reactionData)
        startService(intent)
    }

    override fun getUserCurrentReaction(targetGuid: Long): String? {
        return messageProvider.getUserReaction(groupChat.chatId, targetGuid, true)
    }

    override fun getUnseenReactionsCount(): Int {
        return getStorage().getGroupUnseenReactionsCount(groupChat.chatId).toInt()
    }

    override fun scrollToFirstUnseenReaction(): Boolean {
        val target = getStorage().getFirstUnseenGroupReaction(groupChat.chatId) ?: return false
        val position = adapter.getMessageGuidPosition(target.targetGuid)
        if (position < 0) return false
        recyclerView.smoothScrollToPosition(position)
        getStorage().markGroupReactionAsSeen(groupChat.chatId, target.reactionGuid)
        adapter.notifyMessageChanged(target.targetGuid)
        return true
    }

    override fun sendMessage(text: String, replyTo: Long) {
        val app = App.app.mimirApp ?: run {
            Log.w(TAG, "sendMessage: MimirApp not ready yet")
            return
        }
        // Ownership handoff on the UI thread — see BaseChatActivity.takeAttachmentForSending.
        val staged = takeAttachmentForSending()
        val fileServerHex = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(SettingsData.KEY_FILE_SERVER_PUBKEY, ConnectionService.DEFAULT_FILE_SERVER_HEX)
            ?: ConnectionService.DEFAULT_FILE_SERVER_HEX
        val fileServerPubkey = if (staged != null) Hex.decode(fileServerHex) else null

        Thread {
            try {
                // Rust's Phase 1 fires on_outgoing_message_stored → the row
                // appears in the chat as "sending…" before upload/dispatch.
                // Rust's Phase 3 fires on_group_message_delivered → the
                // delivered indicator flips once the mediator acknowledges.
                app.sendGroupChatMessage(
                    groupChat.chatId, replyTo, text, staged?.spec, fileServerPubkey,
                )
            } catch (e: Exception) {
                Log.e(TAG, "sendGroupChatMessage failed: ${e.message}", e)
            } finally {
                staged?.stagedFile?.delete()
            }
        }.start()
    }

    override fun setupBroadcastReceivers() {
        val filter = IntentFilter().apply {
            addAction("ACTION_MEDIATOR_MESSAGE_SENT")
            addAction("ACTION_MEDIATOR_CHAT_DELETED")
            addAction("ACTION_MEDIATOR_LEFT_CHAT")
            addAction("ACTION_MEDIATOR_ERROR")
            addAction("ACTION_GROUP_MESSAGE_RECEIVED")
            addAction("ACTION_GROUP_MESSAGE_DELETED")
            addAction("ACTION_GROUP_MESSAGE_DELIVERED")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(mediatorReceiver, filter)

        // Register status receiver for connection badge updates and member sync
        val statusFilter = IntentFilter().apply {
            addAction("ACTION_GROUP_CHAT_STATUS")
            addAction("ACTION_MEMBERS_SYNCED")
            addAction("ACTION_MEMBER_ONLINE_STATUS")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(groupChatStatusReceiver, statusFilter)

        // Register audio playback state receiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            audioPlaybackReceiver,
            IntentFilter(AudioPlaybackService.ACTION_PLAYBACK_STATE_CHANGED)
        )

        // Register file progress receiver
        val fileFilter = IntentFilter().apply {
            addAction("ACTION_FILE_DOWNLOADING")
            addAction("ACTION_FILE_DOWNLOADED")
            addAction("ACTION_FILE_PROGRESS")
            addAction("ACTION_FILE_UPLOADED")
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(fileProgressReceiver, fileFilter)
    }

    override fun onToolbarClick() {
        openGroupInfo()
    }

    // Click handlers

    private fun onClickOnAvatar() = fun(it: View) {
        val memberId = it.tag as? Long
        if (memberId != null) {
            val pubKey = getStorage().getMemberPubkey(groupChat.chatId, memberId)
            if (pubKey != null) {
                val member = getStorage().getMemberInfo(groupChat.chatId, pubKey)
                val intent = Intent(this, ContactActivity::class.java)
                intent.putExtra("pubkey", pubKey)
                intent.putExtra("name", member?.nickname ?: Hex.toHexString(pubKey).take(16))
                startActivity(intent, animFromRight.toBundle())
            }
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
            1, 3 -> {
                messageJson?.let { json ->
                    try {
                        val meta = org.json.JSONObject(json)
                        val filename = meta.optString("name", "")
                        if (filename.isNotEmpty()) {
                            val attachmentsDir = java.io.File(filesDir, "files")
                            val mimeType = meta.optString(
                                "mimeType",
                                if (messageType == 1) "image/jpeg" else "application/octet-stream",
                            )
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
                                if (messageType == 1) {
                                    val preview = android.graphics.BitmapFactory.decodeFile(
                                        java.io.File(attachmentsDir, filename).absolutePath,
                                    )
                                    attachmentPreview.setImageBitmap(preview)
                                    attachmentPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                } else {
                                    attachmentPreview.setImageResource(getFileIconForMimeType(mimeType))
                                    attachmentPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                                }
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

    // Menu handling

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_group_chat, menu)

        if (groupChat.readOnly) {
            // In read-only mode, only show delete (local cleanup)
            menu.findItem(R.id.add_member)?.isVisible = false
            menu.findItem(R.id.mute_group)?.isVisible = false
            menu.findItem(R.id.leave_group)?.isVisible = false
            menu.findItem(R.id.delete_group)?.isVisible = true
        } else if (!groupChat.isOwner) {
            menu.findItem(R.id.add_member)?.isVisible = false
            menu.findItem(R.id.delete_group)?.isVisible = false
        } else {
            // Hide leave group option for owners (they must delete the group instead)
            menu.findItem(R.id.leave_group)?.isVisible = false
        }

        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)

        // Update mute/unmute menu item title based on current muted status
        val muteItem = menu.findItem(R.id.mute_group)
        val currentlyMuted = getStorage().getGroupChat(groupChat.chatId)?.muted ?: false

        muteItem?.title = if (currentlyMuted) {
            getString(R.string.unmute_group)
        } else {
            getString(R.string.mute_group)
        }

        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Check if we should navigate up to parent activity
        // This handles the case where activity was launched from notification
        if (shouldNavigateUpToParent()) {
            val upIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("no_service", true)
            }
            startActivity(upIntent)
            finish()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /*
     * Determines if we need to manually navigate to parent activity.
     * Returns true if the activity has no parent in the back stack.
     */
    private fun shouldNavigateUpToParent(): Boolean {
        // Check if this activity was launched in a way that has no back stack
        // (e.g., from a notification)
        return isTaskRoot
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.add_member -> {
                openContactSelector()
                true
            }
            R.id.mute_group -> {
                val currentlyMuted = getStorage().getGroupChat(groupChat.chatId)?.muted ?: false
                val newMutedStatus = !currentlyMuted
                getStorage().setGroupChatMuted(groupChat.chatId, newMutedStatus)
                val message = if (newMutedStatus) {
                    getString(R.string.mute_group)
                } else {
                    getString(R.string.unmute_group)
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                invalidateOptionsMenu()
                true
            }
            R.id.clear_history -> {
                showClearHistoryConfirmDialog()
                true
            }
            R.id.leave_group -> {
                leaveGroup()
                true
            }
            R.id.delete_group -> {
                deleteGroup()
                true
            }
            else -> false
        }
    }

    private fun leaveGroup() {
        // Check if user is the owner - owners cannot leave, they must delete the group instead
        if (groupChat.isOwner) {
            Toast.makeText(
                this,
                getString(R.string.owner_cannot_leave_group),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Show confirmation dialog
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(R.string.leave_group)
            .setMessage(R.string.confirm_leave_group)
            .setPositiveButton(R.string.leave) { _, _ ->
                // Send intent to ConnectionService to leave the chat
                val intent = Intent(this, ConnectionService::class.java)
                intent.putExtra("command", "mediator_leave")
                intent.putExtra("chat_id", groupChat.chatId)
                startService(intent)

                Log.i(TAG, "Sent leave chat request to ConnectionService for chat ${groupChat.chatId}")

                // The activity will be finished when ACTION_MEDIATOR_LEFT_CHAT broadcast is received
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteGroup() {
        // Show confirmation dialog
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(R.string.delete_group)
            .setMessage(R.string.confirm_delete_group)
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                if (groupChat.readOnly) {
                    // Read-only chat: just delete locally, no mediator call needed
                    getStorage().deleteGroupChat(groupChat.chatId)
                    Log.i(TAG, "Deleted read-only chat ${groupChat.chatId} from local storage")
                    finish()
                } else {
                    // Send intent to ConnectionService to delete the chat
                    val intent = Intent(this, ConnectionService::class.java)
                    intent.putExtra("command", "mediator_delete")
                    intent.putExtra("chat_id", groupChat.chatId)
                    startService(intent)

                    Log.i(TAG, "Sent delete chat request to ConnectionService for chat ${groupChat.chatId}")

                    // The activity will be finished when ACTION_MEDIATOR_LEFT_CHAT broadcast is received
                    // (same broadcast as leaving, as both result in the chat being removed)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // Clear history functionality

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
                val attachments = getStorage().clearGroupChatHistory(groupChat.chatId)
                val filesDir = File(filesDir, "files")
                val cacheFilesDir = File(cacheDir, "files")
                for (name in attachments) {
                    File(filesDir, name).delete()
                    File(cacheFilesDir, name).delete()
                }
                Log.i(TAG, "Cleared history for chat ${groupChat.chatId} (${attachments.size} attachments)")

                runOnUiThread {
                    adapter.clearAllMessages()
                    Toast.makeText(
                        this,
                        getString(R.string.history_cleared),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error clearing group history", e)
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

    // Message lifecycle callbacks dispatched from mediatorReceiver.

    fun onGroupMessageReceived(chatId: Long, id: Long, contactId: Long, type: Int, replyTo: Long): Boolean {
        if (chatId == groupChat.chatId) {
            runOnUiThread {
                val message = getStorage().getGroupMessage(chatId, id)
                if (message != null) {
                    if (type == MSG_TYPE_REACTION) {
                        adapter.notifyMessageChanged(replyTo)
                        return@runOnUiThread
                    }
                    val isAtEnd = recyclerView.isAtEnd()
                    adapter.addMessageId(id, message.incoming)
                    if (isAtEnd) {
                        recyclerView.scrollToEnd()
                    } else {
                        startShortSound(R.raw.message_more)
                    }

                    // Check if this is a system message that affects member count
                    if (message.msgType == 1000 && message.data != null) {
                        val sysMsg = try { mimirParseSystemMessage(message.data!!) } catch (_: Exception) { null }
                        when (sysMsg) {
                            is SystemMessage.UserAdded,
                            is SystemMessage.UserLeft,
                            is SystemMessage.UserBanned -> {
                                // Member list changed - update member count
                                // (Member deletion from table is handled by MediatorManager)
                                updateMemberCount()
                            }
                            else -> {
                                // Other system messages don't affect member count
                            }
                        }
                    }
                }
            }
            return isChatVisible
        }
        return false
    }

    fun onGroupMessageDeleted(chatId: Long, messageId: Long) {
        if (chatId == groupChat.chatId) {
            runOnUiThread {
                Log.i(TAG, "Message $messageId deleted in chat $chatId, updating adapter")
                adapter.deleteMessageId(messageId)
            }
        }
    }

    private fun updateMemberCount() {
        val total = getStorage().getMemberCount(groupChat.chatId)
        val online = getStorage().getOnlineMemberCount(groupChat.chatId)

        groupChat = groupChat.copy(memberCount = total)
        findViewById<AppCompatTextView>(R.id.subtitle).text =
            getString(R.string.member_count, total, online)
    }

    private fun refreshMemberStatus() {
        updateMemberCount()
    }

    // Invite member functionality

    private fun openContactSelector() {
        val members = getStorage().getMembers(groupChat.chatId)
        val memberPubkeys = members.map { it.pubkey }.toTypedArray()

        val intent = Intent(this, ContactSelectorActivity::class.java).apply {
            putExtra(ContactSelectorActivity.EXTRA_TITLE, getString(R.string.add_member))
            putExtra(ContactSelectorActivity.EXTRA_FILTER_PUBKEYS, memberPubkeys)
        }
        startActivityForResult(intent, REQUEST_SELECT_CONTACT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_SELECT_CONTACT -> {
                if (resultCode == RESULT_OK && data != null) {
                    val pubkey = data.getByteArrayExtra(ContactSelectorActivity.RESULT_PUBKEY)
                    val name = data.getStringExtra(ContactSelectorActivity.RESULT_NAME)

                    if (pubkey != null) {
                        sendInvite(pubkey, name ?: "Unknown")
                    }
                }
            }
        }
    }

    private fun sendInvite(recipientPubkey: ByteArray, recipientName: String) {
        // Send intent to ConnectionService to send invite
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "mediator_send_invite")
        intent.putExtra("chat_id", groupChat.chatId)
        intent.putExtra("recipient_pubkey", recipientPubkey)
        startService(intent)

        val pubkeyHex = Hex.toHexString(recipientPubkey).take(8)
        Log.i(TAG, "Sent invite request to ConnectionService for user $pubkeyHex")

        Toast.makeText(
            this,
            "Invite sent to $recipientName",
            Toast.LENGTH_SHORT
        ).show()
    }

    // Group Info

    private fun openGroupInfo() {
        val intent = Intent(this, GroupInfoActivity::class.java).apply {
            putExtra(GroupInfoActivity.EXTRA_CHAT_ID, groupChat.chatId)
            putExtra(GroupInfoActivity.EXTRA_CHAT_NAME, groupChat.name)
            putExtra(GroupInfoActivity.EXTRA_CHAT_DESCRIPTION, groupChat.description)
            putExtra(GroupInfoActivity.EXTRA_IS_OWNER, groupChat.isOwner)
            putExtra(GroupInfoActivity.EXTRA_MEDIATOR_ADDRESS, mediatorAddress)
        }
        startActivity(intent)
    }

    /**
     * Apply read-only mode: hide tools, update subtitle, show grey badge, refresh menu.
     */
    private fun applyReadOnlyMode() {
        findViewById<View>(R.id.tools).visibility = View.GONE
        setToolbarSubtitle(getString(R.string.read_only))
        showConnectionStatus(MediatorManager.GroupChatStatus.READ_ONLY)
        invalidateOptionsMenu()
    }

    private fun exitReadOnlyMode() {
        findViewById<View>(R.id.tools).visibility = View.VISIBLE
        updateMemberCount()
        showConnectionStatus(MediatorManager.GroupChatStatus.SUBSCRIBED)
        invalidateOptionsMenu()
    }

    /**
     * Update the status badge on the group avatar based on connection status.
     * Red = disconnected, Yellow = connecting, Green = subscribed, Grey = read-only
     */
    private fun showConnectionStatus(status: MediatorManager.GroupChatStatus) {
        val imageView = findViewById<AppCompatImageView>(R.id.status_image)
        when (status) {
            MediatorManager.GroupChatStatus.DISCONNECTED -> {
                imageView.setImageResource(R.drawable.status_badge_red)
            }
            MediatorManager.GroupChatStatus.CONNECTING -> {
                imageView.setImageResource(R.drawable.status_badge_yellow)
            }
            MediatorManager.GroupChatStatus.SUBSCRIBED -> {
                imageView.setImageResource(R.drawable.status_badge_green)
            }
            MediatorManager.GroupChatStatus.READ_ONLY -> {
                imageView.setImageResource(R.drawable.status_badge_grey)
            }
        }
    }

    /**
     * Request the current connection status from ConnectionService.
     * The service will respond with an ACTION_GROUP_CHAT_STATUS broadcast.
     */
    private fun requestGroupChatStatus() {
        val intent = Intent(this, ConnectionService::class.java).apply {
            putExtra("command", "group_chat_status")
            putExtra("chat_id", groupChat.chatId)
        }
        startService(intent)
    }

    private fun handleSharedMedia(uri: Uri) {
        try {
            val mimeType = contentResolver.getType(uri)
            when {
                mimeType?.startsWith("image/") == true -> {
                    getImageFromUri(uri)
                }
                else -> {
                    getFileFromUri(uri)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle shared media: ${e.message}")
            Toast.makeText(this, R.string.error_loading_file, Toast.LENGTH_SHORT).show()
        }
    }

    // Lifecycle

    override fun onResume() {
        super.onResume()
        // Refresh member cache in case avatars or member info was updated
        // (e.g., after returning from GroupInfoActivity)
        adapter.refreshMemberCache()
        refreshMemberStatus()
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mediatorReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(groupChatStatusReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(audioPlaybackReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(fileProgressReceiver)
        super.onDestroy()
    }
}