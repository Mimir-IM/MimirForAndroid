package com.revertron.mimir.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

data class PeerEntry(val url: String, val enabled: Boolean)

class PeerProvider(val context: Context) {
    val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    companion object {
        const val PREF_PEERS = "peers"

        val DEFAULT_PEERS = listOf(
            "tls://109.176.250.101:65534",
            "tcp://yggpeer.tilde.green:53299",
            "tcp://62.210.85.80:39565",
            "tcp://51.15.204.214:12345",
            "tcp://45.95.202.21:12403",
            "tcp://de1.mimir.im:7743?key=1bb8affffffff5ef2b5157b691dc1dd13875c1ec90e789e73bce03af983c4420",
            "tcp://de2.mimir.im:7743?key=0dedeefeffe7e36dd503d83ac8314859ef2601e0841b6d95fb6168501413c58e",
            "tcp://sk1.mimir.im:7743?key=0000000003782d918d36b649e77d70a80322b22be41d4b25455bd81f6e58580f",
            "tcp://sk2.mimir.im:7743?key=00ffed7fdfffa148ab3b01a9c53c20a7bcc8683f621598943f364fcdba034bef",
            "tcp://us1.mimir.im:7743?key=00ff9bffdbffdd6bd9a2151915d9474545c50d324f7b282bff33ef7c402ebe94",
        )
    }

    fun getAllPeers(): List<PeerEntry> {
        val raw = prefs.getString(PREF_PEERS, null)
        if (raw.isNullOrEmpty()) {
            // First run or legacy user with no custom peers — seed with defaults
            val defaults = DEFAULT_PEERS.map { PeerEntry(it, enabled = true) }
            savePeers(defaults)
            prefs.edit().remove("defaultPeers").commit()
            return defaults
        }
        val isLegacy = raw.lines().any { it.isNotEmpty() && it[0] != '+' && it[0] != '-' }
        if (isLegacy) {
            // Migrate from old format: plain URLs without +/- prefix
            val customPeers = raw.lines().filter { it.isNotEmpty() }.map { PeerEntry(it, enabled = true) }
            val useDefault = prefs.getBoolean("defaultPeers", true)
            val defaults = DEFAULT_PEERS.map { PeerEntry(it, enabled = useDefault) }
            val merged = defaults + customPeers
            savePeers(merged)
            prefs.edit().remove("defaultPeers").commit()
            return merged
        }
        return raw.lines().filter { it.isNotEmpty() }.map { line ->
            val enabled = line[0] == '+'
            val url = line.substring(1)
            PeerEntry(url, enabled)
        }
    }

    /** Returns only enabled peer URLs (for ConnectionService). */
    fun getPeers(): List<String> {
        return getAllPeers().filter { it.enabled }.map { it.url }
    }

    fun savePeers(peers: List<PeerEntry>) {
        val data = peers.joinToString("\n") { (if (it.enabled) "+" else "-") + it.url }
        prefs.edit().putString(PREF_PEERS, data).commit()
    }
}
