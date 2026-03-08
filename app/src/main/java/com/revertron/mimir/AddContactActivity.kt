package com.revertron.mimir

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import org.bouncycastle.util.encoders.Hex

class AddContactActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_contact)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.add_contact)

        val nameField = findViewById<AppCompatEditText>(R.id.contact_name)
        val pubkeyField = findViewById<AppCompatEditText>(R.id.contact_pubkey)
        val messageField = findViewById<AppCompatEditText>(R.id.contact_message)

        // Pre-fill pubkey if passed via intent (e.g. from QR scan or deep link)
        intent.getStringExtra("pubkey")?.let { pubkeyField.setText(it) }
        intent.getStringExtra("name")?.let { nameField.setText(it) }

        findViewById<MaterialButton>(R.id.btn_add).setOnClickListener {
            val name = nameField.text.toString().trim()
            val pubKeyString = pubkeyField.text.toString().trim()
            val message = messageField.text.toString().trim()

            if (!validPublicKey(pubKeyString)) {
                Toast.makeText(this, R.string.wrong_public_key, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.empty_name, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            val pubkey = Hex.decode(pubKeyString)

            // Check if it's our own key
            val myPubkey = getStorage().getMyPubKey()
            if (pubkey.contentEquals(myPubkey)) {
                Toast.makeText(this, R.string.you_cant_add_yourself, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            // Check if contact already exists
            val existingId = getStorage().getContactId(pubkey)
            if (existingId > 0) {
                Toast.makeText(this, R.string.contact_already_added, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            getStorage().addContact(pubkey, name)

            // Send a contact request so the other side can add us back
            val intent = Intent(this, ConnectionService::class.java).apply {
                putExtra("command", "send_contact_request")
                putExtra("pubkey", pubkey)
                putExtra("message", message)
            }
            startService(intent)

            Toast.makeText(this, R.string.contact_added, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { finish(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }
}
