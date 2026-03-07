package com.revertron.mimir.net

import java.io.Serializable

/**
 * Peer connection status.
 * Used for broadcasts from ConnectionService to UI activities.
 */
enum class PeerStatus : Serializable {
    NotConnected,
    Connecting,
    Connected,
    ErrorConnecting,
}
