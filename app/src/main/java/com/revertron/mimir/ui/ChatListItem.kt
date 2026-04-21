package com.revertron.mimir.ui

import android.graphics.drawable.Drawable
import uniffi.mimir.StoredDraft

/**
 * Sealed class representing items in the main chat list (contacts and group chats).
 */
sealed class ChatListItem {
    abstract val id: Long
    abstract val name: String
    abstract val avatar: Drawable?
    abstract val lastMessageText: String?
    abstract val lastMessageTime: Long
    abstract val unreadCount: Int
    abstract val unseenReactions: Int
    abstract val draft: StoredDraft?

    companion object {
        const val SAVED_MESSAGES_CONTACT_ID = -100L
        /** Draft / chat-type discriminator used by MimirStorage.getDraft/saveDraft. */
        const val CHAT_TYPE_CONTACT = 0
        const val CHAT_TYPE_GROUP = 1
    }

    /**
     * Individual contact chat item.
     */
    data class ContactItem(
        override val id: Long,
        val pubkey: ByteArray,
        override val name: String,
        override val lastMessageText: String?,
        override val lastMessageTime: Long,
        override val unreadCount: Int,
        override val avatar: Drawable?,
        override val unseenReactions: Int = 0,
        override val draft: StoredDraft? = null
    ) : ChatListItem()

    /**
     * Group chat item.
     */
    data class GroupChatItem(
        override val id: Long,  // Use chatId as Long for ID
        val chatId: Long,
        override val name: String,
        val description: String,
        val mediatorAddress: ByteArray,
        val memberCount: Int,
        val isOwner: Boolean,
        override val avatar: Drawable?,
        override val lastMessageText: String?,
        override val lastMessageTime: Long,
        override val unreadCount: Int,
        override val unseenReactions: Int = 0,
        override val draft: StoredDraft? = null,
        val readOnly: Boolean = false
    ) : ChatListItem()

    /**
     * Saved messages chat item (virtual contact for saving messages to self).
     */
    data class SavedMessagesItem(
        override val id: Long = SAVED_MESSAGES_CONTACT_ID,
        override val name: String,
        override val avatar: Drawable?,
        override val lastMessageText: String?,
        override val lastMessageTime: Long,
        override val unreadCount: Int = 0,
        override val unseenReactions: Int = 0,
        override val draft: StoredDraft? = null
    ) : ChatListItem()
}
