package com.revertron.mimir

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.app.AlertDialog
import android.view.ContextThemeWrapper
import com.revertron.mimir.ui.SettingsAdapter
import com.revertron.mimir.ui.SettingsData

class SettingsActivity : BaseActivity(), SettingsAdapter.Listener {

    private lateinit var preferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)

        val recycler = findViewById<RecyclerView>(R.id.settingsRecyclerView)
        recycler.setLayoutManager(LinearLayoutManager(this))
        recycler.setAdapter(SettingsAdapter(SettingsData.create(this), this))
        recycler.addItemDecoration(DividerItemDecoration(baseContext, RecyclerView.VERTICAL))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.hold_still, R.anim.slide_out_right)
    }

    override fun onSwitchToggled(id: Int, isChecked: Boolean) {
        when (id) {
            R.string.automatic_updates_checking -> {
                preferences.edit().apply {
                    putBoolean(SettingsData.KEY_AUTO_UPDATES, isChecked)
                    commit()
                }
            }
        }
    }

    override fun onItemClicked(id: Int) {
        when (id) {
            R.string.resize_big_pics -> {
                val intent = Intent(this, ImageSettingsActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
            R.string.message_font_size -> {
                val intent = Intent(this, FontSizeActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
            R.string.backup_and_restore -> {
                val intent = Intent(this, BackupActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
            R.string.advanced -> {
                val intent = Intent(this, AdvancedSettingsActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
            R.string.check_for_updates -> {
                val intent = Intent(this@SettingsActivity, ConnectionService::class.java).apply {
                    putExtra("command", "check_updates")
                }
                startService(intent)
            }
            R.string.accept_messages_from -> {
                showAcceptMessagesDialog()
            }
            R.string.auto_download_contacts -> {
                showAutoDownloadDialog(SettingsData.KEY_AUTO_DOWNLOAD_CONTACTS, R.string.auto_download_contacts, "5242880")
            }
            R.string.auto_download_others -> {
                showAutoDownloadDialog(SettingsData.KEY_AUTO_DOWNLOAD_OTHERS, R.string.auto_download_others, "0")
            }
            R.string.voice_message_quality -> {
                showVoiceQualityDialog()
            }
            R.string.action_about -> {
                val intent = Intent(this, AboutActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
        }
    }

    private fun showAutoDownloadDialog(prefKey: String, titleRes: Int, defaultValue: String) {
        val options = arrayOf(
            getString(R.string.download_never),
            getString(R.string.download_up_to_1mb),
            getString(R.string.download_up_to_5mb),
            getString(R.string.download_up_to_10mb),
            getString(R.string.download_always)
        )
        val values = arrayOf("0", "1048576", "5242880", "10485760", "-1")
        val current = preferences.getString(prefKey, defaultValue) ?: defaultValue
        val selected = values.indexOf(current).coerceAtLeast(0)

        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(getString(titleRes))
            .setSingleChoiceItems(options, selected) { dialog, which ->
                preferences.edit().apply {
                    putString(prefKey, values[which])
                    commit()
                }
                dialog.dismiss()
                val recycler = findViewById<RecyclerView>(R.id.settingsRecyclerView)
                recycler.adapter = SettingsAdapter(SettingsData.create(this), this)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showVoiceQualityDialog() {
        val options = arrayOf(
            getString(R.string.voice_quality_32),
            getString(R.string.voice_quality_64),
            getString(R.string.voice_quality_128)
        )
        val values = arrayOf("32000", "64000", "128000")
        val current = preferences.getString(SettingsData.KEY_VOICE_QUALITY, "32000") ?: "32000"
        val selected = values.indexOf(current).coerceAtLeast(0)

        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(getString(R.string.voice_message_quality))
            .setSingleChoiceItems(options, selected) { dialog, which ->
                preferences.edit().apply {
                    putString(SettingsData.KEY_VOICE_QUALITY, values[which])
                    commit()
                }
                dialog.dismiss()
                val recycler = findViewById<RecyclerView>(R.id.settingsRecyclerView)
                recycler.adapter = SettingsAdapter(SettingsData.create(this), this)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showAcceptMessagesDialog() {
        val options = arrayOf(
            getString(R.string.accept_from_everyone),
            getString(R.string.accept_from_contacts_only)
        )
        val current = preferences.getString(SettingsData.KEY_ACCEPT_MESSAGES, "everyone")
        val selected = if (current == "contacts") 1 else 0

        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(getString(R.string.accept_messages_from))
            .setSingleChoiceItems(options, selected) { dialog, which ->
                val value = if (which == 1) "contacts" else "everyone"
                preferences.edit().apply {
                    putString(SettingsData.KEY_ACCEPT_MESSAGES, value)
                    commit()
                }
                dialog.dismiss()
                // Refresh the list to show updated description
                val recycler = findViewById<RecyclerView>(R.id.settingsRecyclerView)
                recycler.adapter = SettingsAdapter(SettingsData.create(this), this)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}