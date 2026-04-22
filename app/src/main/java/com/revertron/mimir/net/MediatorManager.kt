package com.revertron.mimir.net

import org.bouncycastle.util.encoders.Hex

/**
 * Stub retained for compile compatibility (GroupChatStatus enum + getDefaultMediatorPubkey).
 * Full implementation replaced by uniffi.mimir.MediatorNode in ConnectionService.
 */
class MediatorManager {
    companion object {
        const val DEFAULT_MEDIATOR_PUBKEY = "000096390FDE19F30D3BA042D77F5F5CE0E6C23BD62FF0AFC2E6C29A6FE96B33"

        fun getDefaultMediatorPubkey(): ByteArray = Hex.decode(DEFAULT_MEDIATOR_PUBKEY)
    }

    enum class GroupChatStatus {
        DISCONNECTED,
        CONNECTING,
        SUBSCRIBED,
        READ_ONLY
    }
}
