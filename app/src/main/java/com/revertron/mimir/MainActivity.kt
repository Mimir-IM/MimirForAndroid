package com.revertron.mimir

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.snackbar.Snackbar
import com.revertron.mimir.ui.ChatListItem
import com.revertron.mimir.ui.Contact
import com.revertron.mimir.ui.ContactsAdapter
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.util.encoders.Hex
import java.io.File


class MainActivity : BaseActivity(), View.OnClickListener, View.OnLongClickListener {

    companion object {
        const val TAG = "MainActivity"
        private const val CHAT_TYPE_CONTACT = 0
        private const val CHAT_TYPE_GROUP = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            showOnlineState(App.app.online)   // update the dot color
            handler.postDelayed(this, 3_000)  // schedule again in 3 s
        }
    }

    private val connectivityReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isOnline = intent?.getBooleanExtra("is_online", false) ?: false
            showOnlineState(isOnline)
        }
    }

    /**
     * Catch every broadcast that changes the shape of the contact list:
     * incoming / outgoing messages shift the last-message column and the
     * unread badge; delivered flips the status indicator on our own rows;
     * group/mediator state transitions may reorder the list. Refresh on
     * any of them — the query is cheap and runs on a worker thread.
     */
    private val contactListRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "ACTION_MEDIATOR_LEFT_CHAT",
                "ACTION_MESSAGE_RECEIVED",
                "ACTION_GROUP_MESSAGE_RECEIVED",
                "ACTION_MESSAGE_SENT",
                "ACTION_MEDIATOR_MESSAGE_SENT",
                "ACTION_MESSAGE_DELIVERED",
                "ACTION_GROUP_MESSAGE_DELIVERED",
                "ACTION_GROUP_MESSAGE_DELETED",
                "ACTION_CONTACT_REQUEST_RECEIVED",
                "ACTION_MEMBERS_SYNCED",
                "ACTION_MEDIATOR_CONNECTED",
                "ACTION_MEDIATOR_DISCONNECTED" -> refreshContacts()
            }
        }
    }

    var avatarDrawable: Drawable? = null
    lateinit var myPubKey: ByteArray

    private val migrationReceiver2 = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onMigrationComplete()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setHomeActionContentDescription(R.string.account)

        if (App.app.migrationNeeded) {
            // Show migration overlay, defer everything else.
            findViewById<View>(R.id.migration_overlay).visibility = View.VISIBLE
            LocalBroadcastManager.getInstance(this).registerReceiver(
                migrationReceiver2, IntentFilter(App.ACTION_MIGRATION_COMPLETE)
            )
            return
        }

        initAfterReady()
    }

    private fun initAfterReady() {
        if (intent?.hasExtra("no_service") != true) {
            startService()
        }

        val account = getStorage().getAccount(1)
        myPubKey = account?.pubkey ?: ByteArray(0)
        if (account != null && account.avatar.isNotEmpty()) {
            avatarDrawable = loadRoundedAvatar(this, account.avatar)
            if (avatarDrawable != null) {
                val toolbar = findViewById<Toolbar>(R.id.toolbar)
                toolbar.navigationIcon = avatarDrawable
            }
        }
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))
    }

    private fun onMigrationComplete() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(migrationReceiver2)
        findViewById<View>(R.id.migration_overlay).visibility = View.GONE
        initAfterReady()
    }

    override fun onResume() {
        super.onResume()
        if (App.app.migrationNeeded) return
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        if (recycler.adapter == null) {
            val chatList = getChatList()
            val adapter = ContactsAdapter(chatList, this, this)
            recycler.adapter = adapter
            recycler.layoutManager = LinearLayoutManager(this)
        } else {
            refreshContacts()
        }
        invalidateOptionsMenu()
        showSnackBars()
        handler.post(refreshTask)
        LocalBroadcastManager.getInstance(this).registerReceiver(
            connectivityReceiver,
            IntentFilter("ACTION_CONNECTIVITY_CHANGED")
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            contactListRefreshReceiver,
            IntentFilter().apply {
                addAction("ACTION_MEDIATOR_LEFT_CHAT")
                addAction("ACTION_MESSAGE_RECEIVED")
                addAction("ACTION_GROUP_MESSAGE_RECEIVED")
                addAction("ACTION_MESSAGE_SENT")
                addAction("ACTION_MEDIATOR_MESSAGE_SENT")
                addAction("ACTION_MESSAGE_DELIVERED")
                addAction("ACTION_GROUP_MESSAGE_DELIVERED")
                addAction("ACTION_GROUP_MESSAGE_DELETED")
                addAction("ACTION_CONTACT_REQUEST_RECEIVED")
                addAction("ACTION_MEMBERS_SYNCED")
                addAction("ACTION_MEDIATOR_CONNECTED")
                addAction("ACTION_MEDIATOR_DISCONNECTED")
            }
        )
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshTask)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(connectivityReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(contactListRefreshReceiver)
    }

    override fun onDestroy() {
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(migrationReceiver2)
        } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)

        val invites = getStorage().getPendingInvites()
        menu.findItem(R.id.group_invites).isVisible = invites.isNotEmpty()

        val requestCount = getStorage().getContactRequests().size
        menu.findItem(R.id.contact_requests).isVisible = requestCount > 0
        return true
    }

    @Suppress("NAME_SHADOWING")
    @SuppressLint("NotifyDataSetChanged")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.plus -> {
                val intent = Intent(this, AddContactActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
                return true
            }
            R.id.contact_requests -> {
                val intent = Intent(this, ContactRequestsActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
                return true
            }
            R.id.group_invites -> {
                val intent = Intent(this, InviteListActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
                return true
            }
            R.id.create_chat -> {
                val intent = Intent(this, GroupChatEditActivity::class.java)
                intent.putExtra(GroupChatEditActivity.EXTRA_MODE, GroupChatEditActivity.MODE_CREATE)
                startActivity(intent, animFromRight.toBundle())
                return true
            }
            android.R.id.home -> {
                val intent = Intent(this, AccountsActivity::class.java)
                startActivity(intent, animFromLeft.toBundle())
            }
            R.id.action_settings -> {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
            else -> {
                Toast.makeText(this, getString(R.string.not_yet_implemented), Toast.LENGTH_SHORT).show()
            }
        }

        return super.onOptionsItemSelected(item)
    }

    private fun startService() {
        val intent = Intent(this, ConnectionService::class.java)
        intent.putExtra("command", "start")
        startService(intent)
    }

    override fun onClick(view: View) {
        if (view.tag != null) {
            when (val item = view.tag as ChatListItem) {
                is ChatListItem.SavedMessagesItem -> {
                    Log.i(TAG, "Clicked on Saved Messages")

                    val intent = Intent(this, ChatActivity::class.java)
                    intent.putExtra("contactId", ChatListItem.SAVED_MESSAGES_CONTACT_ID)
                    intent.putExtra("name", item.name)
                    intent.putExtra("savedMessages", true)
                    startActivity(intent, animFromRight.toBundle())
                }
                is ChatListItem.ContactItem -> {
                    val addr = Hex.toHexString(item.pubkey)
                    Log.i(TAG, "Clicked on contact $addr")

                    val intent = Intent(this, ChatActivity::class.java)
                    intent.putExtra("pubkey", item.pubkey)
                    intent.putExtra("name", item.name)
                    startActivity(intent, animFromRight.toBundle())
                }
                is ChatListItem.GroupChatItem -> {
                    Log.i(TAG, "Clicked on group chat ${item.chatId}")

                    val intent = Intent(this, GroupChatActivity::class.java)
                    intent.putExtra(GroupChatActivity.EXTRA_CHAT_ID, item.chatId)
                    intent.putExtra(GroupChatActivity.EXTRA_CHAT_NAME, item.name)
                    intent.putExtra(GroupChatActivity.EXTRA_CHAT_DESCRIPTION, item.description)
                    intent.putExtra(GroupChatActivity.EXTRA_IS_OWNER, item.isOwner)
                    intent.putExtra(GroupChatActivity.EXTRA_MEDIATOR_ADDRESS, item.mediatorAddress)
                    startActivity(intent, animFromRight.toBundle())
                }
            }
        }
    }

    override fun onLongClick(v: View): Boolean {
        when (val item = v.tag as ChatListItem) {
            is ChatListItem.SavedMessagesItem -> {
                // No context menu for saved messages
                return false
            }
            is ChatListItem.ContactItem -> {
                // Convert ChatListItem.ContactItem back to Contact for the popup menu
                val contact = Contact(item.id, item.pubkey, item.name, item.avatar)
                showContactPopupMenu(contact, v)
            }
            is ChatListItem.GroupChatItem -> {
                showGroupChatPopupMenu(item, v)
            }
        }
        return true
    }

    fun showOnlineState(isOnline: Boolean) {
        updateStatusTitle()
    }

    private fun updateStatusTitle() {
        val ygg = App.app.online
        val tracker = App.app.trackerAnnounced
        val mediatorStatus = App.app.mediatorStatus

        val colorRed = Color.parseColor("#F44336")
        val colorYellow = Color.parseColor("#FFC107")
        val colorGreen = Color.parseColor("#4CAF50")

        val appName = getString(R.string.app_name)
        val dot = "●"
        val builder = SpannableStringBuilder(appName)
        builder.append(" ")

        // Yggdrasil dot
        var start = builder.length
        builder.append(dot)
        builder.setSpan(ForegroundColorSpan(if (ygg) colorGreen else colorRed), start, start + dot.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Tracker dot
        start = builder.length
        builder.append(dot)
        val trackerColor = if (!ygg) colorRed else if (tracker) colorGreen else colorYellow
        builder.setSpan(ForegroundColorSpan(trackerColor), start, start + dot.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Mediator dot
        start = builder.length
        builder.append(dot)
        val mediatorColor = if (!ygg) colorRed else when (mediatorStatus) {
            App.MediatorStatus.Connected -> colorGreen
            App.MediatorStatus.Connecting -> colorYellow
            App.MediatorStatus.Disconnected -> colorRed
        }
        builder.setSpan(ForegroundColorSpan(mediatorColor), start, start + dot.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        supportActionBar?.title = builder
        supportActionBar?.subtitle = null
    }

    fun showSnackBars() {
        // Check if notifications are allowed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!areNotificationsEnabled(this)) {
                val root = findViewById<View>(android.R.id.content)
                Snackbar.make(root, getString(R.string.allow_notifications_snack), Snackbar.LENGTH_INDEFINITE)
                    .setAction(getString(R.string.allow)) {
                        try {
                            val intent =
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                }
                            startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            e.printStackTrace()
                        }
                    }
                    .setTextMaxLines(3)
                    .show()
            }
        }

        // Check if app is battery optimized
        if (!isNotBatteryOptimised(this)) {
            val root = findViewById<View>(android.R.id.content)
            Snackbar.make(root, getString(R.string.add_to_power_exceptions), Snackbar.LENGTH_INDEFINITE)
                .setAction(getString(R.string.allow)) {
                    val action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    try {
                        val intent = Intent(action, "package:$packageName".toUri())
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        e.printStackTrace()
                        // Fallback: open the generic battery-settings screen
                        startActivity(Intent(action))
                    }
                }
                .setTextMaxLines(3)
                .show()
        }

        // Check for pending contact requests
        val requestCount = getStorage().getContactRequests().size
        if (requestCount > 0) {
            val root = findViewById<View>(android.R.id.content)
            Snackbar.make(root, getString(R.string.contact_requests_snackbar, requestCount), Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.view)) {
                    val intent = Intent(this, ContactRequestsActivity::class.java)
                    startActivity(intent, animFromRight.toBundle())
                }
                .setTextMaxLines(3)
                .show()
            invalidateOptionsMenu()
        }

        // Check if our links are processed properly
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!isDefaultForDomain(this, getMimirUriHost())) {
                val root = findViewById<View>(android.R.id.content)
                Snackbar.make(root, getString(R.string.enable_domain_links), Snackbar.LENGTH_INDEFINITE)
                    .setAction(getString(R.string.enable)) {
                        val action = Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS
                        try {
                            val intent = Intent(action, "package:$packageName".toUri())
                            startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            e.printStackTrace()
                            // Fallback: open the generic battery-settings screen
                            startActivity(Intent(action))
                        }
                    }
                    .setTextMaxLines(3)
                    .show()
            }
        }
    }

    private fun showRenameContactDialog(contact: Contact) {
        val view = LayoutInflater.from(this).inflate(R.layout.rename_contact_dialog, null)
        val name = view.findViewById<AppCompatEditText>(R.id.contact_name)
        name.setText(contact.name)
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder: AlertDialog.Builder = AlertDialog.Builder(wrapper)
        builder.setTitle(getString(R.string.rename_contact))
        builder.setView(view)
        builder.setIcon(R.drawable.ic_contact_rename)
        builder.setPositiveButton(getString(R.string.rename)) { _, _ ->
            val newName = name.text.toString()
            getStorage().renameContact(contact.id, newName, true)
            refreshContacts()
        }
        builder.setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
            dialog.cancel()
        }
        builder.show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun showContactPopupMenu(contact: Contact, v: View) {
        val popup = PopupMenu(this, v, Gravity.TOP or Gravity.END)
        popup.inflate(R.menu.menu_context_contact)
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                /*R.id.add_address -> {
                    showAddAddressDialog(contact)
                    true
                }*/
                R.id.rename -> {
                    showRenameContactDialog(contact)
                    true
                }
                R.id.copy_id -> {
                    val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Mimir contact ID", Hex.toHexString(contact.pubkey))
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.remove_contact -> {
                    showRemoveContactConfirmDialog(contact)
                    true
                }
                else -> {
                    Log.w(ChatActivity.TAG, "Not implemented handler for menu item ${it.itemId}")
                    false
                }
            }
        }
        popup.show()
    }

    private fun showGroupChatPopupMenu(groupChat: ChatListItem.GroupChatItem, v: View) {
        val popup = PopupMenu(this, v, Gravity.TOP or Gravity.END)
        popup.inflate(R.menu.menu_context_group_chat)
        popup.setForceShowIcon(true)

        // Visibility rules matching GroupChatActivity.onCreateOptionsMenu
        if (groupChat.readOnly) {
            popup.menu.findItem(R.id.mute_group)?.isVisible = false
            popup.menu.findItem(R.id.leave_group)?.isVisible = false
            popup.menu.findItem(R.id.delete_group)?.isVisible = true
        } else if (!groupChat.isOwner) {
            popup.menu.findItem(R.id.delete_group)?.isVisible = false
        } else {
            popup.menu.findItem(R.id.leave_group)?.isVisible = false
        }

        // Dynamic mute/unmute title
        val currentlyMuted = getStorage().getGroupChat(groupChat.chatId)?.muted ?: false
        popup.menu.findItem(R.id.mute_group)?.title = if (currentlyMuted) {
            getString(R.string.unmute_group)
        } else {
            getString(R.string.mute_group)
        }

        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.mute_group -> {
                    toggleGroupMute(groupChat)
                    true
                }
                R.id.clear_history -> {
                    showClearGroupHistoryConfirmDialog(groupChat)
                    true
                }
                R.id.leave_group -> {
                    showLeaveGroupConfirmDialog(groupChat)
                    true
                }
                R.id.delete_group -> {
                    showDeleteGroupConfirmDialog(groupChat)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun toggleGroupMute(groupChat: ChatListItem.GroupChatItem) {
        val currentlyMuted = getStorage().getGroupChat(groupChat.chatId)?.muted ?: false
        val newMuted = !currentlyMuted
        getStorage().setGroupChatMuted(groupChat.chatId, newMuted)
        val message = if (newMuted) getString(R.string.mute_group) else getString(R.string.unmute_group)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showRemoveContactConfirmDialog(contact: Contact) {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(R.string.remove_contact_title)
            .setMessage(getString(R.string.confirm_remove_contact, contact.name))
            .setIcon(R.drawable.ic_clean_chat_outline)
            .setPositiveButton(R.string.remove_contact) { _, _ ->
                getStorage().removeContact(contact.id)
                refreshContacts()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showClearGroupHistoryConfirmDialog(groupChat: ChatListItem.GroupChatItem) {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(getString(R.string.clear_history))
            .setMessage(getString(R.string.clear_history_confirm_text))
            .setIcon(R.drawable.ic_clean_chat_outline)
            .setPositiveButton(getString(R.string.clear)) { _, _ ->
                Thread {
                    try {
                        val attachments = App.app.mimirStorage!!.clearGroupChatHistory(groupChat.chatId)
                        val filesDir = File(filesDir, "files")
                        val cacheFilesDir = File(cacheDir, "files")
                        for (name in attachments) {
                            File(filesDir, name).delete()
                            File(cacheFilesDir, name).delete()
                        }
                        runOnUiThread {
                            refreshContacts()
                            Toast.makeText(this, getString(R.string.history_cleared), Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error clearing group history", e)
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showLeaveGroupConfirmDialog(groupChat: ChatListItem.GroupChatItem) {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(R.string.leave_group)
            .setMessage(R.string.confirm_leave_group)
            .setPositiveButton(R.string.leave) { _, _ ->
                val intent = Intent(this, ConnectionService::class.java)
                intent.putExtra("command", "mediator_leave")
                intent.putExtra("chat_id", groupChat.chatId)
                startService(intent)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteGroupConfirmDialog(groupChat: ChatListItem.GroupChatItem) {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(R.string.delete_group)
            .setMessage(R.string.confirm_delete_group)
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                if (groupChat.readOnly) {
                    getStorage().deleteGroupChat(groupChat.chatId)
                    refreshContacts()
                } else {
                    val intent = Intent(this, ConnectionService::class.java)
                    intent.putExtra("command", "mediator_delete")
                    intent.putExtra("chat_id", groupChat.chatId)
                    startService(intent)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun getStoredMessageText(msg: uniffi.mimir.StoredMessage?): String? {
        if (msg?.data == null) return null
        return try {
            when (msg.msgType) {
                1 -> {
                    val json = org.json.JSONObject(String(msg.data!!))
                    val text = json.optString("text", "")
                    if (text.isNotEmpty()) text
                    else json.optString("originalName", json.optString("name", getString(R.string.draft_image)))
                }
                3 -> {
                    val json = org.json.JSONObject(String(msg.data!!))
                    val text = json.optString("text", "")
                    if (text.isNotEmpty()) text
                    else json.optString("originalName", json.optString("name", getString(R.string.draft_attachment)))
                }
                else -> String(msg.data!!)
            }
        } catch (_: Exception) { String(msg.data!!) }
    }

    private fun getChatList(): List<ChatListItem> {
        val storage = getStorage()
        val contacts = storage.getContacts()
        val groupChats = storage.getGroupChats()

        val chatItems = mutableListOf<ChatListItem>()

        // Convert contacts to ChatListItems
        chatItems.addAll(contacts.map { contact ->
            val lastMessage = storage.getLastMessage(contact.id)
            val lastMsgText = when (lastMessage?.msgType) {
                1 -> "\uD83D\uDDBC\uFE0F " + (getStoredMessageText(lastMessage) ?: "")
                3 -> "\uD83D\uDCC4 " + (getStoredMessageText(lastMessage) ?: "")
                else -> getStoredMessageText(lastMessage)
            }
            val draft = storage.getDraft(CHAT_TYPE_CONTACT, contact.id)
            val avatarDrawable = loadRoundedAvatar(this, contact.avatar.ifEmpty { null }, 48, 6)

            ChatListItem.ContactItem(
                id = contact.id,
                pubkey = contact.pubkey,
                name = contact.name,
                lastMessageText = lastMsgText,
                lastMessageTime = lastMessage?.sendTime ?: 0,
                unreadCount = storage.getUnreadCount(contact.id),
                avatar = avatarDrawable,
                unseenReactions = storage.getUnseenReactionsCount(contact.id).toInt(),
                draft = draft
            )
        })

        // Convert group chats to ChatListItems
        chatItems.addAll(groupChats.map { groupChat ->
            val avatarDrawable = loadRoundedAvatar(this, groupChat.avatar.ifEmpty { null }, 48, 6)
            val isOwner = myPubKey.contentEquals(groupChat.ownerPubkey)

            val lastMessage = storage.getLastGroupMessage(groupChat.chatId)
            val lastMessageText = when (lastMessage?.msgType) {
                1 -> "\uD83D\uDDBC\uFE0F " + (getStoredMessageText(lastMessage) ?: "")
                3 -> "\uD83D\uDCC4 " + (getStoredMessageText(lastMessage) ?: "")
                else -> getStoredMessageText(lastMessage)
            }

            val draft = storage.getDraft(CHAT_TYPE_GROUP, groupChat.chatId)

            ChatListItem.GroupChatItem(
                id = groupChat.chatId,
                chatId = groupChat.chatId,
                name = groupChat.name,
                description = groupChat.description,
                mediatorAddress = groupChat.mediatorPubkey,
                memberCount = storage.getMemberCount(groupChat.chatId),
                isOwner = isOwner,
                avatar = avatarDrawable,
                lastMessageText = lastMessageText,
                lastMessageTime = groupChat.lastMessageTime,
                unreadCount = groupChat.unreadCount,
                unseenReactions = storage.getGroupUnseenReactionsCount(groupChat.chatId).toInt(),
                draft = draft,
                readOnly = groupChat.readOnly
            )
        })

        // Sort by last message time (most recent first)
        val sortedItems = chatItems.sortedByDescending { it.lastMessageTime }.toMutableList()

        // Add Saved Messages at the top (after sorting)
        val savedMessagesIcon = ContextCompat.getDrawable(this, R.drawable.ic_saved_messages)
        val lastSavedMessage = storage.getLastMessage(ChatListItem.SAVED_MESSAGES_CONTACT_ID)
        val savedDraft = storage.getDraft(CHAT_TYPE_CONTACT, ChatListItem.SAVED_MESSAGES_CONTACT_ID)

        val messageText = when {
            savedDraft != null -> null  // Draft will be shown by adapter
            lastSavedMessage != null -> getStoredMessageText(lastSavedMessage)
            else -> getString(R.string.saved_messages_tip)
        }

        sortedItems.add(0, ChatListItem.SavedMessagesItem(
            name = getString(R.string.saved_messages),
            avatar = savedMessagesIcon,
            lastMessageText = messageText,
            lastMessageTime = lastSavedMessage?.sendTime ?: 0,
            draft = savedDraft
        ))

        return sortedItems
    }

    private fun refreshContacts() {
        val chatList = getChatList()
        val recycler = findViewById<RecyclerView>(R.id.contacts_list)
        val adapter = recycler.adapter as ContactsAdapter
        adapter.setContacts(chatList)
    }

    fun areNotificationsEnabled(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun isNotBatteryOptimised(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }
}