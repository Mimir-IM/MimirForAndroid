package com.revertron.mimir.net

import org.bouncycastle.util.encoders.Hex

/**
 * Stub retained for compile compatibility (GroupChatStatus enum + getDefaultMediatorPubkey).
 * Full implementation replaced by uniffi.mimir.MediatorNode in ConnectionService.
 */
class MediatorManager {
    companion object {
        const val DEFAULT_MEDIATOR_PUBKEY =
            "817474e1393a55ef8ab8f0407c17c447d336474fe9af2de429b4cc2861f5b278"

        fun getDefaultMediatorPubkey(): ByteArray = Hex.decode(DEFAULT_MEDIATOR_PUBKEY)
    }

    enum class GroupChatStatus {
        DISCONNECTED,
        CONNECTING,
        SUBSCRIBED,
        READ_ONLY
    }
}
