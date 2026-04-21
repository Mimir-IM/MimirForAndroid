package com.revertron.mimir

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.Toolbar
import com.revertron.mimir.BaseChatActivity.Companion.PICK_IMAGE_REQUEST_CODE
import org.bouncycastle.util.encoders.Hex
import uniffi.mimir.StoredAccount
import java.io.ByteArrayOutputStream
import java.net.URLEncoder

class AccountsActivity: BaseActivity(), Toolbar.OnMenuItemClickListener {

    val accountNumber = 1 //TODO make multi account
    lateinit var accountInfo: StoredAccount
    lateinit var myNameEdit: AppCompatEditText
    lateinit var myDescriptionEdit: AppCompatEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setOnMenuItemClickListener(this)

        accountInfo = getStorage().getAccount(accountNumber)!!
        var name = accountInfo.name
        val public = Hex.toHexString(accountInfo.pubkey).uppercase()

        val avatarView = findViewById<AppCompatImageView>(R.id.avatar)
        if (accountInfo.avatar.isNotEmpty()) {
            val avatar = loadRoundedAvatar(this, accountInfo.avatar, 128, 8)
            avatarView.setImageDrawable(avatar)
        }
        avatarView.setOnClickListener {
            selectPicture()
        }

        myNameEdit = findViewById(R.id.contact_name)
        myNameEdit.setText(name)

        myDescriptionEdit = findViewById(R.id.account_description)
        myDescriptionEdit.setText(accountInfo.info)

        val pubKeyEdit = findViewById<AppCompatEditText>(R.id.contact_public_key)
        pubKeyEdit.setText(public)

        findViewById<View>(R.id.button_copy).setOnClickListener {
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("public key", pubKeyEdit.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        val host = getMimirUriHost()

        val buttonLink = findViewById<View>(R.id.button_link)
        buttonLink.setOnClickListener {
            val encoded = URLEncoder.encode(name, "UTF-8")
            val link = "https://$host/u/${pubKeyEdit.text}/$encoded"
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("mimir link", link)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }

        buttonLink.setOnLongClickListener {
            val encoded = URLEncoder.encode(name, "UTF-8")
            val link = "mimir://mm/u/${pubKeyEdit.text}/$encoded"
            val clipboard: ClipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("mimir link", link)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(applicationContext,R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
            true
        }

        val qrCode = findViewById<View>(R.id.button_qr)
        qrCode.setOnClickListener {
            showQrCodeDialog(this, myNameEdit.text.toString(), public)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onStop() {
        val newName = myNameEdit.text.toString()
        getStorage().updateAccountName(accountNumber, newName)
        val newDescription = myDescriptionEdit.text.toString()
        getStorage().updateAccountInfo(accountNumber, newDescription)
        super.onStop()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_left)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST_CODE && resultCode == RESULT_OK) {
            if (data == null || data.data == null) {
                Log.e(ChatActivity.Companion.TAG, "Error getting picture")
                return
            }
            val selectedPictureUri = data.data!!
            Thread {
                val bmp = loadSquareAvatar(this.applicationContext, selectedPictureUri, 256)
                if (bmp != null) {
                    // Preserve alpha (PNG) when the source had transparency; otherwise
                    // use JPEG for tighter compression. Bytes go straight to Rust —
                    // it writes its own canonical copy under <data_dir>/avatars/.
                    val bos = ByteArrayOutputStream()
                    if (bmp.hasAlpha()) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                    } else {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, bos)
                    }
                    getStorage().updateAccountAvatar(accountNumber, bos.toByteArray())
                    runOnUiThread {
                        val avatarView = findViewById<AppCompatImageView>(R.id.avatar)
                        avatarView.setImageBitmap(bmp)
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, R.string.error_loading_avatar_picture, Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }
    }

    private fun selectPicture() {
        val intent = Intent(Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivityForResult(intent, PICK_IMAGE_REQUEST_CODE)
    }
}