package com.revertron.mimir

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.calls.GroupAudioMixer
import com.revertron.mimir.calls.GroupAudioSender
import com.revertron.mimir.net.InviteLinkCodec
import com.revertron.mimir.ui.SettingsData
import uniffi.mimir.GroupCallParticipant

/**
 * Create or join a group audio call: handshake with the call-mediator,
 * capture + encrypt + send mic audio, and mix everyone else's decrypted
 * audio back out through a single AudioTrack.
 *
 * Uses sibling classes under `calls/` (GroupAudioSender, GroupAudioMixer,
 * GroupParticipantAudio) — the 1:1 AudioSender/AudioReceiver are left
 * untouched so the two call types can evolve independently.
 */
class GroupCallActivity : BaseActivity() {

    private var mode: Int = MODE_CREATE
    private var mediatorPubkey: ByteArray? = null
    private var sessionId: ByteArray? = null
    private var sessionKey: ByteArray? = null
    private var inviteLink: String? = null
    private var iAmInitiator: Boolean = false
    private var myPubkey: ByteArray = ByteArray(0)
    private var pendingInviteLink: String? = null
    private var muted: Boolean = false
    private var onSpeaker: Boolean = false

    private var sender: GroupAudioSender? = null
    private var mixer: GroupAudioMixer? = null
    private lateinit var audioManager: AudioManager

    private lateinit var preJoinGroup: LinearLayout
    private lateinit var secondaryActions: LinearLayout
    private lateinit var inCallButtons: LinearLayout
    private lateinit var displayNameEdit: EditText
    private lateinit var joinOrCreateButton: Button
    private lateinit var inviteUsersButton: Button
    private lateinit var copyLinkButton: Button
    private lateinit var muteButton: AppCompatImageButton
    private lateinit var speakerButton: AppCompatImageButton
    private lateinit var leaveButton: AppCompatImageButton
    private lateinit var participantsList: RecyclerView
    private val adapter = ParticipantsAdapter()

    private val uiHandler = Handler(Looper.getMainLooper())
    private val speakingTicker = object : Runnable {
        override fun run() {
            adapter.refreshSpeaking(mixer, sender?.sendingVoice == true)
            uiHandler.postDelayed(this, 200)
        }
    }

    private val groupCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ConnectionService.ACTION_GROUP_CALL_JOINED -> {
                    showInCallUi()
                    onRosterChanged(ConnectionService.groupCallParticipants)
                }
                ConnectionService.ACTION_GROUP_CALL_PARTICIPANTS,
                ConnectionService.ACTION_GROUP_CALL_PARTICIPANT_EVENT -> {
                    onRosterChanged(ConnectionService.groupCallParticipants)
                }
                ConnectionService.ACTION_GROUP_CALL_ERROR -> {
                    val msg = intent.getStringExtra("message") ?: ""
                    Toast.makeText(this@GroupCallActivity, msg, Toast.LENGTH_LONG).show()
                    finish()
                }
                ConnectionService.ACTION_GROUP_CALL_DISCONNECTED -> {
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_call)

        mode = intent.getIntExtra(EXTRA_MODE, MODE_CREATE)
        mediatorPubkey = intent.getByteArrayExtra(EXTRA_MEDIATOR_PUBKEY)
        sessionId = intent.getByteArrayExtra(EXTRA_SESSION_ID)
        sessionKey = intent.getByteArrayExtra(EXTRA_SESSION_KEY)

        preJoinGroup = findViewById(R.id.preJoinGroup)
        secondaryActions = findViewById(R.id.secondaryActions)
        inCallButtons = findViewById(R.id.inCallButtons)
        displayNameEdit = findViewById(R.id.displayNameEdit)
        joinOrCreateButton = findViewById(R.id.joinOrCreateButton)
        inviteUsersButton = findViewById(R.id.inviteUsersButton)
        copyLinkButton = findViewById(R.id.copyLinkButton)
        muteButton = findViewById(R.id.muteButton)
        speakerButton = findViewById(R.id.speakerButton)
        leaveButton = findViewById(R.id.leaveButton)
        participantsList = findViewById(R.id.participantsList)

        participantsList.layoutManager = LinearLayoutManager(this)
        participantsList.adapter = adapter

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        myPubkey = getMimir().publicKey()
        adapter.mePubkey = myPubkey

        val account = getMimir().getAccount(1)
        displayNameEdit.setText(account?.name ?: "")
        joinOrCreateButton.setText(if (mode == MODE_CREATE) R.string.group_call_start else R.string.group_call_join)
        joinOrCreateButton.setOnClickListener { onJoinOrCreate() }

        inviteUsersButton.setOnClickListener { openInviteUsers() }
        copyLinkButton.setOnClickListener { copyInviteLink() }
        muteButton.setOnClickListener { toggleMute() }
        speakerButton.setOnClickListener { toggleSpeaker() }
        leaveButton.setOnClickListener { leaveAndFinish() }

        // Ensure ConnectionService is running so mimirApp is started and the
        // call-mediator client can spin up on demand.
        startService(Intent(this, ConnectionService::class.java).putExtra("command", "start"))

        LocalBroadcastManager.getInstance(this).registerReceiver(
            groupCallReceiver,
            IntentFilter().apply {
                addAction(ConnectionService.ACTION_GROUP_CALL_JOINED)
                addAction(ConnectionService.ACTION_GROUP_CALL_PARTICIPANTS)
                addAction(ConnectionService.ACTION_GROUP_CALL_PARTICIPANT_EVENT)
                addAction(ConnectionService.ACTION_GROUP_CALL_ERROR)
                addAction(ConnectionService.ACTION_GROUP_CALL_DISCONNECTED)
            },
        )
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(speakingTicker)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(groupCallReceiver)
        tearDownAudio()
        // Restore normal audio routing. Matches CallActivity's cleanup so the
        // earpiece/speaker state doesn't leak to the next call.
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (mixer != null) leaveAndFinish() else super.onBackPressed()
    }

    // ── Join / create ────────────────────────────────────────────────────────

    private fun onJoinOrCreate() {
        // Mic is required even for a listener joining: AAC frames we send are
        // what makes us present on the mediator's roster.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC_PERM)
            return
        }
        val displayName = displayNameEdit.text.toString().trim().take(64)
        joinOrCreateButton.isEnabled = false

        Thread {
            try {
                when (mode) {
                    MODE_CREATE -> doCreate(displayName)
                    MODE_JOIN -> doJoin(displayName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "join/create failed", e)
                runOnUiThread {
                    Toast.makeText(this@GroupCallActivity, e.message ?: "Call error", Toast.LENGTH_LONG).show()
                    joinOrCreateButton.isEnabled = true
                }
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC_PERM) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                onJoinOrCreate()
            } else {
                Toast.makeText(this, R.string.group_call_mic_permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun doCreate(displayName: String) {
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val mediatorHex = sp.getString(SettingsData.KEY_CALL_MEDIATOR_PUBKEY, null)
            ?: throw IllegalStateException(getString(R.string.group_call_needs_mediator))
        val mpk = org.bouncycastle.util.encoders.Hex.decode(mediatorHex)

        // Mode 0 = SFU (end-to-end encrypted). MCU is not wired up client-side.
        val sess = getMimir().createGroupCall(mpk, 0u, displayName, ByteArray(0))
        mediatorPubkey = mpk
        sessionId = sess.sessionId
        sessionKey = sess.sessionKey
        iAmInitiator = true
        inviteLink = InviteLinkCodec.encode(mpk, sess.sessionId, sess.sessionKey)
        runOnUiThread {
            showInCallUi()
            startAudio()
        }
    }

    private fun doJoin(displayName: String) {
        val mpk = mediatorPubkey ?: throw IllegalStateException("missing mediator pubkey")
        val sid = sessionId ?: throw IllegalStateException("missing session id")
        val skey = sessionKey ?: throw IllegalStateException("missing session key")
        getMimir().joinGroupCall(mpk, sid, skey, displayName, ByteArray(0))
        iAmInitiator = false
        // Joiners have the same three pieces as the creator (they came in via
        // the invite URI), so they can re-share the link just as well.
        inviteLink = InviteLinkCodec.encode(mpk, sid, skey)
        runOnUiThread {
            showInCallUi()
            startAudio()
        }
    }

    // ── UI state ─────────────────────────────────────────────────────────────

    private fun showInCallUi() {
        preJoinGroup.visibility = View.GONE
        participantsList.visibility = View.VISIBLE
        secondaryActions.visibility = if (inviteLink != null) View.VISIBLE else View.GONE
        inCallButtons.visibility = View.VISIBLE
        uiHandler.post(speakingTicker)
    }

    private fun copyInviteLink() {
        val link = inviteLink ?: return
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.group_call_invite_link), link))
        Toast.makeText(this, R.string.group_call_link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun openInviteUsers() {
        val link = inviteLink ?: return
        pendingInviteLink = link
        val intent = Intent(this, ContactSelectorActivity::class.java)
            .putExtra(ContactSelectorActivity.EXTRA_TITLE, getString(R.string.group_call_invite_users))
        startActivityForResult(intent, REQ_INVITE_CONTACT)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_INVITE_CONTACT && resultCode == RESULT_OK && data != null) {
            val pubkey = data.getByteArrayExtra(ContactSelectorActivity.RESULT_PUBKEY) ?: return
            val link = pendingInviteLink ?: return
            pendingInviteLink = null
            Thread {
                try {
                    val now = System.currentTimeMillis()
                    val guid = getMimir().generateGuid(now, link.toByteArray())
                    getMimir().sendMessage(pubkey, guid, 0L, now, /* msg_type = */ 1000, link.toByteArray())
                } catch (e: Exception) {
                    Log.e(TAG, "send invite failed", e)
                }
            }.start()
        }
    }

    private fun toggleMute() {
        muted = !muted
        sender?.setMuted(muted)
        muteButton.setImageResource(
            if (muted) R.drawable.ic_microphone_off else R.drawable.ic_microphone_outline
        )
        muteButton.contentDescription = getString(
            if (muted) R.string.group_call_unmute else R.string.group_call_mute
        )
    }

    private fun toggleSpeaker() {
        onSpeaker = !onSpeaker
        setSpeaker(onSpeaker)
        // Match CallActivity's icon convention: ic_volume_high = off (tap to
        // enable), ic_volume_medium = on (tap to disable).
        speakerButton.setImageResource(
            if (onSpeaker) R.drawable.ic_volume_medium else R.drawable.ic_volume_high
        )
    }

    private fun setSpeaker(speaker: Boolean) {
        if (audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn) {
            // Don't fight the Bluetooth headset route.
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audioManager.availableCommunicationDevices
            val target = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            devices.find { it.type == target }?.let { audioManager.setCommunicationDevice(it) }
        } else {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = speaker
        }
    }

    // ── Audio pipeline lifecycle ─────────────────────────────────────────────

    private fun startAudio() {
        if (mixer != null) return
        Log.i(TAG, "startAudio: me=${shortHex(myPubkey)} sid=${sessionId?.let { shortHex(it) }} mediator=${mediatorPubkey?.let { shortHex(it) }} initiator=$iAmInitiator")
        val m = GroupAudioMixer()
        mixer = m
        // Route Rust-decoded AAC frames into the mixer without broadcast overhead.
        ConnectionService.groupAudioSink = { fromPubkey, seq, frame ->
            // Rust seq is UInt — we treat the low 31 bits as a signed Int for
            // the jitter buffer; overflow past 2^31 would only matter after
            // ~13 hours of continuous speech per sender, which is fine.
            m.onFrame(fromPubkey, seq.toInt(), frame)
        }
        Log.i(TAG, "groupAudioSink installed")
        m.start()

        val s = GroupAudioSender(getMimir())
        sender = s
        @Suppress("MissingPermission")
        s.start()
    }

    private fun tearDownAudio() {
        sender?.stopSender()
        sender = null
        ConnectionService.groupAudioSink = null
        mixer?.stopMixer()
        mixer = null
    }

    private fun leaveAndFinish() {
        tearDownAudio()
        Thread {
            try { getMimir().leaveGroupCall() } catch (_: Exception) {}
        }.start()
        finish()
    }

    // ── Roster + tile updates ────────────────────────────────────────────────

    private fun onRosterChanged(list: List<GroupCallParticipant>) {
        Log.i(TAG, "onRosterChanged n=${list.size}: ${list.joinToString { shortHex(it.pubkey) + "/" + it.displayName }}")
        adapter.submit(list)
        val m = mixer ?: run {
            Log.w(TAG, "onRosterChanged: mixer is null, skipping participant wiring")
            return
        }
        // Prime a decoder for every remote participant so their first packet
        // doesn't get dropped while MediaCodec is starting up.
        val present = list.map { hex(it.pubkey) }.toHashSet()
        for (p in list) {
            if (!p.pubkey.contentEquals(myPubkey)) m.addParticipant(p.pubkey)
        }
        // Drop decoders for anyone who left.
        for (key in adapter.knownPubkeyHexes()) {
            if (key !in present) {
                m.removeParticipant(adapter.pubkeyForHex(key) ?: continue)
            }
        }
        adapter.rememberRoster(list)
    }

    // ── Participants adapter with speaking indicator ─────────────────────────

    private class ParticipantsAdapter : RecyclerView.Adapter<ParticipantsAdapter.VH>() {
        private var items: List<GroupCallParticipant> = emptyList()
        private val byHex = HashMap<String, ByteArray>()
        private val speakingHex = HashSet<String>()
        var mePubkey: ByteArray = ByteArray(0)

        fun submit(list: List<GroupCallParticipant>) {
            items = list
            rememberRoster(list)
            notifyDataSetChanged()
        }

        fun rememberRoster(list: List<GroupCallParticipant>) {
            byHex.clear()
            for (p in list) byHex[hex(p.pubkey)] = p.pubkey
        }

        fun knownPubkeyHexes(): Set<String> = byHex.keys.toSet()
        fun pubkeyForHex(h: String): ByteArray? = byHex[h]

        /** Called from the UI tick: updates speaking dots based on mixer RMS. */
        fun refreshSpeaking(mixer: GroupAudioMixer?, meSpeaking: Boolean) {
            val next = HashSet<String>()
            for (p in items) {
                val isMe = p.pubkey.contentEquals(mePubkey)
                val speaking = if (isMe) meSpeaking
                else (mixer?.recentRms(p.pubkey) ?: 0) >= SPEAKING_RMS_THRESHOLD
                if (speaking) next.add(hex(p.pubkey))
            }
            if (next != speakingHex) {
                speakingHex.clear()
                speakingHex.addAll(next)
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_group_call_participant, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val p = items[position]
            val h = hex(p.pubkey)
            holder.name.text = p.displayName.ifBlank { h.take(8) }
            val isMe = p.pubkey.contentEquals(mePubkey)
            holder.you.visibility = if (isMe) View.VISIBLE else View.GONE
            val speaking = h in speakingHex
            holder.speakingDot.setBackgroundColor(
                if (speaking) 0xFF44CC44.toInt() else 0xFF999999.toInt(),
            )
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val speakingDot: View = v.findViewById(R.id.speakingDot)
            val name: TextView = v.findViewById(R.id.participantName)
            val you: TextView = v.findViewById(R.id.participantYou)
        }

        companion object {
            /** 16-bit short RMS above which a participant is considered speaking. */
            private const val SPEAKING_RMS_THRESHOLD = 600
            private fun hex(b: ByteArray): String {
                val sb = StringBuilder(b.size * 2)
                for (x in b) {
                    sb.append("0123456789abcdef"[(x.toInt() ushr 4) and 0xF])
                    sb.append("0123456789abcdef"[x.toInt() and 0xF])
                }
                return sb.toString()
            }
        }
    }

    companion object {
        const val TAG = "GroupCallActivity"
        const val EXTRA_MODE = "mode"
        const val EXTRA_MEDIATOR_PUBKEY = "mediator_pubkey"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_SESSION_KEY = "session_key"

        const val MODE_CREATE = 1
        const val MODE_JOIN = 2

        private const val REQ_INVITE_CONTACT = 2101
        private const val REQ_MIC_PERM = 2102

        private fun hex(b: ByteArray): String {
            val sb = StringBuilder(b.size * 2)
            for (x in b) {
                sb.append("0123456789abcdef"[(x.toInt() ushr 4) and 0xF])
                sb.append("0123456789abcdef"[x.toInt() and 0xF])
            }
            return sb.toString()
        }

        private fun shortHex(b: ByteArray?): String {
            if (b == null) return "<null>"
            val sb = StringBuilder(8)
            for (i in 0 until minOf(4, b.size)) {
                sb.append("0123456789abcdef"[(b[i].toInt() ushr 4) and 0xF])
                sb.append("0123456789abcdef"[b[i].toInt() and 0xF])
            }
            return sb.toString()
        }
    }
}
