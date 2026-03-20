package com.revertron.mimir

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.util.Base64
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.content.edit
import androidx.core.os.postDelayed
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.revertron.mimir.NotificationHelper.Companion.cancelCallNotifications
import com.revertron.mimir.NotificationHelper.Companion.showCallNotification
import com.revertron.mimir.NotificationHelper.Companion.showGroupInviteNotification
import com.revertron.mimir.net.Message
import com.revertron.mimir.net.PeerStatus
import com.revertron.mimir.net.SYS_CHAT_DELETED
import com.revertron.mimir.net.parseAndSaveGroupMessage
import com.revertron.mimir.net.serializeGroupMessage
import com.revertron.mimir.storage.PeerProvider
import com.revertron.mimir.storage.SqlStorage
import com.revertron.mimir.ui.SettingsData
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.util.encoders.Hex
import uniffi.mimir.CallStatus
import uniffi.mimir.ContactInfo
import uniffi.mimir.MemberInfoData
import uniffi.mimir.FilesNode
import uniffi.mimir.MediatorNode
import uniffi.mimir.PeerNode
import uniffi.mimir.decryptMessage as mimirDecryptMessage
import uniffi.mimir.encryptMessage as mimirEncryptMessage
import uniffi.mimir.encryptSharedKey as mimirEncryptSharedKey
import java.util.Collections
import java.io.File
import java.lang.Thread.sleep
import java.nio.ByteBuffer

class ConnectionService : Service(),
    uniffi.mimir.PeerEventListener,
    uniffi.mimir.MediatorEventListener {

    // InfoProvider is a separate object because ContextWrapper already defines getFilesDir(): File,
    // which conflicts with InfoProvider.getFilesDir(): String.
    private val infoProvider = object : uniffi.mimir.InfoProvider {
        override fun getMyInfo(sinceTime: Long): ContactInfo? {
            val info = (application as App).storage.getAccountInfo(1, sinceTime) ?: return null
            var avatar: ByteArray? = null
            if (info.avatar.isNotEmpty()) {
                val f = File(File(filesDir, "avatars"), info.avatar)
                if (f.exists()) avatar = f.readBytes()
            }
            return ContactInfo(
                nickname = info.name,
                info = info.info,
                avatar = avatar,
                updateTime = info.updated
            )
        }
        override fun getContactUpdateTime(pubkey: ByteArray): Long =
            (application as App).storage.getContactUpdateTime(pubkey)
        override fun updateContactInfo(pubkey: ByteArray, info: ContactInfo) {
            val storage = (application as App).storage
            val id = storage.getContactId(pubkey)
            Log.i(TAG, "Updating contact info $id to ${info.nickname}")
            storage.renameContact(id, info.nickname, false)
            storage.updateContactInfo(id, info.info)
            storage.updateContactAvatar(id, info.avatar)
        }
        override fun getFilesDir(): String = "${this@ConnectionService.filesDir.absolutePath}/files"
        override fun getPeerFlags(pubkey: ByteArray): Int {
            val storage = (application as App).storage
            val isContact = storage.getContactId(pubkey) > 0
            if (isContact) return 1
            val prefs = PreferenceManager.getDefaultSharedPreferences(this@ConnectionService)
            val mode = prefs.getString(SettingsData.KEY_ACCEPT_MESSAGES, "everyone")
            return if (mode == "contacts") 0 else 1
        }
    }

    private val filesEventListener = object : uniffi.mimir.FilesEventListener {
        override fun onUploadProgress(fileHash: ByteArray, messageGuid: Long, bytesSent: ULong, totalBytes: ULong) {
            val now = System.currentTimeMillis()
            val last = lastProgressBroadcast[messageGuid] ?: 0L
            if (now - last < 300 && bytesSent < totalBytes) return
            lastProgressBroadcast[messageGuid] = now
            handler.post { broadcastFileProgress(messageGuid, bytesSent.toLong(), totalBytes.toLong(), isUpload = true) }
        }
        override fun onUploadComplete(fileHash: ByteArray, messageGuid: Long) {
            Log.i(TAG, "File upload complete: ${Hex.toHexString(fileHash)}")
            lastProgressBroadcast.remove(messageGuid)
            handler.post {
                LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                    Intent("ACTION_FILE_UPLOADED").apply { putExtra("guid", messageGuid) }
                )
            }
        }
        override fun onUploadError(fileHash: ByteArray, messageGuid: Long, error: String) {
            Log.e(TAG, "File upload error: $error")
            lastProgressBroadcast.remove(messageGuid)
            handler.post {
                LocalBroadcastManager.getInstance(this@ConnectionService).sendBroadcast(
                    Intent("ACTION_FILE_UPLOADED").apply { putExtra("guid", messageGuid) }
                )
            }
        }
        override fun onDownloadProgress(fileHash: ByteArray, messageGuid: Long, bytesReceived: ULong, totalBytes: ULong) {
            val now = System.currentTimeMillis()
            val last = lastProgressBroadcast[messageGuid] ?: 0L
            if (now - last < 300 && bytesReceived < totalBytes) return
            lastProgressBroadcast[messageGuid] = now
            handler.post { broadcastFileProgress(messageGuid, bytesReceived.toLong(), totalBytes.toLong(), isUpload = false) }
        }
        override fun onDownloadComplete(fileHash: ByteArray, messageGuid: Long, filePath: String) {
            Log.i(TAG, "File download complete: $filePath")
        }
        override fun onDownloadError(fileHash: ByteArray, messageGuid: Long, error: String) {
            Log.e(TAG, "File download error: $error")
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

    private var peerNode: PeerNode? = null
    private var mediatorNode: MediatorNode? = null
    private var filesNode: FilesNode? = null

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

    // Tracks which group chat IDs are currently subscribed on the mediator.
    // Updated by onConnected, onDisconnected, and subscribeToChat.
    private val subscribedChats = Collections.synchronizedSet(mutableSetOf<Long>())

    /** File names currently being downloaded — prevents duplicate requestFile() calls. */
    private val activeFileDownloads = Collections.synchronizedSet(mutableSetOf<String>())
    /** Throttle: guid → last broadcast timestamp (ms). */
    private val lastProgressBroadcast = java.util.concurrent.ConcurrentHashMap<Long, Long>()

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
        val storage = (application as App).storage

        when (command) {
            "start" -> {
                Log.i(TAG, "Starting service...")
                val preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)
                if (preferences.getBoolean("enabled", true)) {
                    if (peerNode == null) {
                        Log.i(TAG, "Starting PeerNode...")
                        var accountInfo = storage.getAccountInfo(1, 0L)
                        if (accountInfo == null) {
                            accountInfo = storage.generateNewAccount()
                        }
                        val pubkey = storage.getMyPubKey()
                        Log.i(TAG, "Got account ${accountInfo.name} with pubkey ${Hex.toHexString(pubkey)}")

                        val seed = (accountInfo.keyPair.private as Ed25519PrivateKeyParameters).encoded
                        val peerProvider = PeerProvider(this)
                        val yggPeers = peerProvider.getPeers()
                        val trackers = listOf("$TRACKER_HEX1:$TRACKER_PORT", "$TRACKER_HEX2:$TRACKER_PORT")

                        Thread {
                            sleep(250)
                            try {
                                val node = PeerNode(seed, yggPeers, PEER_PORT, trackers, this, infoProvider)
                                peerNode = node
                                App.app.peerNode = node

                                val mNode = MediatorNode(node, MEDIATOR_PORT, this)
                                mediatorNode = mNode
                                App.app.mediatorNode = mNode

                                val fNode = FilesNode(node, FILES_PORT, filesEventListener)
                                filesNode = fNode
                                App.app.filesNode = fNode

                                node.announceToTrackers()

                                if (haveNetwork(this)) {
                                    connectAndSubscribeToAllChats(storage)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start PeerNode: ${e.message}", e)
                            }
                        }.start()

                        Thread {
                            sleep(1000)
                            App.app.peerNode?.apply {
                                val currentPeer = this.waitForPeerInfo(5000uL)
                                if (currentPeer.uri != null && currentPeer.cost > 0uL) {
                                    val peerHost = extractHost(currentPeer.uri!!)
                                    val n = NotificationHelper.createForegroundServiceNotification(this@ConnectionService, State.Online, peerHost, currentPeer.cost.toInt())
                                    startForeground(1, n)
                                } else {
                                    val n = NotificationHelper.createForegroundServiceNotification(this@ConnectionService, State.Offline, "", 0)
                                    startForeground(1, n)
                                }
                            }
                            while (true) {
                                App.app.peerNode?.apply {
                                    val currentPeer = this.waitForPeerInfo(15000uL)
                                    if (currentPeer.uri != null && currentPeer.cost > 0uL) {
                                        val peerHost = extractHost(currentPeer.uri!!)
                                        val n = NotificationHelper.createForegroundServiceNotification(this@ConnectionService, State.Online, peerHost, currentPeer.cost.toInt())
                                        startForeground(1, n)
                                    } else {
                                        val n = NotificationHelper.createForegroundServiceNotification(this@ConnectionService, State.Offline, "", 0)
                                        startForeground(1, n)
                                    }
                                }
                            }
                        }.start()

                        val n = NotificationHelper.createForegroundServiceNotification(this, State.Offline, "", 0)
                        startForeground(1, n)
                    }
                    return START_STICKY
                }
            }

            "refresh_peer" -> {
                val peerProvider = PeerProvider(this)
                val yggPeers = peerProvider.getPeers()
                peerNode?.setYggPeers(yggPeers)
                peerNode?.announceToTrackers()
            }

            "connect" -> {
                val pubkey = intent.getByteArrayExtra("pubkey")
                pubkey?.let {
                    try { peerNode?.connectToPeer(it) } catch (e: Exception) {
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
                            peerNode?.connectToPeer(it)
                            sleep(2000) // give connection time to establish
                            peerNode?.sendContactRequest(it, message)
                        } catch (e: Exception) {
                            Log.w(TAG, "sendContactRequest failed: ${e.message}")
                        }
                    }.start()
                }
            }

            "call" -> {
                val pubkey = intent.getByteArrayExtra("pubkey")
                pubkey?.let {
                    outgoingCallPubkey = it
                    try {
                        peerNode?.startCall(it)
                    } catch (e: Exception) {
                        // Peer not connected yet — connect first.
                        // startCall() will be retried in onPeerConnected().
                        Log.d(TAG, "startCall: peer not connected, connecting: ${e.message}")
                        try { peerNode?.connectToPeer(it) } catch (e2: Exception) {
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
                        peerNode?.answerCall(pk, true)
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
                        peerNode?.answerCall(pk, false)
                    } catch (e: Exception) {
                        Log.w(TAG, "declineCall failed: ${e.message}")
                    }
                }
                onCallStatusChanged(CallStatus.HANGUP, null)
            }

            "call_hangup" -> {
                Log.i(TAG, "Hanging up call")
                outgoingCallPubkey = null  // user cancelled — no more retries
                cancelCallNotifications(this, incoming = false, ongoing = true)
                val pk = activeCallPubkey
                if (pk != null) {
                    try {
                        peerNode?.hangupCall(pk)
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
                    val myPubkey = storage.getMyPubKey()
                    val isSavedMessage = pubkey.contentEquals(myPubkey)

                    if (isSavedMessage) {
                        val messageBytes = message.toByteArray()
                        val db = storage.writableDatabase
                        val values = ContentValues().apply {
                            put("contact", SqlStorage.SAVED_MESSAGES_CONTACT_ID)
                            put("guid", storage.generateGuid(getUtcTimeMs(), messageBytes))
                            put("replyTo", replyTo)
                            put("incoming", false)
                            put("delivered", true)
                            put("time", getUtcTimeMs())
                            put("edit", 0)
                            put("type", type)
                            put("message", messageBytes)
                            put("read", true)
                        }
                        val id = db.insert("messages", null, values)
                        for (listener in storage.listeners) {
                            listener.onMessageSent(id, SqlStorage.SAVED_MESSAGES_CONTACT_ID, type, replyTo)
                        }
                    } else {
                        val messageBytes = message.toByteArray()
                        val sendTime = getUtcTimeMs()
                        val guid = storage.generateGuid(sendTime, messageBytes)
                        val id = storage.addMessage(pubkey, guid, replyTo, false, false, sendTime, 0, type, messageBytes)
                        Log.i(TAG, "Message $id (guid=$guid) to ${Hex.toHexString(pubkey).take(8)}")
                        Thread {
                            try {
                                // For file attachments, upload to file server first
                                val sendPayload = if (type == 1 || type == 3) {
                                    uploadP2PFileToServer(guid, messageBytes, storage)
                                } else {
                                    messageBytes
                                }
                                // Send immediately if already connected
                                peerNode?.sendMessage(pubkey, guid, replyTo, sendTime, 0L, type, sendPayload)
                                Log.i(TAG, "Message $id sent directly (peer connected)")
                            } catch (e: Exception) {
                                // Peer not connected yet — connect; sendPendingP2PMessages will send it
                                Log.d(TAG, "Not connected, connecting to send msg $id: ${e.message}")
                                try { peerNode?.connectToPeer(pubkey) } catch (e2: Exception) {
                                    Log.w(TAG, "connectToPeer failed: ${e2.message}")
                                }
                                // Kick off the periodic retry loop (no-op if already running).
                                scheduleRetrySend(60_000L)
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
                    // Get message metadata from DB
                    val storage = (application as App).storage
                    val metaBytes = if (isGroupChat) {
                        storage.getGroupMessage(chatId, guid, byGuid = true)?.data
                    } else {
                        storage.getMessage(guid, byGuid = true)?.data
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
                        // File server download — key is stored raw (32 bytes).
                        // Legacy messages may have a wrapped key (>32 bytes); unwrap those.
                        val keyBytes = Base64.decode(meta.getString("key"), Base64.NO_WRAP)
                        val rawKey = if (keyBytes.size > 32 && isGroupChat) {
                            val chatInfo = storage.getGroupChat(chatId)
                            if (chatInfo == null) {
                                Log.e(TAG, "request_file: group chat $chatId not found")
                                return START_STICKY
                            }
                            mimirDecryptMessage(keyBytes, chatInfo.sharedKey)
                        } else {
                            keyBytes
                        }
                        val serverPubkey = Hex.decode(meta.getString("server"))
                        val fileHash = Hex.decode(meta.getString("hash"))

                        if (!activeFileDownloads.add(name)) {
                            Log.i(TAG, "File $name already downloading, skipping")
                            return START_STICKY
                        }
                        broadcastFileDownloading(guid, name, ByteArray(0))
                        Thread {
                            try {
                                val filesDir = File(this.filesDir, "files")
                                filesDir.mkdirs()
                                val destPath = File(filesDir, name).absolutePath
                                filesNode!!.downloadFile(serverPubkey, fileHash, guid, destPath, rawKey)
                                val cacheDir = File(this.cacheDir, "files")
                                cacheDir.mkdirs()
                                createImagePreview(destPath, File(cacheDir, name).absolutePath, 512, 80)
                                activeFileDownloads.remove(name)
                                Log.i(TAG, "File downloaded from server: $name")
                                LocalBroadcastManager.getInstance(this).sendBroadcast(
                                    Intent("ACTION_FILE_DOWNLOADED").apply { putExtra("guid", guid) }
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "File server download failed: ${e.message}", e)
                                activeFileDownloads.remove(name)
                                LocalBroadcastManager.getInstance(this).sendBroadcast(
                                    Intent("ACTION_FILE_DOWNLOADED").apply { putExtra("guid", guid) }
                                )
                            }
                        }.start()
                    } else {
                        // Legacy P2P download
                        val hash = intent.getStringExtra("hash")
                        val size = intent.getLongExtra("size", 0L)
                        val pubkey = if (!isGroupChat) storage.getContactPubkey(chatId) else null
                        if (pubkey != null && hash != null) {
                            if (!activeFileDownloads.add(name)) {
                                Log.i(TAG, "File $name is already being downloaded, skipping")
                            } else {
                                broadcastFileDownloading(guid, name, pubkey)
                                Thread {
                                    try {
                                        peerNode?.requestFile(pubkey, guid, name, hash, size)
                                        Log.i(TAG, "Requested file $name (guid=$guid) from ${Hex.toHexString(pubkey).take(8)}")
                                    } catch (e: Exception) {
                                        Log.w(TAG, "requestFile failed: ${e.message}")
                                        activeFileDownloads.remove(name)
                                        try { peerNode?.connectToPeer(pubkey) } catch (_: Exception) {}
                                    }
                                }.start()
                            }
                        }
                    }
                }
            }

            "online" -> {
                peerNode?.retryPeersNow()
                peerNode?.setNetworkOnline(true)

                Thread {
                    sleep(3000)
                    peerNode?.announceToTrackers()
                    if (updateAfter == 0L) {
                        handler.postDelayed(1000) { updateTick() }
                    }
                }.start()
            }

            "offline" -> {
                peerNode?.setNetworkOnline(false)
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

            "mediator_create_chat" -> {
                val name = intent.getStringExtra("name")
                val description = intent.getStringExtra("description") ?: ""
                val avatar = intent.getByteArrayExtra("avatar")
                if (name != null) {
                    Thread { createChat(name, description, avatar) }.start()
                }
            }

            "mediator_update_chat_info" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val name = intent.getStringExtra("name")
                val description = intent.getStringExtra("description")
                val avatar = intent.getByteArrayExtra("avatar")
                if (chatId != 0L) {
                    Thread { updateChatInfo(chatId, name, description, avatar) }.start()
                }
            }

            "mediator_subscribe" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                if (chatId != 0L) {
                    Thread { subscribeToChat(chatId, storage) }.start()
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
                        sendGroupChatMessage(chatId, storage, guid, replyTo, sendTime, type, message)
                    }.start()
                }
            }

            "mediator_leave" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                if (chatId != 0L) {
                    Thread { leaveGroupChat(chatId) }.start()
                }
            }

            "mediator_delete" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                if (chatId != 0L) {
                    Thread { deleteGroupChat(chatId) }.start()
                }
            }

            "mediator_send_invite" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val recipientPubkey = intent.getByteArrayExtra("recipient_pubkey")
                if (chatId != 0L && recipientPubkey != null) {
                    Thread { sendInviteToGroupChat(chatId, recipientPubkey) }.start()
                }
            }

            "mediator_accept_invite" -> {
                val inviteId = intent.getLongExtra("invite_id", 0)
                val chatId = intent.getLongExtra("chat_id", 0)
                if (inviteId != 0L && chatId != 0L) {
                    Thread { acceptInviteAndSubscribe(inviteId, chatId, storage) }.start()
                }
            }

            "mediator_ban_user" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val userPubkey = intent.getByteArrayExtra("user_pubkey")
                if (chatId != 0L && userPubkey != null) {
                    Thread { banUserFromGroupChat(chatId, userPubkey) }.start()
                }
            }

            "mediator_change_role" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val userPubkey = intent.getByteArrayExtra("user_pubkey")
                val permissions = intent.getIntExtra("permissions", 0)
                if (chatId != 0L && userPubkey != null) {
                    Thread { changeMemberRole(chatId, userPubkey, permissions) }.start()
                }
            }

            "mediator_delete_message" -> {
                val chatId = intent.getLongExtra("chat_id", 0)
                val guid = intent.getLongExtra("guid", 0)
                if (chatId != 0L && guid != 0L) {
                    Thread {
                        try {
                            val chatInfo = storage.getGroupChat(chatId) ?: return@Thread
                            mediatorNode?.deleteMessage(chatInfo.mediatorPubkey, chatId, guid)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to delete message from mediator: ${e.message}")
                        }
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
                    val chatInfo = storage.getGroupChat(chatId)
                    val status = when {
                        chatInfo?.readOnly == true -> "READ_ONLY"
                        subscribedChats.contains(chatId) -> "SUBSCRIBED"
                        else -> "CONNECTING"
                    }
                    handler.post { broadcastGroupChatStatus(chatId, storage, status) }
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "ConnectionService destroying")
        stopAudioSession()

        filesNode?.stop()
        filesNode = null
        App.app.filesNode = null

        mediatorNode?.stop()
        mediatorNode = null
        App.app.mediatorNode = null

        peerNode?.stop()
        peerNode = null
        App.app.peerNode = null

        App.app.online = false
        App.app.trackerAnnounced = false
        App.app.mediatorConnected = false

        updaterThread.quitSafely()
        super.onDestroy()
    }

    // ── PeerEventListener ─────────────────────────────────────────────────────

    override fun onConnectivityChanged(isOnline: Boolean) {
        Log.i(TAG, "onConnectivityChanged: $isOnline")
        App.app.online = isOnline
        val state = if (isOnline) State.Online else State.Offline
        val n = NotificationHelper.createForegroundServiceNotification(this, state, "", 0)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, n)

        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("ACTION_CONNECTIVITY_CHANGED").putExtra("is_online", isOnline)
        )

        if (isOnline) {
            preferences().edit {
                putLong("trackerPingTime", getUtcTime())
                apply()
            }
            val storage = (application as App).storage
            Thread { connectAndSubscribeToAllChats(storage) }.start()
        } else {
            App.app.trackerAnnounced = false
            // Do NOT clear mediatorConnected here. The mediator connection may
            // survive a brief Yggdrasil peer switch (the socket stays alive).
            // When Yggdrasil is offline the mediator dot is already shown red by
            // updateStatusTitle() regardless of mediatorConnected, so clearing it
            // here only causes a stale "red" after connectivity is restored.
            // The real onDisconnected callback from MediatorEventListener handles
            // setting mediatorConnected = false when the connection actually drops.
        }
    }

    override fun onPeerConnected(pubkey: ByteArray, address: String) {
        val contact = Hex.toHexString(pubkey)
        Log.i(TAG, "onPeerConnected: $contact")

        val storage = (application as App).storage
        val expiration = getUtcTime() + IP_CACHE_DEFAULT_TTL
        storage.saveIp(pubkey, address, 0, 0, 0, expiration)

        peerStatuses[contact] = PeerStatus.Connected
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("ACTION_PEER_STATUS").apply {
                putExtra("contact", contact)
                putExtra("status", PeerStatus.Connected)
            }
        )

        // If we were the initiator of a call to this peer (and the connection
        // was replaced mid-call by a fresh reconnect), retry startCall() so the
        // call offer is sent over the new connection.
        val callPubkey = outgoingCallPubkey
        if (callPubkey != null && callPubkey.contentEquals(pubkey)) {
            Log.i(TAG, "Retrying startCall() on new connection to ${contact.take(8)}")
            try { peerNode?.startCall(pubkey) } catch (e: Exception) {
                Log.w(TAG, "retry startCall failed: ${e.message}")
            }
        }

        // Send any pending P2P messages for this peer
        Thread { sendPendingP2PMessages(pubkey, storage) }.start()
    }

    override fun onPeerDisconnected(pubkey: ByteArray, address: String, deadPeer: Boolean) {
        val contact = Hex.toHexString(pubkey)
        Log.i(TAG, "onPeerDisconnected: $contact, dead=$deadPeer")
        val status = if (deadPeer) PeerStatus.ErrorConnecting else PeerStatus.NotConnected
        peerStatuses[contact] = status
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("ACTION_PEER_STATUS").apply {
                putExtra("contact", contact)
                putExtra("status", status)
            }
        )
    }

    override fun onMessageReceived(
        pubkey: ByteArray, guid: Long, replyTo: Long, sendTime: Long,
        editTime: Long, msgType: Int, data: ByteArray
    ) {
        val storage = (application as App).storage

        if (msgType == 1 || msgType == 3) {
            // Attachment metadata only (no file bytes in the new protocol).
            // Store the JSON metadata; the file will be downloaded on demand.
            storage.addMessage(pubkey, guid, replyTo, true, true, sendTime, editTime, msgType, data)
            // Check for file-server format (has "key" field) — download from server
            val hasServerKey = try {
                org.json.JSONObject(String(data)).has("key")
            } catch (_: Exception) { false }
            if (hasServerKey) {
                maybeAutoDownloadFromServer(pubkey, guid, data, storage)
            } else {
                // Legacy P2P format — request file directly from peer
                maybeAutoDownloadFile(pubkey, guid, data, storage)
            }
        } else {
            storage.addMessage(pubkey, guid, replyTo, true, true, sendTime, editTime, msgType, data)
        }
    }

    override fun onFileReceived(
        pubkey: ByteArray, guid: Long, replyTo: Long, sendTime: Long,
        editTime: Long, msgType: Int, metaJson: String, filePath: String
    ) {
        try {
            val json = org.json.JSONObject(metaJson)
            val name = json.getString("name")
            val declaredSize = json.optLong("size", -1)

            val tempFile = File(filePath)
            if (!tempFile.exists()) {
                Log.w(TAG, "onFileReceived: temp file $filePath does not exist")
                activeFileDownloads.remove(name)
                return
            }

            // Size validation
            if (declaredSize >= 0 && tempFile.length() != declaredSize) {
                Log.w(TAG, "File size mismatch for $name: declared=$declaredSize, actual=${tempFile.length()}. Discarding.")
                tempFile.delete()
                activeFileDownloads.remove(name)
                return
            }

            // Move temp file to final location (same filesystem, so renameTo should work)
            val filesDir = File(this.filesDir, "files")
            filesDir.mkdirs()
            val finalFile = File(filesDir, name)
            if (!tempFile.renameTo(finalFile)) {
                // renameTo can fail across filesystems; fall back to copy+delete
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            // Generate image preview for large images
            val cacheDir = File(this.cacheDir, "files")
            cacheDir.mkdirs()
            val previewFile = File(cacheDir, name)
            createImagePreview(finalFile.absolutePath, previewFile.absolutePath, 512, 80)

            activeFileDownloads.remove(name)
            Log.i(TAG, "File received: $name (${finalFile.length()} bytes)")

            // Notify the UI that this file is now available.
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent("ACTION_FILE_DOWNLOADED").apply {
                    putExtra("guid", guid)
                    putExtra("pubkey", pubkey)
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle file received: ${e.message}")
            // Clean up temp file
            try { File(filePath).delete() } catch (_: Exception) {}
        }
    }

    /**
     * Check auto-download settings and request the file immediately if allowed.
     */
    private fun maybeAutoDownloadFile(pubkey: ByteArray, msgGuid: Long, metaBytes: ByteArray, storage: SqlStorage) {
        try {
            val json = org.json.JSONObject(String(metaBytes))
            val name = json.getString("name")
            val hash = json.getString("hash")
            val size = json.optLong("size", 0)

            // Check if file already exists (e.g. we sent it ourselves)
            val file = File(File(filesDir, "files"), name)
            if (file.exists()) return

            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val isContact = storage.getContactId(pubkey) >= 0
            val threshold = if (isContact) {
                prefs.getString("auto-download-contacts", "5242880")?.toLongOrNull() ?: 5242880L
            } else {
                prefs.getString("auto-download-others", "0")?.toLongOrNull() ?: 0L
            }

            if (size <= 0L) return  // Don't auto-download files with missing/zero size
            if (threshold == 0L) return
            if (threshold > 0 && size > threshold) return

            // Peer must be connected to request file.
            val contact = Hex.toHexString(pubkey)
            val status = peerStatuses[contact]
            if (status != PeerStatus.Connected) return

            if (!activeFileDownloads.add(name)) {
                Log.i(TAG, "File $name is already being downloaded, skipping auto-download")
                return
            }
            Log.i(TAG, "Auto-downloading file $name ($size bytes) from ${contact.take(8)}")
            broadcastFileDownloading(msgGuid, name, pubkey)
            Thread {
                try {
                    peerNode?.requestFile(pubkey, msgGuid, name, hash, size)
                } catch (e: Exception) {
                    Log.w(TAG, "requestFile failed: ${e.message}")
                    activeFileDownloads.remove(name)
                }
            }.start()
        } catch (e: Exception) {
            Log.w(TAG, "maybeAutoDownloadFile failed: ${e.message}")
        }
    }

    /**
     * Download a file from the file server (new format with key/server fields).
     * Respects the same auto-download size thresholds as P2P downloads.
     */
    private fun maybeAutoDownloadFromServer(pubkey: ByteArray, msgGuid: Long, metaBytes: ByteArray, storage: SqlStorage) {
        try {
            val json = org.json.JSONObject(String(metaBytes))
            val name = json.getString("name")
            val size = json.optLong("size", 0)

            // Check if file already exists
            val file = File(File(filesDir, "files"), name)
            if (file.exists()) return

            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val isContact = storage.getContactId(pubkey) >= 0
            val threshold = if (isContact) {
                prefs.getString("auto-download-contacts", "5242880")?.toLongOrNull() ?: 5242880L
            } else {
                prefs.getString("auto-download-others", "0")?.toLongOrNull() ?: 0L
            }

            if (size <= 0L) return
            if (threshold == 0L) return
            if (threshold > 0 && size > threshold) return

            downloadFileFromServer(pubkey, msgGuid, metaBytes, storage)
        } catch (e: Exception) {
            Log.w(TAG, "maybeAutoDownloadFromServer failed: ${e.message}")
        }
    }

    /**
     * Check auto-download settings for group chats and download the file from the file server
     * if the size is within the threshold.
     */
    private fun maybeAutoDownloadGroupFile(chatId: Long, msgGuid: Long, storage: SqlStorage) {
        try {
            val msg = storage.getGroupMessage(chatId, msgGuid, byGuid = true) ?: return
            if (msg.type != 1 && msg.type != 3) return
            val metaBytes = msg.data ?: return

            val json = org.json.JSONObject(String(metaBytes))
            if (!json.has("key")) return  // Only file-server format supports group auto-download
            val name = json.getString("name")
            val size = json.optLong("size", 0)

            // Check if file already exists
            val file = File(File(filesDir, "files"), name)
            if (file.exists()) return

            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val threshold = prefs.getString(SettingsData.KEY_AUTO_DOWNLOAD_GROUPS, "5242880")?.toLongOrNull() ?: 5242880L

            if (size <= 0L) return
            if (threshold == 0L) return
            if (threshold > 0 && size > threshold) return

            // Key is stored raw (32 bytes). Legacy messages may have a wrapped key (>32 bytes).
            val keyBytes = Base64.decode(json.getString("key"), Base64.NO_WRAP)
            val rawKey = if (keyBytes.size > 32) {
                val chatInfo = storage.getGroupChat(chatId) ?: return
                mimirDecryptMessage(keyBytes, chatInfo.sharedKey)
            } else {
                keyBytes
            }
            val serverPubkey = Hex.decode(json.getString("server"))
            val fileHash = Hex.decode(json.getString("hash"))

            if (!activeFileDownloads.add(name)) {
                Log.i(TAG, "File $name is already being downloaded, skipping group auto-download")
                return
            }
            Log.i(TAG, "Auto-downloading group file $name ($size bytes) for chat $chatId")
            broadcastFileDownloading(msgGuid, name, ByteArray(0))
            Thread {
                try {
                    val filesDir = File(this.filesDir, "files")
                    filesDir.mkdirs()
                    val destPath = File(filesDir, name).absolutePath
                    filesNode!!.downloadFile(serverPubkey, fileHash, msgGuid, destPath, rawKey)
                    val cacheDir = File(this.cacheDir, "files")
                    cacheDir.mkdirs()
                    createImagePreview(destPath, File(cacheDir, name).absolutePath, 512, 80)
                    activeFileDownloads.remove(name)
                    Log.i(TAG, "Group file downloaded: $name")
                    LocalBroadcastManager.getInstance(this).sendBroadcast(
                        Intent("ACTION_FILE_DOWNLOADED").apply { putExtra("guid", msgGuid) }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Group file auto-download failed: ${e.message}")
                    activeFileDownloads.remove(name)
                }
            }.start()
        } catch (e: Exception) {
            Log.w(TAG, "maybeAutoDownloadGroupFile failed: ${e.message}")
        }
    }

    /**
     * Downloads a file from the file server using metadata with key/server fields.
     * Runs the download on a background thread.
     */
    private fun downloadFileFromServer(pubkey: ByteArray, msgGuid: Long, metaBytes: ByteArray, storage: SqlStorage) {
        try {
            val json = org.json.JSONObject(String(metaBytes))
            val name = json.getString("name")

            if (!activeFileDownloads.add(name)) {
                Log.i(TAG, "File $name is already being downloaded, skipping")
                return
            }
            broadcastFileDownloading(msgGuid, name, pubkey)
            Thread {
                try {
                    val fileKey = Base64.decode(json.getString("key"), Base64.NO_WRAP)
                    val serverPubkey = Hex.decode(json.getString("server"))
                    val fileHash = Hex.decode(json.getString("hash"))
                    val filesDir = File(this.filesDir, "files")
                    filesDir.mkdirs()
                    val destPath = File(filesDir, name).absolutePath

                    filesNode?.downloadFile(serverPubkey, fileHash, msgGuid, destPath, fileKey)

                    // Generate image preview
                    val cacheDir = File(this.cacheDir, "files")
                    cacheDir.mkdirs()
                    val previewFile = File(cacheDir, name)
                    createImagePreview(destPath, previewFile.absolutePath, 512, 80)

                    activeFileDownloads.remove(name)
                    Log.i(TAG, "File downloaded from server: $name")

                    LocalBroadcastManager.getInstance(this).sendBroadcast(
                        Intent("ACTION_FILE_DOWNLOADED").apply {
                            putExtra("guid", msgGuid)
                            putExtra("pubkey", pubkey)
                        }
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "downloadFileFromServer failed: ${e.message}")
                    activeFileDownloads.remove(name)
                }
            }.start()
        } catch (e: Exception) {
            Log.w(TAG, "downloadFileFromServer parse failed: ${e.message}")
        }
    }

    /**
     * Uploads a P2P file attachment to the file server and returns updated metadata with
     * key/server/hash fields (raw key, not wrapped).
     * Checks if already uploaded for this guid by verifying the key length is raw (32 bytes).
     * Updates the local DB with the new metadata.
     */
    private fun uploadP2PFileToServer(guid: Long, metaBytes: ByteArray, storage: SqlStorage): ByteArray {
        val meta = org.json.JSONObject(String(metaBytes))
        // Already uploaded — skip re-upload
        if (meta.has("key")) {
            return metaBytes
        }
        val fileKey = uniffi.mimir.generateSharedKey()
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val serverPubkey = Hex.decode(preferences.getString(SettingsData.KEY_FILE_SERVER_PUBKEY, DEFAULT_FILE_SERVER_HEX)!!)
        val fileName = meta.getString("name")
        val filePath = File(filesDir, "files/$fileName").absolutePath
        val fileHash = filesNode!!.uploadFile(serverPubkey, filePath, guid, fileKey)
        // Add file server fields — raw key for P2P (no wrapping)
        meta.put("key", Base64.encodeToString(fileKey, Base64.NO_WRAP))
        meta.put("server", Hex.toHexString(serverPubkey))
        meta.put("hash", Hex.toHexString(fileHash))
        val updatedBytes = meta.toString().toByteArray()
        storage.updateMessageData(guid, updatedBytes)
        return updatedBytes
    }

    override fun onMessageDelivered(pubkey: ByteArray, guid: Long) {
        val guidL = guid
        Log.i(TAG, "onMessageDelivered: guid=$guidL from ${Hex.toHexString(pubkey).take(8)}")
        (application as App).storage.setMessageDelivered(pubkey, guidL, true)
    }

    override fun onIncomingCall(pubkey: ByteArray) {
        Log.i(TAG, "onIncomingCall from ${Hex.toHexString(pubkey).take(8)}")
        callingPubkey = pubkey
        showCallNotification(this, applicationContext, false, pubkey)
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("ACTION_INCOMING_CALL").apply { putExtra("pubkey", pubkey) }
        )
    }

    override fun onCallStatusChanged(status: CallStatus, pubkey: ByteArray?) {
        Log.i(TAG, "onCallStatusChanged: $status")
        when (status) {
            CallStatus.IN_CALL -> {
                outgoingCallPubkey = null  // call connected — no more retries needed
                activeCallPubkey = pubkey
                if (pubkey != null) startAudioSession(pubkey)
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("ACTION_IN_CALL_START"))
                pubkey?.let { showCallNotification(this, applicationContext, true, it) }
            }
            CallStatus.HANGUP, CallStatus.IDLE -> {
                outgoingCallPubkey = null  // call ended
                stopAudioSession()
                activeCallPubkey = null
                callingPubkey = null
                cancelCallNotifications(this, incoming = true, ongoing = true)
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent("ACTION_FINISH_CALL"))
            }
            CallStatus.CALLING -> {
                // Outgoing call initiated; UI handles this via broadcast
                LocalBroadcastManager.getInstance(this).sendBroadcast(
                    Intent("ACTION_CALL_STATUS").apply {
                        putExtra("status", status.name)
                        putExtra("pubkey", pubkey)
                    }
                )
            }
            CallStatus.RECEIVING -> {
                // Incoming call being received; already handled in onIncomingCall
            }
        }
    }

    override fun onCallPacket(pubkey: ByteArray, data: ByteArray) {
        activeAudioReceiver?.receivePacket(data)
    }

    override fun onFileReceiveProgress(pubkey: ByteArray, guid: Long, bytesReceived: Long, totalBytes: Long) {
        Log.d(TAG, "File receive progress $bytesReceived/$totalBytes")
        // Throttle early on the callback thread to avoid unnecessary handler posts
        val now = System.currentTimeMillis()
        val isFinal = bytesReceived >= totalBytes
        val last = lastProgressBroadcast[guid] ?: 0L
        if (!isFinal && now - last < 300) return
        lastProgressBroadcast[guid] = now
        // Post DB lookup + broadcast to handler thread so Rust callback returns immediately
        handler.post { broadcastFileProgress(guid, bytesReceived, totalBytes, isUpload = false) }
    }

    override fun onFileSendProgress(pubkey: ByteArray, guid: Long, bytesSent: Long, totalBytes: Long) {
        Log.d(TAG, "File send progress $bytesSent/$totalBytes")
        val now = System.currentTimeMillis()
        val isFinal = bytesSent >= totalBytes
        val last = lastProgressBroadcast[guid] ?: 0L
        if (!isFinal && now - last < 300) return
        lastProgressBroadcast[guid] = now
        handler.post { broadcastFileProgress(guid, bytesSent, totalBytes, isUpload = true) }
    }

    /**
     * Resolves a transfer guid to a file name, then broadcasts progress.
     * Runs on the handler thread (not on the Rust callback thread).
     */
    private fun broadcastFileProgress(guid: Long, bytes: Long, total: Long, isUpload: Boolean) {
        val isFinal = bytes >= total
        if (isFinal) {
            lastProgressBroadcast.remove(guid)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("ACTION_FILE_PROGRESS").apply {
                putExtra("guid", guid)
                putExtra("bytes", bytes)
                putExtra("total", total)
                putExtra("is_upload", isUpload)
            }
        )
    }

    override fun onTrackerAnnounce(ok: Boolean, ttl: Int) {
        Log.i(TAG, "onTrackerAnnounce: ok=$ok, ttl=$ttl")
        App.app.trackerAnnounced = ok
    }

    override fun onContactRequest(pubkey: ByteArray, message: String, nickname: String, info: String, avatar: ByteArray?) {
        val hex = Hex.toHexString(pubkey).take(8)
        Log.i(TAG, "onContactRequest from $hex: $nickname")
        val storage = (application as App).storage
        val contactId = storage.getContactId(pubkey)
        if (contactId > 0) {
            // Already our contact — auto-respond with accepted, don't store a request
            Log.i(TAG, "Auto-accepting contact request from existing contact $hex")
            Thread {
                try { peerNode?.sendContactResponse(pubkey, true) } catch (_: Exception) {}
            }.start()
            return
        }
        storage.addContactRequest(pubkey, message, nickname, info, avatar)
    }

    override fun onContactResponse(pubkey: ByteArray, accepted: Boolean) {
        Log.i(TAG, "onContactResponse from ${Hex.toHexString(pubkey).take(8)}: accepted=$accepted")
        if (accepted) {
            val storage = (application as App).storage
            // The peer accepted our request — create/confirm contact on our side
            val id = storage.getContactId(pubkey)
            if (id <= 0) {
                storage.addContact(pubkey, "")
            }
        }
    }

    // ── MediatorEventListener ─────────────────────────────────────────────────

    override fun onConnected(mediatorPubkey: ByteArray) {
        Log.i(TAG, "onConnected to mediator ${Hex.toHexString(mediatorPubkey).take(8)}")
        App.app.mediatorConnected = true
        val storage = (application as App).storage
        Thread {
            sleep(1000)
            val chats = storage.getGroupChatList()
            for (chat in chats.filter { it.mediatorPubkey.contentEquals(mediatorPubkey) }) {
                try {
                    subscribedChats.add(chat.chatId)
                    val serverLastId = mediatorNode!!.subscribe(mediatorPubkey, chat.chatId)
                    Log.i(TAG, "Subscribed to chat ${chat.chatId}, serverLastId=$serverLastId")
                    broadcastGroupChatStatus(chat.chatId, storage, "SUBSCRIBED")
                    syncInitialMemberList(chat.chatId, mediatorPubkey, storage)
                    sleep(1000)
                    syncMissedMessages(chat.chatId, mediatorPubkey, storage, serverLastId)
                    resendUndeliveredGroupMessages(chat.chatId, mediatorPubkey, storage)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to subscribe to chat ${chat.chatId}: ${e.message}")
                    subscribedChats.remove(chat.chatId)
                    val msg = e.message?.lowercase() ?: ""
                    if (msg.contains("not a member") || msg.contains("not member") || msg.contains("banned")) {
                        storage.setGroupChatReadOnly(chat.chatId, true)
                        broadcastGroupChatStatus(chat.chatId, storage, "READ_ONLY")
                    } else {
                        broadcastGroupChatStatus(chat.chatId, storage, "DISCONNECTED")
                    }
                }
            }
            broadcastMediatorConnected(mediatorPubkey)
        }.start()
    }

    override fun onSubscribed(mediatorPubkey: ByteArray, chatId: Long, lastMessageId: Long) {
        Log.i(TAG, "onSubscribed to chat $chatId, lastMessageId=$lastMessageId")
        // If subscribeToChat() already added this chat, it will handle
        // syncInitialMemberList + syncMissedMessages itself.  Running a
        // competing sync here without member data causes messages to be
        // silently dropped (author lookup fails) while system messages
        // advance last_msg_id, permanently losing the skipped messages.
        if (subscribedChats.contains(chatId)) {
            Log.d(TAG, "onSubscribed: chat $chatId already managed by subscribeToChat, skipping")
            return
        }
        val storage = (application as App).storage
        Thread {
            try {
                subscribedChats.add(chatId)
                broadcastGroupChatStatus(chatId, storage, "SUBSCRIBED")
                syncInitialMemberList(chatId, mediatorPubkey, storage)
                syncMissedMessages(chatId, mediatorPubkey, storage, lastMessageId)
                resendUndeliveredGroupMessages(chatId, mediatorPubkey, storage)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync chat $chatId after subscribe: ${e.message}")
                subscribedChats.remove(chatId)
                broadcastGroupChatStatus(chatId, storage, "DISCONNECTED")
            }
        }.start()
    }

    override fun onPushMessage(chatId: Long, messageId: Long, guid: Long, timestamp: Long, author: ByteArray, data: ByteArray) {
        val storage = (application as App).storage
        Thread {
            val localId = parseAndSaveGroupMessage(
                this,
                chatId,
                messageId,
                guid,
                timestamp,
                author,
                data,
                storage
            )
            if (localId > 0) {
                maybeAutoDownloadGroupFile(chatId, guid, storage)
            }
            // If this was a system message (from mediator) indicating chat deletion, broadcast READ_ONLY
            val chatInfo = storage.getGroupChat(chatId)
            if (chatInfo != null && author.contentEquals(chatInfo.mediatorPubkey) &&
                data.isNotEmpty() && data[0] == SYS_CHAT_DELETED) {
                handler.post { broadcastGroupChatStatus(chatId, storage, "READ_ONLY") }
            }
        }.start()
    }

    override fun onSystemMessage(chatId: Long, messageId: Long, guid: Long, timestamp: Long, body: ByteArray) {
        val storage = (application as App).storage
        Thread {
            // System messages arrive from the mediator; parseAndSaveGroupMessage handles them
            val chatInfo = storage.getGroupChat(chatId) ?: return@Thread
            parseAndSaveGroupMessage(
                this,
                chatId,
                messageId,
                guid,
                timestamp,
                chatInfo.mediatorPubkey,  // author = mediator → treated as system message
                body,
                storage
            )
            // If chat was deleted, broadcast READ_ONLY status so the UI updates
            if (body.isNotEmpty() && body[0] == SYS_CHAT_DELETED) {
                handler.post { broadcastGroupChatStatus(chatId, storage, "READ_ONLY") }
            }
        }.start()
    }

    override fun onPushInvite(
        inviteId: Long, chatId: Long, fromPubkey: ByteArray, timestamp: Long,
        chatName: String, chatDesc: String, chatAvatar: ByteArray?, encryptedData: ByteArray,
        mediatorPubkey: ByteArray
    ) {
        val storage = (application as App).storage
        Log.i(TAG, "onPushInvite: chatName=$chatName from=${Hex.toHexString(fromPubkey).take(8)}")

        val (inviteDbId, avatarPath) = storage.saveGroupInvite(
            chatId,
            inviteId,
            fromPubkey,
            timestamp,
            chatName,
            chatDesc,
            chatAvatar,
            encryptedData
        )

        for (listener in storage.listeners) {
            listener.onGroupInviteReceived(inviteDbId, chatId, fromPubkey)
        }

        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("ACTION_GROUP_INVITE_RECEIVED").apply {
                putExtra("invite_id", inviteDbId)
                putExtra("chat_id", chatId)
                putExtra("from_pubkey", fromPubkey)
                putExtra("chat_name", chatName)
                putExtra("chat_description", chatDesc)
                putExtra("chat_avatar_path", avatarPath)
                putExtra("mediator_pubkey", mediatorPubkey)
            }
        )

        showGroupInviteNotification(
            this, inviteDbId, chatId, fromPubkey, timestamp,
            chatName, chatDesc, avatarPath, encryptedData, mediatorPubkey
        )
    }

    override fun onMemberInfoRequest(chatId: Long, lastUpdate: Long): MemberInfoData? {
        val chatIdL = chatId
        val storage = (application as App).storage
        val chatInfo = storage.getGroupChat(chatIdL) ?: return null
        val accountInfo = storage.getAccountInfo(1, lastUpdate) ?: return null

        var avatar: ByteArray? = null
        if (accountInfo.avatar.isNotEmpty()) {
            val f = File(File(filesDir, "avatars"), accountInfo.avatar)
            if (f.exists()) avatar = f.readBytes()
        }

        // Serialize member info: [nicknameLen(u16)][nickname][infoLen(u16)][info][avatarLen(u32)][avatar]
        val nicknameBytes = accountInfo.name.toByteArray(Charsets.UTF_8)
        val infoBytes = accountInfo.info.toByteArray(Charsets.UTF_8)
        val avatarBytes = avatar ?: ByteArray(0)
        val buffer = ByteBuffer.allocate(2 + nicknameBytes.size + 2 + infoBytes.size + 4 + avatarBytes.size)
        buffer.putShort(nicknameBytes.size.toShort())
        buffer.put(nicknameBytes)
        buffer.putShort(infoBytes.size.toShort())
        buffer.put(infoBytes)
        buffer.putInt(avatarBytes.size)
        if (avatarBytes.isNotEmpty()) buffer.put(avatarBytes)

        return try {
            val encryptedBlob = mimirEncryptMessage(buffer.array(), chatInfo.sharedKey)
            MemberInfoData(encryptedBlob = encryptedBlob, timestamp = accountInfo.updated)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt member info: ${e.message}")
            null
        }
    }

    override fun onMemberInfoUpdate(
        chatId: Long, memberPubkey: ByteArray, encryptedInfo: ByteArray?, timestamp: Long
    ) {
        val storage = (application as App).storage
        val chatInfo = storage.getGroupChat(chatId) ?: return
        if (encryptedInfo == null) return
        try {
            processMemberInfo(chatId, memberPubkey, encryptedInfo, chatInfo.sharedKey, storage)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process member info update: ${e.message}")
        }
    }

    private fun processMemberInfo(
        chatId: Long, pubkey: ByteArray, encryptedInfo: ByteArray, sharedKey: ByteArray, storage: SqlStorage
    ) {
        val plaintext = mimirDecryptMessage(encryptedInfo, sharedKey)
        val buffer = ByteBuffer.wrap(plaintext)

        val nicknameLen = buffer.getShort().toInt() and 0xFFFF
        val nicknameBytes = ByteArray(nicknameLen)
        buffer.get(nicknameBytes)
        val nickname = String(nicknameBytes, Charsets.UTF_8)

        val infoLen = buffer.getShort().toInt() and 0xFFFF
        val infoBytes = ByteArray(infoLen)
        buffer.get(infoBytes)
        val info = String(infoBytes, Charsets.UTF_8)

        val avatarLen = buffer.getInt()
        val avatar = if (avatarLen > 0) ByteArray(avatarLen).also { buffer.get(it) } else null

        storage.updateGroupMemberInfo(chatId, pubkey, nickname, info, avatar)
    }

    private fun syncInitialMemberList(chatId: Long, mediatorPubkey: ByteArray, storage: SqlStorage) {
        try {
            val chatInfo = storage.getGroupChat(chatId) ?: return
            val membersInfo = mediatorNode!!.getMembersInfo(mediatorPubkey, chatId, 0L)
            val oldMediatorPubkey = Hex.decode(SqlStorage.OLD_MEDIATOR_PUBKEY_HEX)
            for (info in membersInfo) {
                if (info.pubkey.contentEquals(oldMediatorPubkey)) continue
                if (info.encryptedInfo != null) {
                    try {
                        processMemberInfo(chatId, info.pubkey, info.encryptedInfo!!, chatInfo.sharedKey, storage)
                    } catch (e: Exception) {
                        Log.d(TAG, "Could not decrypt member info for ${info.pubkey.take(4)}: ${e.message}")
                        storage.updateGroupMemberInfo(chatId, info.pubkey, null, null, null)
                    }
                } else {
                    // No profile yet — create stub so the member appears in the list
                    storage.updateGroupMemberInfo(chatId, info.pubkey, null, null, null)
                }
            }
            // Update permissions and online status now that all members exist in DB
            val members = mediatorNode!!.getMembers(mediatorPubkey, chatId)
            val myPubkey = peerNode?.publicKey()
            for (member in members) {
                if (member.pubkey.contentEquals(oldMediatorPubkey)) continue
                storage.updateGroupMemberStatus(chatId, member.pubkey, member.permissions.toInt(), member.online)
                // Update readOnly based on our own permissions from the server
                if (myPubkey != null && member.pubkey.contentEquals(myPubkey)) {
                    val perms = member.permissions.toInt()
                    val shouldBeReadOnly = (perms and PERM_READ_ONLY) != 0 || (perms and PERM_BANNED) != 0
                    storage.setGroupChatReadOnly(chatId, shouldBeReadOnly)
                    if (shouldBeReadOnly) {
                        broadcastGroupChatStatus(chatId, storage, "READ_ONLY")
                    }
                }
            }
            Log.i(TAG, "Synced ${membersInfo.size} member(s) for chat $chatId")
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent("ACTION_MEMBERS_SYNCED").apply { putExtra("chat_id", chatId) }
            )
        } catch (e: Exception) {
            Log.w(TAG, "syncInitialMemberList failed for chat $chatId: ${e.message}")
        }
    }

    override fun onMemberOnlineStatusChanged(
        chatId: Long, memberPubkey: ByteArray, isOnline: Boolean, timestamp: Long
    ) {
        // Ignore stale "offline" pushes for ourselves — we know we're online
        // while subscribed.  The mediator may deliver a queued offline event
        // from a previous session right after we reconnect.
        val myPubkey = peerNode?.publicKey()
        if (!isOnline && myPubkey != null && memberPubkey.contentEquals(myPubkey)
            && subscribedChats.contains(chatId)) {
            Log.d(TAG, "Ignoring stale offline push for ourselves in chat $chatId")
            return
        }

        val chatIdL = chatId
        val storage = (application as App).storage
        val lastSeen = if (isOnline) 0L else timestamp
        storage.updateGroupMemberOnlineStatus(chatIdL, memberPubkey, isOnline, lastSeen)
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("ACTION_MEMBER_ONLINE_STATUS").apply {
                putExtra("chat_id", chatIdL)
                putExtra("pubkey", memberPubkey)
                putExtra("is_online", isOnline)
            }
        )
    }

    override fun onDisconnected(mediatorPubkey: ByteArray) {
        Log.w(TAG, "onDisconnected from mediator ${Hex.toHexString(mediatorPubkey).take(8)}")
        App.app.mediatorConnected = false
        val storage = (application as App).storage
        val myPubkey = peerNode?.publicKey()
        val chats = storage.getGroupChatList()
        for (chat in chats.filter { it.mediatorPubkey.contentEquals(mediatorPubkey) }) {
            subscribedChats.remove(chat.chatId)
            // Mark ourselves offline in DB so the UI reflects the real state
            if (myPubkey != null) {
                storage.updateGroupMemberOnlineStatus(chat.chatId, myPubkey, false, System.currentTimeMillis() / 1000)
            }
            broadcastGroupChatStatus(chat.chatId, storage, "DISCONNECTED")
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("ACTION_MEDIATOR_DISCONNECTED").apply {
                putExtra("mediator_pubkey", mediatorPubkey)
            }
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun connectAndSubscribeToAllChats(storage: SqlStorage) {
        try {
            sleep(3000)
            val knownMediators = storage.getKnownMediators().toMutableList()
            val defaultMediator = Hex.decode(DEFAULT_MEDIATOR_HEX)
            if (!knownMediators.any { it.contentEquals(defaultMediator) }) {
                knownMediators.add(defaultMediator)
            }
            Log.i(TAG, "Connecting to ${knownMediators.size} mediator(s)")
            for (mediatorPubkey in knownMediators) {
                try {
                    mediatorNode?.connectToMediator(mediatorPubkey)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect to mediator: ${e.message}")
                }
            }
            // Also try to connect to any P2P contacts with unsent messages.
            retryPeersWithUnsentMessages(storage)
            // Start the periodic retry timer if not already running.
            scheduleRetrySend()
        } catch (e: Exception) {
            Log.e(TAG, "connectAndSubscribeToAllChats error: ${e.message}", e)
        }
    }

    /**
     * For each contact that has unsent P2P messages and is not currently connected,
     * initiate a connection attempt. The actual re-send happens in [onPeerConnected]
     * via [sendPendingP2PMessages].
     */
    private fun retryPeersWithUnsentMessages(storage: SqlStorage) {
        val contacts = storage.getContactsWithUnsentMessages()
        if (contacts.isEmpty()) return
        Log.i(TAG, "Retrying connections for ${contacts.size} contacts with unsent messages")
        for (pubkey in contacts) {
            if (peerStatuses[Hex.toHexString(pubkey)] != PeerStatus.Connected) {
                try { peerNode?.connectToPeer(pubkey) } catch (e: Exception) {
                    Log.w(TAG, "connectToPeer for unsent msg failed: ${e.message}")
                }
            }
        }
    }

    /**
     * Schedule [retrySendTick] to fire after [RETRY_SEND_INTERVAL] unless one is already pending.
     * [delayMs] can be shorter (e.g. 60 s) for an expedited first retry.
     */
    private fun scheduleRetrySend(delayMs: Long = RETRY_SEND_INTERVAL) {
        if (!retrySendPending) {
            retrySendPending = true
            handler.postDelayed(delayMs) { retrySendTick() }
        }
    }

    /** Periodic tick: retry unsent-message connections, then reschedule if still needed. */
    private fun retrySendTick() {
        retrySendPending = false
        val storage = (application as App).storage
        retryPeersWithUnsentMessages(storage)
        // Keep the timer alive as long as there are outstanding messages.
        if (storage.getContactsWithUnsentMessages().isNotEmpty()) {
            retrySendPending = true
            handler.postDelayed(RETRY_SEND_INTERVAL) { retrySendTick() }
        }
    }

    private fun syncMissedMessages(chatId: Long, mediatorPubkey: ByteArray, storage: SqlStorage, serverLastId: Long) {
        val localLastId = storage.getGroupChat(chatId)?.lastSyncedMsgId ?: 0L
        if (localLastId >= serverLastId) return

        var fromId = localLastId
        while (true) {
            val batch = try {
                mediatorNode!!.getMessagesSince(mediatorPubkey, chatId, fromId, 100u)
            } catch (e: Exception) {
                Log.e(TAG, "getMessagesSince failed: ${e.message}")
                break
            }
            if (batch.isEmpty()) break
            for (msg in batch) {
                parseAndSaveGroupMessage(
                    this,
                    chatId,
                    msg.messageId,
                    msg.guid,
                    msg.timestamp,
                    msg.author,
                    msg.data,
                    storage,
                    broadcast = true,
                    fromSync = true
                )
            }
            fromId = batch.last().messageId
            if (batch.size < 100) break
        }
    }

    private fun resendUndeliveredGroupMessages(
        chatId: Long, mediatorPubkey: ByteArray, storage: SqlStorage
    ) {
        val chatInfo = storage.getGroupChat(chatId) ?: return
        val undelivered = storage.getUndeliveredGroupMessages(chatId)
        if (undelivered.isEmpty()) return

        for (msg in undelivered) {
            try {
                var messageData = String(msg.data ?: ByteArray(0))

                // Skip oversized file messages to prevent OOM crash
                if (msg.type == 1 || msg.type == 3) {
                    try {
                        val meta = org.json.JSONObject(messageData)
                        val fileSize = meta.optLong("size", 0)
                        if (fileSize > MAX_GROUP_FILE_SIZE) {
                            Log.w(TAG, "Skipping oversized group file msg ${msg.guid}: $fileSize bytes")
                            storage.setGroupMessageDelivered(chatId, msg.guid, true)
                            continue
                        }
                        // Check if already uploaded (has "key" field)
                        if (!meta.has("key")) {
                            // Not yet uploaded — upload now via FilesNode
                            val fileKey = uniffi.mimir.generateSharedKey()
                            val preferences = PreferenceManager.getDefaultSharedPreferences(this)
                            val serverPubkey = Hex.decode(preferences.getString(SettingsData.KEY_FILE_SERVER_PUBKEY, DEFAULT_FILE_SERVER_HEX)!!)
                            val fileName = meta.getString("name")
                            val filePath = File(filesDir, "files/$fileName").absolutePath
                            val fileHash = filesNode!!.uploadFile(serverPubkey, filePath, msg.guid, fileKey)
                            val newMeta = org.json.JSONObject()
                            newMeta.put("name", meta.getString("name"))
                            newMeta.put("hash", Hex.toHexString(fileHash))
                            newMeta.put("size", meta.optLong("size", 0))
                            if (meta.has("text")) newMeta.put("text", meta.getString("text"))
                            newMeta.put("key", Base64.encodeToString(fileKey, Base64.NO_WRAP))
                            newMeta.put("server", Hex.toHexString(serverPubkey))
                            if (meta.has("originalName")) newMeta.put("originalName", meta.getString("originalName"))
                            if (meta.has("mimeType")) newMeta.put("mimeType", meta.getString("mimeType"))
                            messageData = newMeta.toString()
                        }
                    } catch (_: Exception) { }
                }

                val netMsg = Message(
                    guid = msg.guid,
                    replyTo = msg.replyTo,
                    sendTime = msg.timestamp,
                    editTime = 0,
                    type = msg.type,
                    data = messageData.toByteArray()
                )
                val serialized = serializeGroupMessage(netMsg, "")
                val encryptedData = mimirEncryptMessage(serialized, chatInfo.sharedKey)
                val messageId = mediatorNode!!.sendGroupMessage(
                    mediatorPubkey, chatId, msg.guid, msg.timestamp, encryptedData
                )
                storage.updateGroupMessageServerId(chatId, msg.guid, messageId)
                storage.setGroupMessageDelivered(chatId, msg.guid, true)
                LocalBroadcastManager.getInstance(this).sendBroadcast(
                    Intent("ACTION_MEDIATOR_MESSAGE_SENT").apply {
                        putExtra("chat_id", chatId)
                        putExtra("message_id", messageId)
                        putExtra("guid", msg.guid)
                        putExtra("type", msg.type)
                        putExtra("replyTo", msg.replyTo)
                    }
                )
                sleep(100)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resend msg ${msg.guid}: ${e.message}")
            }
        }
    }

    private fun sendPendingP2PMessages(pubkey: ByteArray, storage: SqlStorage) {
        val unsentIds = storage.getUnsentMessages(pubkey)
        if (unsentIds.isEmpty()) return
        Log.i(TAG, "Sending ${unsentIds.size} pending messages to ${Hex.toHexString(pubkey).take(8)}")
        for (id in unsentIds) {
            val msg = storage.getMessage(id) ?: continue
            if (msg.data == null) continue
            Log.d(TAG, "  Sending pending msg id=$id guid=${msg.guid}")
            try {
                // For file attachments, upload to file server if not yet uploaded
                val sendPayload = if ((msg.type == 1 || msg.type == 3) && msg.data != null) {
                    uploadP2PFileToServer(msg.guid, msg.data, storage)
                } else {
                    msg.data
                }
                peerNode?.sendMessage(
                    pubkey,
                    msg.guid,
                    msg.replyTo,
                    msg.time,
                    msg.edit,
                    msg.type,
                    sendPayload
                )
                Log.d(TAG, "  Sent pending msg id=$id guid=${msg.guid}")
            } catch (e: Exception) {
                Log.w(TAG, "sendMessage failed for id=$id: ${e.message}")
                break  // Connection gone; remaining messages will be sent on next connect
            }
        }
    }

    private fun subscribeToChat(chatId: Long, storage: SqlStorage) {
        val chatInfo = storage.getGroupChat(chatId) ?: run {
            broadcastMediatorError("subscribe", "Chat not found")
            return
        }
        try {
            mediatorNode?.connectToMediator(chatInfo.mediatorPubkey)
            // Add to subscribedChats BEFORE subscribe() so that the onSubscribed
            // callback (fired synchronously inside subscribe) sees this chat is
            // already being managed and skips its own competing syncMissedMessages.
            subscribedChats.add(chatId)
            val serverLastId = mediatorNode!!.subscribe(chatInfo.mediatorPubkey, chatId)
            Log.i(TAG, "Subscribed to chat $chatId (serverLastId=$serverLastId)")
            broadcastGroupChatStatus(chatId, storage, "SUBSCRIBED")
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent("ACTION_MEDIATOR_SUBSCRIBED").apply { putExtra("chat_id", chatId) }
            )
            syncInitialMemberList(chatId, chatInfo.mediatorPubkey, storage)
            sleep(1000)
            syncMissedMessages(chatId, chatInfo.mediatorPubkey, storage, serverLastId)
            resendUndeliveredGroupMessages(chatId, chatInfo.mediatorPubkey, storage)
        } catch (e: Exception) {
            Log.e(TAG, "subscribeToChat $chatId failed: ${e.message}")
            subscribedChats.remove(chatId)
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("not a member") || msg.contains("not member") || msg.contains("banned")) {
                storage.setGroupChatReadOnly(chatId, true)
                broadcastGroupChatStatus(chatId, storage, "READ_ONLY")
            } else {
                broadcastGroupChatStatus(chatId, storage, "DISCONNECTED")
                broadcastMediatorError("subscribe", e.message ?: "Unknown error")
            }
        }
    }

    private fun sendGroupChatMessage(
        chatId: Long, storage: SqlStorage, guid: Long, replyTo: Long,
        sendTime: Long, type: Int, messageData: String
    ) {
        try {
            // Check file size before loading into memory to prevent OOM
            if (type == 1 || type == 3) {
                val meta = org.json.JSONObject(messageData)
                val fileSize = meta.optLong("size", 0)
                if (fileSize > MAX_GROUP_FILE_SIZE) {
                    Log.e(TAG, "Group file too large: $fileSize bytes (max: $MAX_GROUP_FILE_SIZE)")
                    storage.setGroupMessageDelivered(chatId, guid, true) // Mark as delivered to stop retries
                    broadcastMediatorError("send", "File too large for group chat")
                    return
                }
            }

            val chatInfo = storage.getGroupChat(chatId) ?: run {
                broadcastMediatorError("send", "Chat not found")
                return
            }

            val actualMessageData = if (type == 1 || type == 3) {
                // Upload file via FilesNode, send only metadata through mediator
                val meta = org.json.JSONObject(messageData)
                val fileKey = uniffi.mimir.generateSharedKey()
                val preferences = PreferenceManager.getDefaultSharedPreferences(this)
                val serverPubkey = Hex.decode(preferences.getString(SettingsData.KEY_FILE_SERVER_PUBKEY, DEFAULT_FILE_SERVER_HEX)!!)
                val fileName = meta.getString("name")
                val filePath = File(filesDir, "files/$fileName").absolutePath
                val fileHash = filesNode!!.uploadFile(serverPubkey, filePath, guid, fileKey)
                // Build metadata-only JSON — raw key (message itself is encrypted with group shared key)
                val newMeta = org.json.JSONObject()
                newMeta.put("name", meta.getString("name"))
                newMeta.put("hash", Hex.toHexString(fileHash))
                newMeta.put("size", meta.optLong("size", 0))
                if (meta.has("text")) newMeta.put("text", meta.getString("text"))
                newMeta.put("key", Base64.encodeToString(fileKey, Base64.NO_WRAP))
                newMeta.put("server", Hex.toHexString(serverPubkey))
                if (meta.has("originalName")) newMeta.put("originalName", meta.getString("originalName"))
                if (meta.has("mimeType")) newMeta.put("mimeType", meta.getString("mimeType"))
                newMeta.toString()
            } else {
                messageData
            }

            val serialized = serializeGroupMessage(
                Message(guid, replyTo, sendTime, 0, type, actualMessageData.toByteArray()),
                "" // No file path — file data is on the server, not embedded
            )
            val encryptedData = mimirEncryptMessage(serialized, chatInfo.sharedKey)
            val messageId = mediatorNode!!.sendGroupMessage(
                chatInfo.mediatorPubkey, chatId, guid, sendTime, encryptedData
            )
            Log.i(TAG, "Group message sent: msgId=$messageId, guid=$guid")
            storage.updateGroupMessageServerId(chatId, guid, messageId)
            storage.setGroupMessageDelivered(chatId, guid, true)
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent("ACTION_MEDIATOR_MESSAGE_SENT").apply {
                    putExtra("chat_id", chatId)
                    putExtra("message_id", messageId)
                    putExtra("guid", guid)
                    putExtra("type", type)
                    putExtra("replyTo", replyTo)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "sendGroupChatMessage failed: ${e.message}", e)
            broadcastMediatorError("send", e.message ?: "Unknown error")
        }
    }

    private fun createChat(name: String, description: String, avatar: ByteArray?) {
        val mediatorPubkey = Hex.decode(DEFAULT_MEDIATOR_HEX)
        try {
            val sharedKey = uniffi.mimir.generateSharedKey()
            val chatId = mediatorNode!!.createChat(mediatorPubkey, name, description, avatar)
            Log.i(TAG, "Chat created: id=$chatId")
            // Save to local DB (avatar saved separately via updateGroupChatAvatar)
            val storage = (application as App).storage
            storage.saveGroupChat(chatId, name, description, null, mediatorPubkey, peerNode!!.publicKey(), sharedKey)
            if (avatar != null && avatar.isNotEmpty()) {
                storage.updateGroupChatAvatar(chatId, avatar)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent("ACTION_MEDIATOR_CHAT_CREATED").apply {
                    putExtra("chat_id", chatId)
                    putExtra("name", name)
                    putExtra("description", description)
                    putExtra("mediator_address", mediatorPubkey)
                }
            )
            // Subscribe to the newly created chat so it becomes active immediately
            subscribeToChat(chatId, storage)
        } catch (e: Exception) {
            Log.e(TAG, "createChat failed: ${e.message}", e)
            broadcastMediatorError("create_chat", e.message ?: "Unknown error")
        }
    }

    private fun updateChatInfo(chatId: Long, name: String?, description: String?, avatar: ByteArray?) {
        val storage = (application as App).storage
        val chatInfo = storage.getGroupChat(chatId) ?: run {
            broadcastMediatorError("update_chat", "Chat not found")
            return
        }
        try {
            mediatorNode!!.updateChatInfo(chatInfo.mediatorPubkey, chatId, name, description, avatar)
            if (name != null) storage.updateGroupChatName(chatId, name)
            if (description != null) storage.updateGroupChatDescription(chatId, description)
            if (avatar != null) storage.updateGroupChatAvatar(chatId, avatar)
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent("ACTION_MEDIATOR_CHAT_INFO_UPDATED").apply { putExtra("chat_id", chatId) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "updateChatInfo failed: ${e.message}", e)
            broadcastMediatorError("update_chat", e.message ?: "Unknown error")
        }
    }

    private fun leaveGroupChat(chatId: Long) {
        val storage = (application as App).storage
        val chatInfo = storage.getGroupChat(chatId) ?: return
        try {
            mediatorNode!!.leaveChat(chatInfo.mediatorPubkey, chatId)
            subscribedChats.remove(chatId)
            storage.deleteGroupChat(chatId)
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent("ACTION_MEDIATOR_LEFT_CHAT").apply { putExtra("chat_id", chatId) }
            )
        } catch (e: Exception) {
            val msg = e.message?.lowercase() ?: ""
            if (msg.contains("not a member") || msg.contains("not member") ||
                msg.contains("not found") || msg.contains("banned")) {
                // Server says we're not a member/banned — remove locally regardless.
                Log.w(TAG, "leaveGroupChat: not a member server-side for chat $chatId, removing locally")
                storage.deleteGroupChat(chatId)
                LocalBroadcastManager.getInstance(this).sendBroadcast(
                    Intent("ACTION_MEDIATOR_LEFT_CHAT").apply { putExtra("chat_id", chatId) }
                )
            } else {
                Log.e(TAG, "leaveGroupChat failed: ${e.message}", e)
                broadcastMediatorError("leave", e.message ?: "Unknown error")
            }
        }
    }

    private fun deleteGroupChat(chatId: Long) {
        val storage = (application as App).storage
        val chatInfo = storage.getGroupChat(chatId) ?: return
        try {
            mediatorNode!!.deleteChat(chatInfo.mediatorPubkey, chatId)
            subscribedChats.remove(chatId)
            storage.deleteGroupChat(chatId)
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent("ACTION_MEDIATOR_CHAT_DELETED").apply { putExtra("chat_id", chatId) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "deleteGroupChat failed: ${e.message}", e)
            broadcastMediatorError("delete", e.message ?: "Unknown error")
        }
    }

    private fun sendInviteToGroupChat(chatId: Long, recipientPubkey: ByteArray) {
        val storage = (application as App).storage
        val chatInfo = storage.getGroupChat(chatId) ?: run {
            broadcastMediatorError("send_invite", "Chat not found")
            return
        }
        try {
            val encryptedKey = mimirEncryptSharedKey(chatInfo.sharedKey, recipientPubkey)
            mediatorNode!!.sendInvite(chatInfo.mediatorPubkey, chatId, recipientPubkey, encryptedKey)
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent("ACTION_MEDIATOR_INVITE_SENT").apply {
                    putExtra("chat_id", chatId)
                    putExtra("recipient_pubkey", recipientPubkey)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "sendInviteToGroupChat failed: ${e.message}", e)
            broadcastMediatorError("send_invite", e.message ?: "Unknown error")
        }
    }

    private fun banUserFromGroupChat(chatId: Long, userPubkey: ByteArray) {
        val storage = (application as App).storage
        val chatInfo = storage.getGroupChat(chatId) ?: return
        try {
            mediatorNode!!.changeMemberStatus(chatInfo.mediatorPubkey, chatId, userPubkey, 0u)
            storage.deleteGroupMember(chatId, userPubkey)
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent("ACTION_MEDIATOR_USER_BANNED").apply {
                    putExtra("chat_id", chatId)
                    putExtra("user_pubkey", userPubkey)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "banUserFromGroupChat failed: ${e.message}", e)
            broadcastMediatorError("ban_user", e.message ?: "Unknown error")
        }
    }

    private fun changeMemberRole(chatId: Long, userPubkey: ByteArray, newPermissions: Int) {
        val storage = (application as App).storage
        val chatInfo = storage.getGroupChat(chatId) ?: return
        try {
            mediatorNode!!.changeMemberStatus(chatInfo.mediatorPubkey, chatId, userPubkey, newPermissions.toUByte())
            storage.updateGroupMemberStatus(chatId, userPubkey, newPermissions, true)
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent("ACTION_MEDIATOR_ROLE_CHANGED").apply {
                    putExtra("chat_id", chatId)
                    putExtra("user_pubkey", userPubkey)
                    putExtra("permissions", newPermissions)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "changeMemberRole failed: ${e.message}", e)
            broadcastMediatorError("change_role", e.message ?: "Unknown error")
        }
    }

    private fun acceptInviteAndSubscribe(inviteId: Long, chatId: Long, storage: SqlStorage) {
        val chatInfo = storage.getGroupChat(chatId) ?: run {
            broadcastMediatorError("add_user", "Chat not found")
            return
        }
        var respondOk = false
        for (attempt in 1..3) {
            try {
                mediatorNode!!.respondToInvite(chatInfo.mediatorPubkey, chatId, inviteId, true)
                respondOk = true
                break
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                if (msg.contains("already")) {
                    // Invite already processed on a previous run — user is already a member.
                    respondOk = true
                    break
                }
                // Any other error (including "not found" = invite expired/revoked): retry.
                if (attempt < 3) sleep(2000)
            }
        }

        if (respondOk) {
            storage.updateGroupInviteStatus(inviteId, 1)
            subscribeToChat(chatId, storage)
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent("ACTION_INVITE_ACCEPTED").apply {
                    putExtra("chat_id", chatId)
                    putExtra("invite_id", inviteId)
                }
            )
        } else {
            // respond_to_invite failed on all retries. Verify whether the user is
            // already a member by attempting to subscribe (handles the case where the
            // invite record was already deleted after a previous successful acceptance).
            var subscribeOk = false
            try {
                mediatorNode!!.subscribe(chatInfo.mediatorPubkey, chatId)
                subscribeOk = true
            } catch (e: Exception) {
                Log.d(TAG, "acceptInviteAndSubscribe: verification subscribe failed: ${e.message}")
            }

            if (subscribeOk) {
                // User IS a member — invite was already processed.
                storage.updateGroupInviteStatus(inviteId, 1)
                subscribeToChat(chatId, storage)
                LocalBroadcastManager.getInstance(this).sendBroadcast(
                    Intent("ACTION_INVITE_ACCEPTED").apply {
                        putExtra("chat_id", chatId)
                        putExtra("invite_id", inviteId)
                    }
                )
            } else {
                // Truly not a member. Remove the optimistically-saved local group.
                Log.w(TAG, "acceptInviteAndSubscribe: invite invalid/expired for chat $chatId, cleaning up")
                storage.deleteGroupChat(chatId)
                broadcastMediatorError("add_user", "Invite expired or invalid — please ask for a new invite")
            }
        }
    }

    private fun startAudioSession(pubkey: ByteArray) {
        stopAudioSession()
        val receiver = com.revertron.mimir.calls.AudioReceiver()
        activeAudioReceiver = receiver
        receiver.start()
        val node = peerNode
        if (node != null && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            val sender = com.revertron.mimir.calls.AudioSender(node, pubkey)
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

    private fun broadcastFileDownloading(guid: Long, name: String, pubkey: ByteArray) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent("ACTION_FILE_DOWNLOADING").apply {
                putExtra("guid", guid)
                putExtra("name", name)
                putExtra("pubkey", pubkey)
            }
        )
    }

    private fun broadcastGroupChatStatus(chatId: Long, storage: SqlStorage, status: String = "CONNECTING") {
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
            } else {
                updateAfter = System.currentTimeMillis() + 600 * 1000L
                handler.postDelayed(600 * 1000L) { updateTick() }
            }
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
