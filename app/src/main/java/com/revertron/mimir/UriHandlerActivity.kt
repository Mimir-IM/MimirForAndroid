package com.revertron.mimir

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
                openAddContact(data.data)
                finish()
            }
        }
    }

    private fun openAddContact(uri: Uri?) {
        if (uri == null) {
            finish()
            return
        }
        val path = uri.path?.trim('/') ?: return
        val parts = path.split("/")
        // parts[0] contains the type of entity that we are trying to add (user, chat, news)
        var pubkey = ""
        var name = ""
        if (parts.size >= 2) {
            pubkey = parts[1]
            if (parts.size >= 3) {
                name = URLDecoder.decode(parts[2], "UTF-8")
            }
        }
        val intent = Intent(this, AddContactActivity::class.java).apply {
            putExtra("pubkey", pubkey)
            putExtra("name", name)
        }
        startActivity(intent)
    }
}