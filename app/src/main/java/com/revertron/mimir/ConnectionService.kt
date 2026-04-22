package com.revertron.mimir

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.os.postDelayed
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.revertron.mimir.NotificationHelper.Companion.cancelCallNotifications
import com.revertron.mimir.NotificationHelper.Companion.showCallNotification
import com.revertron.mimir.net.MediatorManager
import com.revertron.mimir.net.PeerStatus
import com.revertron.mimir.storage.PeerProvider
import com.revertron.mimir.ui.SettingsData
import org.bouncycastle.util.encoders.Hex
import uniffi.mimir.AndroidNetworkInterface
import uniffi.mimir.AppConfig
import uniffi.mimir.AppEventListener
import uniffi.mimir.CallStatus
import uniffi.mimir.MimirApp
import java.io.File
import java.lang.Thread.sleep

class ConnectionService : Service() {

    // ── AppEventListener — maps Rust callbacks to Android broadcasts ─────────
    private val appEventListener = object : AppEventListener {
        override fun onConnectivityChanged(isOnline: Boolean) {
            Log.i(TAG, ">>> onConnectivityChanged: $isOnline")
            App.app.online = isOnline
            val state = if (isOnline) State.Online else State.Offline
            val n = NotificationHelper.createForegroundServiceNotification(this@ConnectionService, state, "", 0)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, n)
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_CONNECTIVITY_CHANGED").putExtra("is_online", isOnline)
            )
        }
        override fun onMessageReceived(contactId: Long, messageId: Long, msgType: Int, replyTo: Long) {
            NotificationHelper.showMessageNotification(this@ConnectionService, contactId, messageId)
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_MESSAGE_RECEIVED").apply {
                    putExtra("contact_id", contactId)
                    putExtra("message_id", messageId)
                    putExtra("msg_type", msgType)
                    putExtra("reply_to", replyTo)
                }
            )
        }
        override fun onMessageDelivered(contactPubkey: ByteArray, guid: Long) {
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_MESSAGE_DELIVERED").apply {
                    putExtra("pubkey", contactPubkey)
                    putExtra("guid", guid)
                }
            )
        }
        override fun onContactConnected(pubkey: ByteArray) {
            peerStatuses[Hex.toHexString(pubkey)] = PeerStatus.Connected
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_PEER_STATUS").apply {
                    putExtra("contact", Hex.toHexString(pubkey))
                    putExtra("status", PeerStatus.Connected.ordinal)
                }
            )
        }
        override fun onContactDisconnected(pubkey: ByteArray, dead: Boolean) {
            val status = if (dead) PeerStatus.ErrorConnecting else PeerStatus.NotConnected
            peerStatuses[Hex.toHexString(pubkey)] = status
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_PEER_STATUS").apply {
                    putExtra("contact", Hex.toHexString(pubkey))
                    putExtra("status", status.ordinal)
                }
            )
        }
        override fun onGroupMessageReceived(chatId: Long, messageId: Long, msgType: Int, replyTo: Long) {
            NotificationHelper.showGroupMessageNotification(this@ConnectionService, chatId, messageId)
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_GROUP_MESSAGE_RECEIVED").apply {
                    putExtra("chat_id", chatId)
                    putExtra("message_id", messageId)
                    putExtra("msg_type", msgType)
                    putExtra("reply_to", replyTo)
                }
            )
        }
        override fun onGroupMessageDeleted(chatId: Long, guid: Long) {
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_GROUP_MESSAGE_DELETED").apply {
                    putExtra("chat_id", chatId)
                    putExtra("guid", guid)
                }
            )
        }
        override fun onGroupMessageDelivered(chatId: Long, guid: Long) {
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_GROUP_MESSAGE_DELIVERED").apply {
                    putExtra("chat_id", chatId)
                    putExtra("guid", guid)
                }
            )
        }
        override fun onOutgoingMessageStored(isGroup: Boolean, targetId: Long, messageId: Long) {
            if (isGroup) {
                // Use existing ACTION_MEDIATOR_MESSAGE_SENT so the adapter adds the row.
                LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                    Intent("ACTION_MEDIATOR_MESSAGE_SENT").apply {
                        putExtra("chat_id", targetId)
                        putExtra("message_id", messageId)
                        putExtra("guid", 0L)
                        putExtra("type", 0)
                        putExtra("replyTo", 0L)
                    }
                )
            } else {
                LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                    Intent("ACTION_MESSAGE_SENT").apply {
                        putExtra("message_id", messageId)
                        putExtra("contact_id", targetId)
                        putExtra("type", 0)
                        putExtra("reply_to", 0L)
                    }
                )
            }
        }
        override fun onGroupChatStatus(chatId: Long, status: String) {
            Log.i(TAG, "onGroupChatStatus: chat=$chatId status=$status")
            when (status) {
                "SUBSCRIBED" -> {
                    App.app.mediatorStatus = App.MediatorStatus.Connected
                    LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                        Intent("ACTION_MEDIATOR_CONNECTED")
                    )
                }
                "DISCONNECTED" -> {
                    App.app.mediatorStatus = App.MediatorStatus.Disconnected
                    LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                        Intent("ACTION_MEDIATOR_DISCONNECTED")
                    )
                }
            }
            broadcastGroupChatStatus(chatId, status)
        }
        override fun onGroupInviteReceived(inviteId: Long, chatId: Long, fromPubkey: ByteArray) {
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_GROUP_INVITE_RECEIVED").apply {
                    putExtra("invite_id", inviteId)
                    putExtra("chat_id", chatId)
                    putExtra("from_pubkey", fromPubkey)
                }
            )

            // The Rust callback only carries id/chat/from. Look up the rest in
            // storage to build a rich notification that deep-links into
            // GroupInviteActivity with all the bytes the activity expects.
            val storage = App.app.mimirApp ?: return
            val invite = storage.getPendingInvites().firstOrNull { it.id == inviteId } ?: return
            NotificationHelper.showGroupInviteNotification(
                this@ConnectionService,
                inviteId,
                chatId,
                fromPubkey,
                invite.timestamp,
                invite.chatName,
                invite.chatDescription,
                invite.chatAvatar.ifEmpty { null },
                invite.encryptedData,
                invite.mediatorPubkey,
            )
        }
        override fun onGroupMembersChanged(chatId: Long) {
            Log.i(TAG, "onGroupMembersChanged: chat=$chatId")
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_MEMBERS_SYNCED").putExtra("chat_id", chatId)
            )
        }
        override fun onGroupMemberOnline(chatId: Long, pubkey: ByteArray, online: Boolean) {
            Log.i(TAG, "onGroupMemberOnline: chat=$chatId pubkey=${Hex.toHexString(pubkey).take(8)} online=$online")
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_MEMBER_ONLINE_STATUS").apply {
                    putExtra("chat_id", chatId)
                    putExtra("pubkey", pubkey)
                    putExtra("is_online", online)
                }
            )
        }
        override fun onMediatorAuthenticated(mediatorPubkey: ByteArray) {
            // Fires as soon as auth succeeds with ANY mediator — covers the
            // zero-group-chat user whose status dot would otherwise stay red
            // (no chat to subscribe to, so no onGroupChatStatus(SUBSCRIBED)).
            Log.i(TAG, "onMediatorAuthenticated: pubkey=${Hex.toHexString(mediatorPubkey).take(8)}")
            App.app.mediatorStatus = App.MediatorStatus.Connected
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_MEDIATOR_CONNECTED")
            )
        }
        override fun onMediatorDisconnected(mediatorPubkey: ByteArray) {
            Log.i(TAG, "onMediatorDisconnected: pubkey=${Hex.toHexString(mediatorPubkey).take(8)}")
            App.app.mediatorStatus = App.MediatorStatus.Disconnected
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_MEDIATOR_DISCONNECTED")
            )
        }
        override fun onIncomingCall(pubkey: ByteArray) {
            callingPubkey = pubkey
            showCallNotification(this@ConnectionService, applicationContext, false, pubkey)
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_INCOMING_CALL").putExtra("pubkey", pubkey)
            )
        }
        override fun onCallStatusChanged(status: CallStatus, pubkey: ByteArray?) {
            when (status) {
                CallStatus.IN_CALL -> {
                    outgoingCallPubkey = null
                    activeCallPubkey = pubkey
                    if (pubkey != null) startAudioSession(pubkey)
                    showCallNotification(this@ConnectionService, applicationContext, true, pubkey ?: ByteArray(0))
                    LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(Intent("ACTION_IN_CALL_START"))
                }
                CallStatus.HANGUP, CallStatus.IDLE -> {
                    outgoingCallPubkey = null
                    stopAudioSession()
                    activeCallPubkey = null
                    callingPubkey = null
                    cancelCallNotifications(this@ConnectionService)
                    LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(Intent("ACTION_FINISH_CALL"))
                }
                else -> {
                    LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                        Intent("ACTION_CALL_STATUS").apply {
                            putExtra("status", status.name)
                            if (pubkey != null) putExtra("pubkey", pubkey)
                        }
                    )
                }
            }
        }
        override fun onCallPacket(pubkey: ByteArray, data: ByteArray) {
            activeAudioReceiver?.receivePacket(data)
        }
        override fun onFileProgress(guid: Long, bytesDone: Long, totalBytes: Long, isUpload: Boolean) {
            handler.post { broadcastFileProgress(guid, bytesDone, totalBytes, isUpload) }
        }
        override fun onFileReceived(pubkey: ByteArray, guid: Long, metaJson: String, filePath: String) {
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_FILE_DOWNLOADED").apply {
                    putExtra("guid", guid)
                    putExtra("pubkey", pubkey)
                }
            )
        }
        override fun onFileDownloaded(chatId: Long, guid: Long, filePath: String) {
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_FILE_DOWNLOADED").putExtra("guid", guid)
            )
        }
        override fun onContactRequestReceived(requestId: Long) {
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_CONTACT_REQUEST_RECEIVED").putExtra("request_id", requestId)
            )
        }
        override fun onContactResponse(pubkey: ByteArray, accepted: Boolean) {
            LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                Intent("ACTION_CONTACT_RESPONSE").apply {
                    putExtra("pubkey", pubkey)
                    putExtra("accepted", accepted)
                }
            )
        }
        override fun onTrackerAnnounce(ok: Boolean, ttl: Int) {
            Log.i(TAG, ">>> onTrackerAnnounce: ok=$ok ttl=$ttl")
            App.app.trackerAnnounced = ok
        }
    }

    companion object {
        const val TAG = "ConnectionService"

        // Ed25519 public key hex for the default tracker (port 69)
        private const val TRACKER_HEX1 = "0000118d965a512ce8a37896957ef15b4108f89a9954ae9365448c6bf049c48d"
        private const val TRACKER_HEX2 = "000044c35636ae819b55ef3f4d5008dd0125fb70baa5fc0f8a94a3671ef8c649"
        private const val TRACKER_PORT = 69
        private const val PEER_PORT: UShort = 42u
        private const val MEDIATOR_PORT: UShort = 42u
        /** How often to retry connecting to contacts with unsent P2P messages. */
        private const val RETRY_SEND_INTERVAL = 2 * 60 * 1000L

        // Default mediator (same as MediatorManager.DEFAULT_MEDIATOR_PUBKEY)
        private const val DEFAULT_MEDIATOR_HEX = "000096390fde19f30d3ba042d77f5f5ce0e6c23bd62ff0afc2e6c29a6fe96b33"

        private const val FILES_PORT: UShort = 80u
        const val DEFAULT_FILE_SERVER_HEX = "0000128c1179f819faaa07fcada9f9803cb288bc68d278da82ca23d8b4b0ea9e"

        // Group member permission flags (must match mediator server)
        private const val PERM_READ_ONLY = 0x08
        private const val PERM_BANNED = 0x01
    }

    private var mimirApp: MimirApp? = null

    // Call state
    private var callingPubkey: ByteArray? = null
    private var activeCallPubkey: ByteArray? = null
    /** Set while we are the initiator of an outgoing call.  Cleared on IN_CALL or HANGUP.
     *  Used to retry startCall() when the peer reconnects mid-call. */
    private var outgoingCallPubkey: ByteArray? = null
    private var activeAudioSender: com.revertron.mimir.calls.AudioSender? = null
    private var activeAudioReceiver: com.revertron.mimir.calls.AudioReceiver? = null

    // Peer status cache for query responses
    private val peerStatuses = HashMap<String, PeerStatus>()

    // subscribedChats and activeFileDownloads are now managed by Rust (MimirApp).
    // Group chat status is queried on demand via MimirApp.getGroupChatStatus().

    private var updateAfter = 0L
    /** True while a retrySendTick is posted to the handler — prevents double-scheduling. */
    @Volatile private var retrySendPending = false
    private lateinit var updaterThread: HandlerThread
    private lateinit var handler: Handler

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        updaterThread = HandlerThread("UpdateThread").apply { start() }
        handler = Handler(updaterThread.looper)
        // Clean up orphaned temp files from interrupted file transfers
        val filesDir = File(filesDir, "files")
        filesDir.listFiles()?.filter { it.name.startsWith(".recv_") && it.name.endsWith(".tmp") }?.forEach {
            Log.i(TAG, "Cleaning up orphaned temp file: ${it.name}")
            it.delete()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val command = intent.getStringExtra("command")
        Log.i(TAG, "Starting service with command $command")

        when (command) {
            "start" -> {
                Log.i(TAG, "Starting service...")
                val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
                if (preferences.getBoolean("enabled", true)) {
                    if (mimirApp == null) {
                        // MimirApp was constructed in App.onCreate (storage-only mode).
                        // This service calls mimirApp.start(...) to light up networking.
                        val app = App.app.mimirApp
                        if (app == null) {
                            Log.e(TAG, "MimirApp not initialized in App.onCreate — aborting service start")
                            return START_NOT_STICKY
                        }
                        val peerProvider = PeerProvider(this)
                        val yggPeers = peerProvider.getPeers()
                        val multicast = preferences.getBoolean("local_peer_discovery", false)
                        val trackers = listOf("$TRACKER_HEX1:$TRACKER_PORT", "$TRACKER_HEX2:$TRACKER_PORT")
                        val mediators = listOf(MediatorManager.getDefaultMediatorPubkey())

                        val n = NotificationHelper.createForegroundServiceNotification(this, State.Offline, "", 0)
                        startForeground(1, n)

                        Thread {
                            try {
                                val config = AppConfig(
                                    yggPeers = yggPeers,
                                    trackers = trackers,
                                    mediators,
                                    peerPort = PEER_PORT,
                                    mediatorPort = MEDIATOR_PORT,
                                    filesPort = FILES_PORT,
                                    autoDownloadContacts = 5L * 1024 * 1024,
                                    autoDownloadOthers = 0L,
                                    autoDownloadGroups = 5L * 1024 * 1024,
                                    multicastEnabled = multicast,
                                    // Share the attachments directory with Rust so outgoing
                                    // attachments (stage_attachment in app/send.rs) and
                                    // incoming P2P file transfers (InfoProvider.get_files_dir)
                                    // land in the same place Android reads from.
                                    attachmentsDir = File(filesDir, "files").absolutePath,
                                )
                                app.start(config, appEventListener)
                                mimirApp = app
                                Log.i(TAG, "MimirApp networking started, pubkey=${Hex.toHexString(app.publicKey().take(4).toByteArray())}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start MimirApp: ${e.message}", e)
                            }
                        }.start()

                        // Notification update loop.
                        Thread {
                            while (true) {
                                mimirApp?.apply {
                                    val currentPeer = this.waitForPeerInfo(15000uL)
                                    if (currentPeer.uri != null && currentPeer.cost > 0u) {
                                        val peerHost = extractHost(currentPeer.uri!!)
                                        val nn = NotificationHelper.createForegroundServiceNotification(this@ConnectionService, State.Online, peerHost, currentPeer.cost.toInt())
                                        startForeground(1, nn)
                                    } else {
                                        val nn = NotificationHelper.createForegroundServiceNotification(this@ConnectionService, State.Offline, "", 0)
                                        startForeground(1, nn)
                                    }
                                } ?: sleep(2000)
                            }
                        }.start()
                    }
                    return START_STICKY
                }
            }

            "refresh_peer" -> {
                val peerProvider = PeerProvider(this)
                val yggPeers = peerProvider.getPeers()
                mimirApp?.setYggPeers(yggPeers)
                mimirApp?.announceToTrackers()
            }

            "connect" -> {
                val pubkey = intent.getByteArrayExtra("pubkey")
                pubkey?.let {
                    try { mimirApp?.connectToContact(it) } catch (e: Exception) {
                        Log.w(TAG, "connect failed: ${e.message}")
                    }
                }
            }

            "send_contact_request" -> {
                val pubkey = intent.getByteArrayExtra("pubkey")
                val message = intent.getStringExtra("message") ?: ""
                pubkey?.let {
                    Thread {
                        try {
                            // Connect first if needed, then send request
                            mimirApp?.connectToContact(it)
                            sleep(2000) // give connection time to establish
                            mimirApp?.sendContactRequest(it, message)
                        } catch (e: Exception) {
                            Log.w(TAG, "sendContactRequest failed: ${e.message}")
                        }
                    }.start()
                }
            }

            "send_contact_response" -> {
                val pubkey = intent.getByteArrayExtra("pubkey")
                val accepted = intent.getBooleanExtra("accepted", false)
                if (pubkey != null) {
                    Thread {
                        try { mimirApp?.sendContactResponse(pubkey, accepted) }
                        catch (e: Exception) { Log.w(TAG, "sendContactResponse failed: ${e.message}") }
                    }.start()
                }
            }

            "call" -> {
                val pubkey = intent.getByteArrayExtra("pubkey")
                pubkey?.let {
                    outgoingCallPubkey = it
                    try {
                        mimirApp?.startCall(it)
                    } catch (e: Exception) {
                        // Peer not connected yet — connect first.
                        // startCall() will be retried in onPeerConnected().
                        Log.d(TAG, "startCall: peer not connected, connecting: ${e.message}")
                        try { mimirApp?.connectToContact(it) } catch (e2: Exception) {
                            Log.w(TAG, "connectToPeer failed: ${e2.message}")
                        }
                    }
                }
            }

            "call_answer" -> {
                Log.i(TAG, "Answering call")
                val pk = callingPubkey
                cancelCallNotifications(this, incoming = true, ongoing = false)
                if (pk != null) {
                    showCallNotification(this, applicationContext, true, pk)
                    try {
                        mimirApp?.answerCall(pk, true)
                    } catch (e: Exception) {
                        Log.w(TAG, "answerCall failed: ${e.message}")
                    }
                }
            }

            "call_decline" -> {
                Log.i(TAG, "Declining call")
                cancelCallNotifications(this, incoming = true, ongoing = true)
                val pk = callingPubkey
                if (pk != null) {
                    try {
                        mimirApp?.answerCall(pk, false)
                    } catch (e: Exception) {
                        Log.w(TAG, "declineCall failed: ${e.message}")
                    }
                }
                appEventListener.onCallStatusChanged(CallStatus.HANGUP, null)
            }

            "call_hangup" -> {
                // Resolve the pubkey of the contact we need to notify.
                // activeCallPubkey is only set after the callee answers (IN_CALL),
                // so for pre-answer hangup we fall back to outgoingCallPubkey (caller)
                // or callingPubkey (callee declining via hangup).
                val pk = activeCallPubkey ?: outgoingCallPubkey ?: callingPubkey
                Log.i(TAG, "Hanging up call, resolved=${pk != null}")
                outgoingCallPubkey = null  // user cancelled — no more retries
                cancelCallNotifications(this, incoming = false, ongoing = true)
                if (pk != null) {
                    try {
                        mimirApp?.hangupCallWith(pk)
                    } catch (e: Exception) {
                        Log.w(TAG, "hangupCall failed: ${e.message}")
                    }
                }
            }

            "incoming_call" -> {
                val pubkey = intent.getByteArrayExtra("pubkey")
                pubkey?.let { showCallNotification(this, applicationContext, false, it) }
            }

            "call_mute" -> {
                val mute = intent.getBooleanExtra("mute", false)
                activeAudioSender?.muteCall(mute)
            }

            "send" -> {
                val pubkey = intent.getByteArrayExtra("pubkey")
                val message = intent.getStringExtra("message")
                val replyTo = intent.getLongExtra("replyTo", 0L)
                val type = intent.getIntExtra("type", 0)

                if (pubkey != null && message != null) {
                    val app = mimirApp ?: return START_STICKY
                    val myPubkey = app.publicKey()
                    val messageBytes = message.toByteArray()
                    val sendTime = getUtcTimeMs()
                    val guid = App.app.mimirApp?.generateGuid(sendTime, messageBytes) ?: sendTime

                    if (pubkey.contentEquals(myPubkey)) {
                        // Saved message — store locally, no network send
                        try {
                            val id = App.app.mimirApp?.addMessage(
                                pubkey, guid, replyTo, false, true, sendTime, 0, type, messageBytes
                            ) ?: 0L
                            LocalBroadcastManager.getInstance(this).sendBroadcast(
                                Intent("ACTION_MESSAGE_SENT").apply {
                                    putExtra("message_id", id)
                                    putExtra("contact_id", -100L)
                                    putExtra("type", type)
                                    putExtra("reply_to", replyTo)
                                }
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to save message: ${e.message}")
                        }
                    } else {
                        Log.i(TAG, "Sending message guid=$guid to ${Hex.toHexString(pubkey).take(8)}")
                        val contactId = App.app.mimirApp?.getContactId(pubkey) ?: 0L
                        Thread {
                            try {
                                app.sendMessage(pubkey, guid, replyTo, sendTime, type, messageBytes)
                                Log.i(TAG, "Message sent (guid=$guid)")
                            } catch (e: Exception) {
                                Log.d(TAG, "Send failed, connecting: ${e.message}")
                                try { app.connectToContact(pubkey) } catch (e2: Exception) {
                                    Log.w(TAG, "connectToPeer failed: ${e2.message}")
                                }
                            }
                            // Show in chat immediately regardless of send result
                            val msgId = App.app.mimirApp?.getMessageByGuid(guid)?.id ?: 0L
                            if (msgId > 0) {
                                LocalBroadcastManager.getInstance(this).sendBroadcast(
                                    Intent("ACTION_MESSAGE_SENT").apply {
                                        putExtra("message_id", msgId)
                                        putExtra("contact_id", contactId)
                                        putExtra("type", type)
                                        putExtra("reply_to", replyTo)
                                    }
                                )
                            }
                        }.start()
                    }
                }
            }

            "request_file" -> {
                val guid = intent.getLongExtra("guid", 0L)
                val name = intent.getStringExtra("name")
                val isGroupChat = intent.getBooleanExtra("group_chat", false)
                val chatId = intent.getLongExtra("chat_id", 0L)
                if (name != null && guid != 0L) {
                    val ms = App.app.mimirApp
                    val metaBytes = if (isGroupChat) {
                        ms?.getGroupMessageByGuid(chatId, guid)?.data
                    } else {
                        ms?.getMessageByGuid(guid)?.data
                    }
                    if (metaBytes == null) {
                        Log.e(TAG, "request_file: message not found for guid=$guid")
                        return START_STICKY
                    }
                    val meta = try { org.json.JSONObject(String(metaBytes)) } catch (_: Exception) {
                        Log.e(TAG, "request_file: invalid metadata for guid=$guid")
                        return START_STICKY
                    }

                    if (meta.has("key")) {
                        // File server download via MimirApp → FilesNode.
                        val destDir = java.io.File(filesDir, "files").apply { mkdirs() }
                        val destPath = java.io.File(destDir, name).absolutePath
                        Thread {
                            try {
                                mimirApp?.downloadGroupFile(chatId, guid, destPath)
                            } catch (e: Exception) {
                                Log.w(TAG, "downloadGroupFile failed: ${e.message}")
                            }
                        }.start()
                    } else {
                        // Legacy P2P download
                        val hash = intent.getStringExtra("hash")
                        val size = intent.getLongExtra("size", 0L)
                        val pubkey = if (!isGroupChat) ms?.getContactByPubkey(intent.getByteArrayExtra("contact_pubkey") ?: ByteArray(0))?.pubkey else null
                        if (hash != null) {
                            val contactPubkey = intent.getByteArrayExtra("contact_pubkey")
                            if (contactPubkey != null) {
                                Thread {
                                    try {
                                        mimirApp?.requestFile(contactPubkey, guid, name, hash, size)
                                    } catch (e: Exception) {
                                        Log.w(TAG, "requestFile failed: ${e.message}")
                                        try { mimirApp?.connectToContact(contactPubkey) } catch (_: Exception) {}
                                    }
                                }.start()
                            }
                        }
                    }
                }
            }

            "online" -> {
                mimirApp?.retryPeersNow()
                mimirApp?.setNetworkOnline(true)

                if (updateAfter == 0L) {
                    handler.postDelayed(10000) { updateTick() }
                }
            }

            "interfaces" -> {
                val raw = intent.getStringArrayExtra("interfaces") ?: emptyArray()
                val interfaces = raw.map { entry ->
                    val parts = entry.split(",", limit = 3)
                    AndroidNetworkInterface(
                        name = parts[0],
                        index = parts[1].toUInt(),
                        addrs = if (parts.size > 2 && parts[2].isNotEmpty()) parts[2].split("|") else emptyList()
                    )
                }
                mimirApp?.updateNetworkInterfaces(interfaces)
            }

            "offline" -> {
                mimirApp?.setNetworkOnline(false)
            }

            "peer_statuses" -> {
                val from = intent.getByteArrayExtra("contact") ?: return START_STICKY
                val contact = Hex.toHexString(from)
                peerStatuses[contact]?.let { status ->
                    LocalBroadcastManager.getInstance(this).sendBroadcast(
                        Intent("ACTION_PEER_STATUS").apply {
                            putExtra("contact", contact)
                            putExtra("status", status)
                        }
                    )
                }
            }

            "update_dismissed" -> {
                val delay = 3600 * 1000L
                updateAfter = System.currentTimeMillis() + delay
                handler.postDelayed(delay) { updateTick() }
            }

            "check_updates" -> {
                updateAfter = System.currentTimeMillis()
                handler.postDelayed(100) { updateTick(true) }
            }

            "restart_update_tick" -> {
                updateAfter = System.currentTimeMillis()
                handler.postDelayed(100) { updateTick() }
            }

            "mediator_create_chat" -> {
                val name = intent.getStringExtra("name")
                val description = intent.getStringExtra("description") ?: ""
                val avatar = intent.getByteArrayExtra("avatar")
                if (name != null) {
                    Thread {
                        try {
                            val chatId = mimirApp?.createGroup(name, description, avatar) ?: 0L
                            if (chatId != 0L) {
                                val mediatorAddress = App.app.mimirApp
                                    ?.getGroupChat(chatId)?.mediatorPubkey
                                    ?: ByteArray(0)
                                LocalBroadcastManager.getInstance(this).sendBroadcast(
                                    Intent("ACTION_MEDIATOR_CHAT_CREATED").apply {
                                        putExtra("chat_id", chatId)
                                        putExtra("name", name)
                                        putExtra("description", description)
                                        putExtra("mediator_address", mediatorAddress)
                                    }
                                )
                            } else {
                                LocalBroadcastManager.getInstance(this).sendBroadcast(
                                    Intent("ACTION_MEDIATOR_ERROR")
                                        .putExtra("operation", "create_chat")
                                        .putExtra("error", "createGroup returned 0")
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "createGroup failed: ${e.message}", e)
                            LocalBroadcastManager.getInstance(this).sendBroadcast(
                                Intent("ACTION_MEDIATOR_ERROR")
                                    .putExtra("operation", "create_chat")
                                    .putExtra("error", e.message ?: "unknown error")
                            )
                        }
                    }.start()
                }
            }

            "mediator_update_chat_info" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val name = intent.getStringExtra("name")
                val description = intent.getStringExtra("description")
                val avatar = intent.getByteArrayExtra("avatar")
                if (chatId != 0L) {
                    Thread {
                        try { mimirApp?.updateGroupInfo(chatId, name, description, avatar) }
                        catch (e: Exception) { Log.e(TAG, "updateGroupInfo failed: ${e.message}") }
                    }.start()
                }
            }

            "mediator_subscribe" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                if (chatId != 0L) {
                    Thread {
                        try { mimirApp?.subscribeToChat(chatId) }
                        catch (e: Exception) { Log.e(TAG, "subscribeToChat failed: ${e.message}") }
                    }.start()
                }
            }

            "mediator_send" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val guid = intent.getLongExtra("guid", System.currentTimeMillis())
                val replyTo = intent.getLongExtra("reply_to", 0)
                val sendTime = intent.getLongExtra("send_time", System.currentTimeMillis())
                val type = intent.getIntExtra("type", 0)
                val message = intent.getStringExtra("message")
                if (chatId != 0L && message != null) {
                    Thread {
                        try {
                            val localId = mimirApp?.sendGroupMessage(chatId, guid, replyTo, sendTime, type, message.toByteArray())
                            LocalBroadcastManager.getInstance(this).sendBroadcast(
                                Intent("ACTION_MEDIATOR_MESSAGE_SENT").apply {
                                    putExtra("chat_id", chatId)
                                    putExtra("message_id", localId ?: 0L)
                                    putExtra("guid", guid)
                                    putExtra("type", type)
                                    putExtra("replyTo", replyTo)
                                }
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "sendGroupMessage failed: ${e.message}")
                        }
                    }.start()
                }
            }

            "mediator_leave" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                if (chatId != 0L) {
                    Thread {
                        try {
                            mimirApp?.leaveGroup(chatId)
                            LocalBroadcastManager.getInstance(this).sendBroadcast(
                                Intent("ACTION_MEDIATOR_LEFT_CHAT").putExtra("chat_id", chatId)
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "leaveGroup failed: ${e.message}", e)
                            LocalBroadcastManager.getInstance(this).sendBroadcast(
                                Intent("ACTION_MEDIATOR_ERROR")
                                    .putExtra("operation", "leave_chat")
                                    .putExtra("chat_id", chatId)
                                    .putExtra("error", e.message ?: "unknown error")
                            )
                        }
                    }.start()
                }
            }

            "mediator_delete" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                if (chatId != 0L) {
                    Thread {
                        try {
                            mimirApp?.deleteGroup(chatId)
                            LocalBroadcastManager.getInstance(this).sendBroadcast(
                                Intent("ACTION_MEDIATOR_CHAT_DELETED").putExtra("chat_id", chatId)
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "deleteGroup failed: ${e.message}", e)
                            LocalBroadcastManager.getInstance(this).sendBroadcast(
                                Intent("ACTION_MEDIATOR_ERROR")
                                    .putExtra("operation", "delete_chat")
                                    .putExtra("chat_id", chatId)
                                    .putExtra("error", e.message ?: "unknown error")
                            )
                        }
                    }.start()
                }
            }

            "mediator_send_invite" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val recipientPubkey = intent.getByteArrayExtra("recipient_pubkey")
                if (chatId != 0L && recipientPubkey != null) {
                    Thread {
                        try { mimirApp?.inviteToGroup(chatId, recipientPubkey) }
                        catch (e: Exception) { Log.e(TAG, "inviteToGroup failed: ${e.message}") }
                    }.start()
                }
            }

            "mediator_accept_invite" -> {
                val inviteId = intent.getLongExtra("invite_id", 0)
                val chatId = intent.getLongExtra("chat_id", 0)
                if (inviteId != 0L && chatId != 0L) {
                    Thread {
                        try {
                            mimirApp?.acceptGroupInvite(inviteId)
                            LocalBroadcastManager.getInstance(this).sendBroadcast(
                                Intent("ACTION_INVITE_ACCEPTED").putExtra("chat_id", chatId)
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "acceptGroupInvite failed: ${e.message}", e)
                            LocalBroadcastManager.getInstance(this).sendBroadcast(
                                Intent("ACTION_MEDIATOR_ERROR")
                                    .putExtra("operation", "add_user")
                                    .putExtra("chat_id", chatId)
                                    .putExtra("error", e.message ?: "unknown error")
                            )
                        }
                    }.start()
                }
            }

            "mediator_ban_user" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val userPubkey = intent.getByteArrayExtra("user_pubkey")
                if (chatId != 0L && userPubkey != null) {
                    Thread {
                        try { mimirApp?.banMember(chatId, userPubkey) }
                        catch (e: Exception) { Log.e(TAG, "banMember failed: ${e.message}") }
                    }.start()
                }
            }

            "mediator_change_role" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val userPubkey = intent.getByteArrayExtra("user_pubkey")
                val permissions = intent.getIntExtra("permissions", 0)
                if (chatId != 0L && userPubkey != null) {
                    Thread {
                        try { mimirApp?.changeMemberRole(chatId, userPubkey, permissions.toUByte()) }
                        catch (e: Exception) { Log.e(TAG, "changeMemberRole failed: ${e.message}") }
                    }.start()
                }
            }

            "mediator_delete_message" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val guid = intent.getLongExtra("guid", 0)
                if (chatId != 0L && guid != 0L) {
                    Thread {
                        try { mimirApp?.deleteGroupMessage(chatId, guid) }
                        catch (e: Exception) { Log.e(TAG, "deleteGroupMessage failed: ${e.message}") }
                    }.start()
                }
            }

            "mediator_register_listener" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                Log.i(TAG, "Registered listener for chat $chatId (no-op in new implementation)")
            }

            "group_chat_status" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                if (chatId != 0L) {
                    val status = mimirApp?.getGroupChatStatus(chatId) ?: "CONNECTING"
                    handler.post { broadcastGroupChatStatus(chatId, status) }
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "ConnectionService destroying")
        stopAudioSession()

        // Tear down networking only. MimirApp itself (and its DB handle) stays
        // alive under App.app.mimirApp so activities can still read storage.
        mimirApp?.stop()
        mimirApp = null

        App.app.online = false
        App.app.trackerAnnounced = false
        App.app.mediatorStatus = App.MediatorStatus.Disconnected

        updaterThread.quitSafely()
        super.onDestroy()
    }

    // ── Old PeerEventListener/MediatorEventListener/helper methods removed ──
    // All business logic now lives in Rust (MimirApp).
    // Only Android-specific helpers remain below.


    private fun startAudioSession(pubkey: ByteArray) {
        stopAudioSession()
        val receiver = com.revertron.mimir.calls.AudioReceiver()
        activeAudioReceiver = receiver
        receiver.start()
        val app = mimirApp
        if (app != null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val sender = com.revertron.mimir.calls.AudioSender(app, pubkey)
            activeAudioSender = sender
            sender.start()
        }
    }

    private fun stopAudioSession() {
        activeAudioSender?.stopSender()
        activeAudioSender = null
        activeAudioReceiver?.stopReceiver()
        activeAudioReceiver = null
    }

    private fun broadcastFileProgress(guid: Long, bytes: Long, total: Long, isUpload: Boolean) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("ACTION_FILE_PROGRESS").apply {
                putExtra("guid", guid)
                putExtra("bytes", bytes)
                putExtra("total", total)
                putExtra("is_upload", isUpload)
            }
        )
    }

    private fun broadcastFileDownloading(guid: Long, name: String, pubkey: ByteArray) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("ACTION_FILE_DOWNLOADING").apply {
                putExtra("guid", guid)
                putExtra("name", name)
                putExtra("pubkey", pubkey)
            }
        )
    }

    private fun broadcastGroupChatStatus(chatId: Long, status: String = "CONNECTING") {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("ACTION_GROUP_CHAT_STATUS").apply {
                putExtra("chat_id", chatId)
                putExtra("status", status)
            }
        )
    }

    private fun broadcastMediatorConnected(mediatorPubkey: ByteArray) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("ACTION_MEDIATOR_CONNECTED").apply {
                putExtra("mediator_pubkey", mediatorPubkey)
            }
        )
    }

    private fun broadcastMediatorError(operation: String, error: String) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("ACTION_MEDIATOR_ERROR").apply {
                putExtra("operation", operation)
                putExtra("error", error)
            }
        )
    }

    private fun preferences() = PreferenceManager.getDefaultSharedPreferences(this.baseContext)

    private fun updateTick(forced: Boolean = false) {
        if (BuildConfig.DEBUG && !forced) {
            Log.i(TAG, "Skipping update check in debug build")
            return
        }
        if (System.currentTimeMillis() >= updateAfter) {
            val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                createWindowContext(display, android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            } else {
                this@ConnectionService
            }
            val updatesEnabled = preferences().getBoolean(SettingsData.KEY_AUTO_UPDATES, true)
            if (updatesEnabled || forced) {
                val delay = if (checkUpdates(windowContext, forced)) 3600 * 1000L else 600 * 1000L
                updateAfter = System.currentTimeMillis() + delay
                handler.postDelayed(delay) { updateTick() }
            }
            // When updates are disabled, don't reschedule. The loop restarts
            // from "online" event or "check_updates" command if the user re-enables.
        }
    }
}

fun connect(context: Context, pubkey: ByteArray) {
    context.startService(
        Intent(context, ConnectionService::class.java)
            .putExtra("command", "connect")
            .putExtra("pubkey", pubkey)
    )
}

fun fetchStatus(context: Context, pubkey: ByteArray) {
    context.startService(
        Intent(context, ConnectionService::class.java)
            .putExtra("command", "peer_statuses")
            .putExtra("contact", pubkey)
    )
}
