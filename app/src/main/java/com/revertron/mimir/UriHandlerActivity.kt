package com.revertron.mimir

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import com.revertron.mimir.net.InviteLinkCodec
import java.net.URLDecoder


class UriHandlerActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_uri_handler)
    }

    override fun onStart() {
        super.onStart()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(data: Intent?) {
        val action = data?.action ?: return
        when (action) {
            Intent.ACTION_MAIN -> {}
            Intent.ACTION_VIEW, Intent.ACTION_SENDTO -> {
                route(data.data)
                finish()
            }
        }
    }

    private fun route(uri: Uri?) {
        if (uri == null) {
            finish()
            return
        }
        // Dispatch table
        //   mimir://gc/<pubkey>/<sid>#<key>   — group-call invite
        //   mimir://mm/u/<pubkey>/<name>      — user / contact-add (legacy authority "mm")
        //   https://go.mimir.im/u/<pubkey>/<name>   — web mirror of the contact-add link
        //
        // For `mimir://` links the discriminator is the URI authority (host);
        // for web links the authority is always `go.mimir.im` and the first path
        // segment (`u`, `gc`, …) carries the entity type.
        val scheme = uri.scheme
        val routed = when (scheme) {
            "mimir" -> routeMimirUri(uri)
            "http", "https" -> routeWebUri(uri)
            else -> false
        }
        if (!routed) {
            Toast.makeText(this, R.string.unknown_link, Toast.LENGTH_LONG).show()
        }
    }

    /** Returns true if the URI was recognised and handled. */
    private fun routeMimirUri(uri: Uri): Boolean {
        return when (uri.host) {
            "gc" -> { openGroupCall(uri); true }
            "mm" -> routeEntityPath(uri.path?.trim('/').orEmpty().split("/"))
            else -> false
        }
    }

    /** Returns true if the URI was recognised and handled. */
    private fun routeWebUri(uri: Uri): Boolean {
        // Only our own web domain is registered in the manifest, but double-check.
        if (uri.host != "go.mimir.im") return false
        return routeEntityPath(uri.path?.trim('/').orEmpty().split("/"))
    }

    /**
     * Path shape `<type>/<pubkey>[/<name>]`. Returns true if the type is known.
     * `type` is `u` for user/contact-add today; other values are reserved.
     */
    private fun routeEntityPath(parts: List<String>): Boolean {
        if (parts.isEmpty() || parts[0].isEmpty()) return false
        return when (parts[0]) {
            "u" -> { openAddContact(parts); true }
            else -> false
        }
    }

    private fun openGroupCall(uri: Uri) {
        val invite = InviteLinkCodec.parse(uri)
        if (invite == null || invite.sessionKey == null) {
            // v1 only handles encrypted (SFU) invites. MCU invites (no fragment)
            // are a future path; fall through with a clear message for now.
            Toast.makeText(this, R.string.group_call_invalid_link, Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(this, GroupCallActivity::class.java)
            .putExtra(GroupCallActivity.EXTRA_MODE, GroupCallActivity.MODE_JOIN)
            .putExtra(GroupCallActivity.EXTRA_MEDIATOR_PUBKEY, invite.callMediatorPubkey)
            .putExtra(GroupCallActivity.EXTRA_SESSION_ID, invite.sessionId)
            .putExtra(GroupCallActivity.EXTRA_SESSION_KEY, invite.sessionKey)
        startActivity(intent)
    }

    private fun openAddContact(parts: List<String>) {
        // parts: ["u", "<pubkey>", "<name>"?]
        val pubkey = parts.getOrNull(1).orEmpty()
        val name = parts.getOrNull(2)?.let { URLDecoder.decode(it, "UTF-8") }.orEmpty()
        val intent = Intent(this, AddContactActivity::class.java).apply {
            putExtra("pubkey", pubkey)
            putExtra("name", name)
        }
        startActivity(intent)
    }
}