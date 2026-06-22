package com.revertron.mimir.ui

import android.content.Context
import android.graphics.drawable.Drawable
import com.revertron.mimir.R
import com.revertron.mimir.formatDuration
import com.revertron.mimir.isUnsupportedMessageType
import com.revertron.mimir.loadRoundedAvatar
import com.revertron.mimir.net.MSG_TYPE_REACTION
import org.bouncycastle.util.encoders.Hex
import org.json.JSONException
import org.json.JSONObject
import uniffi.mimir.MimirApp
import uniffi.mimir.StoredMessage
import uniffi.mimir.SystemMessage
import uniffi.mimir.parseSystemMessage as mimirParseSystemMessage

/** Aggregated reaction for a single target message. */
data class ReactionDisplay(val emoji: String, val count: Int, val userReacted: Boolean)

/**
 * Interface that MessageAdapter uses to access messages and member info.
 * Decouples the adapter from the storage implementation.
 */
interface MessageProvider {
    /** Get all message IDs for a chat. Returns list of (id, isIncoming). */
    fun getMessageIds(chatId: Long, isGroup: Boolean): List<Pair<Long, Boolean>>
    /** Get a message by local ID. */
    fun getMessage(chatId: Long, id: Long, isGroup: Boolean): DisplayMessage?
    /** Get a message by GUID. */
    fun getMessageByGuid(chatId: Long, guid: Long, isGroup: Boolean): DisplayMessage?
    /** Get member display info: (nickname, avatar Drawable?). */
    fun getMemberDisplayInfo(memberId: Long, chatId: Long, avatarSize: Int, corners: Int): Pair<String, Drawable?>?
    /** Get member pubkey by member ID. */
    fun getMemberPubkey(memberId: Long, chatId: Long): ByteArray?
    /** Mark a message as read. */
    fun setMessageRead(chatId: Long, messageId: Long, isGroup: Boolean)
    /** Mark reactions as seen for a message. Returns count marked. */
    fun markReactionsAsSeenForMessage(chatId: Long, targetGuid: Long, isGroup: Boolean): Int
    /** Aggregated reactions for a specific target message. */
    fun getReactionsForMessage(chatId: Long, targetGuid: Long, isGroup: Boolean): List<ReactionDisplay>
    /** Current user's active reaction emoji on a target message, or null. */
    fun getUserReaction(chatId: Long, targetGuid: Long, isGroup: Boolean): String?
    /** Invalidate cached reaction data (call after a new reaction is stored). */
    fun invalidateReactionsCache(chatId: Long, isGroup: Boolean)
    /**
     * Formats a group-chat system message (msgType=1000) from its raw binary payload into
     * human-readable text. Returns null if the payload is not a valid system message.
     */
    fun formatSystemMessage(context: Context, chatId: Long, data: ByteArray): String?
}

/**
 * Message data for display — wraps StoredMessage with UI-friendly field names.
 */
data class DisplayMessage(
    val id: Long,
    val contact: Long,  // contact_id for P2P, sender_id for group
    val guid: Long,
    val replyTo: Long,
    val incoming: Boolean,
    var delivered: Boolean,
    val read: Boolean,
    val time: Long,
    val edit: Long,
    val type: Int,
    val data: ByteArray?,
) {
    fun getText(context: Context): String {
        return if (data != null) {
            try {
                when (type) {
                    1 -> {
                        val json = JSONObject(String(data))
                        val text = json.optString("text", "")
                        if (text.isNotEmpty()) text
                        else {
                            val name = json.optString("originalName", json.optString("name", ""))
                            name.ifEmpty { context.getString(R.string.draft_image) }
                        }
                    }
                    2 -> formatDuration(edit - time)
                    3 -> {
                        val json = JSONObject(String(data))
                        val filename = json.optString("originalName", json.optString("name", ""))
                        val text = json.optString("text", "")
                        if (text.isNotEmpty()) text
                        else filename.ifEmpty { context.getString(R.string.draft_attachment) }
                    }
                    4 -> {
                        // Video message: show caption if any, else a label.
                        val json = JSONObject(String(data))
                        json.optString("text", "").ifEmpty { context.getString(R.string.video_message) }
                    }
                    else -> if (isUnsupportedMessageType(type)) {
                        // Forward-compat: a type this client version can't render.
                        context.getString(R.string.unsupported_message_title)
                    } else {
                        String(data)
                    }
                }
            } catch (e: JSONException) {
                "Unable to format message of type $type:\n" + String(data)
            }
        } else {
            "<Empty>"
        }
    }

    companion object {
        fun from(msg: StoredMessage): DisplayMessage {
            return DisplayMessage(
                id = msg.id,
                contact = msg.contactId,
                guid = msg.guid,
                replyTo = msg.replyTo,
                incoming = msg.incoming,
                delivered = msg.delivered,
                read = msg.read,
                time = msg.sendTime,
                edit = msg.editTime,
                type = msg.msgType,
                data = msg.data,
            )
        }
    }
}

/**
 * MessageProvider backed by MimirApp.
 */
class MimirMessageProvider(
    private val storage: MimirApp,
    private val context: Context,
) : MessageProvider {

    override fun getMessageIds(chatId: Long, isGroup: Boolean): List<Pair<Long, Boolean>> {
        // Storage returns newest-first (ORDER BY id DESC), adapter expects oldest-first.
        // Reaction messages (type=10) are excluded — they're rendered as chips on the target message instead.
        return if (isGroup) {
            storage.getGroupMessages(chatId, 0, 10000)
                .filter { it.msgType != MSG_TYPE_REACTION }
                .map { Pair(it.id, it.incoming) }.reversed()
        } else {
            storage.getMessages(chatId, 0, 10000)
                .filter { it.msgType != MSG_TYPE_REACTION }
                .map { Pair(it.id, it.incoming) }.reversed()
        }
    }

    override fun getMessage(chatId: Long, id: Long, isGroup: Boolean): DisplayMessage? {
        val msg = if (isGroup) {
            storage.getGroupMessage(chatId, id)
        } else {
            storage.getMessage(id)
        }
        return msg?.let { DisplayMessage.from(it) }
    }

    override fun getMessageByGuid(chatId: Long, guid: Long, isGroup: Boolean): DisplayMessage? {
        val msg = if (isGroup) {
            storage.getGroupMessageByGuid(chatId, guid)
        } else {
            storage.getMessageByGuid(guid)
        }
        return msg?.let { DisplayMessage.from(it) }
    }

    override fun getMemberDisplayInfo(memberId: Long, chatId: Long, avatarSize: Int, corners: Int): Pair<String, Drawable?>? {
        val nickname = storage.getMemberNickname(chatId, memberId) ?: return null
        val pubkey = storage.getMemberPubkey(chatId, memberId) ?: return Pair(nickname, null)
        val member = storage.getMemberInfo(chatId, pubkey) ?: return Pair(nickname, null)
        val drawable = if (member.avatar.isNotEmpty()) {
            loadRoundedAvatar(context, member.avatar, avatarSize, corners)
        } else null
        return Pair(nickname, drawable)
    }

    override fun getMemberPubkey(memberId: Long, chatId: Long): ByteArray? {
        return storage.getMemberPubkey(chatId, memberId)
    }

    override fun setMessageRead(chatId: Long, messageId: Long, isGroup: Boolean) {
        try {
            if (isGroup) {
                storage.setGroupMessageRead(chatId, messageId, true)
            } else {
                storage.setMessageRead(chatId, messageId, true)
            }
        } catch (_: Exception) {}
    }

    override fun markReactionsAsSeenForMessage(chatId: Long, targetGuid: Long, isGroup: Boolean): Int {
        return if (isGroup) {
            storage.markGroupReactionsAsSeenForMessage(chatId, targetGuid).toInt()
        } else {
            storage.markReactionsAsSeenForMessage(chatId, targetGuid).toInt()
        }
    }

    override fun getReactionsForMessage(chatId: Long, targetGuid: Long, isGroup: Boolean): List<ReactionDisplay> {
        val raw = if (isGroup) {
            storage.getReactionsForGroupMessage(chatId, targetGuid)
        } else {
            storage.getReactionsForMessage(chatId, targetGuid)
        }
        return raw.map { ReactionDisplay(it.emoji, it.count.toInt(), it.userReacted) }
    }

    override fun getUserReaction(chatId: Long, targetGuid: Long, isGroup: Boolean): String? {
        return if (isGroup) {
            storage.getUserGroupReaction(chatId, targetGuid)
        } else {
            storage.getUserReaction(chatId, targetGuid)
        }
    }

    override fun invalidateReactionsCache(chatId: Long, isGroup: Boolean) {
        // Reactions are queried from storage on demand — no client cache to invalidate.
    }

    override fun formatSystemMessage(context: Context, chatId: Long, data: ByteArray): String? {
        val sysMsg = try { mimirParseSystemMessage(data) } catch (_: Exception) { return null }
        return when (sysMsg) {
            is SystemMessage.UserAdded -> context.getString(
                R.string.system_msg_user_accepted_invite,
                memberName(chatId, sysMsg.target),
                memberName(chatId, sysMsg.actor)
            )
            is SystemMessage.UserLeft -> context.getString(
                R.string.system_msg_user_left,
                memberName(chatId, sysMsg.user)
            )
            is SystemMessage.UserBanned -> context.getString(
                R.string.system_msg_user_banned,
                memberName(chatId, sysMsg.actor),
                memberName(chatId, sysMsg.target)
            )
            is SystemMessage.ChatDeleted -> context.getString(
                R.string.system_msg_chat_deleted,
                memberName(chatId, sysMsg.actor)
            )
            is SystemMessage.ChatInfoChanged -> context.getString(
                R.string.system_msg_chat_info_changed,
                memberName(chatId, sysMsg.actor)
            )
            is SystemMessage.PermsChanged -> {
                val perms = sysMsg.newPermissions.toInt()
                val roleName = when {
                    (perms and 0x01) != 0 -> context.getString(R.string.role_banned)
                    (perms and 0x40) != 0 -> context.getString(R.string.admin)
                    (perms and 0x20) != 0 -> context.getString(R.string.moderator)
                    else -> context.getString(R.string.role_user)
                }
                context.getString(
                    R.string.system_msg_role_changed,
                    memberName(chatId, sysMsg.actor),
                    memberName(chatId, sysMsg.target),
                    roleName
                )
            }
            is SystemMessage.MessageDeleted -> ""
            is SystemMessage.Unknown -> context.getString(
                R.string.system_msg_unknown,
                "0x" + sysMsg.eventCode.toString(16)
            )
        }
    }

    private fun memberName(chatId: Long, pubkey: ByteArray): String {
        val nickname = storage.getMemberInfo(chatId, pubkey)?.nickname
        return if (!nickname.isNullOrBlank()) nickname else Hex.toHexString(pubkey).take(8)
    }
}
