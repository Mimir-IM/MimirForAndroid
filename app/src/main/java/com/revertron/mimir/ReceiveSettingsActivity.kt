package com.revertron.mimir

import android.app.AlertDialog
import android.content.SharedPreferences
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.ui.SettingsAdapter
import com.revertron.mimir.ui.SettingsData

class ReceiveSettingsActivity : BaseActivity(), SettingsAdapter.Listener {

    private lateinit var preferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.receive_settings)

        preferences = PreferenceManager.getDefaultSharedPreferences(this.baseContext)

        val recycler = findViewById<RecyclerView>(R.id.settingsRecyclerView)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = SettingsAdapter(SettingsData.createReceiveSettings(this), this)
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

    override fun onSwitchToggled(id: Int, isChecked: Boolean) {}

    override fun onItemClicked(id: Int) {
        when (id) {
            R.string.accept_messages_from -> showAcceptMessagesDialog()
            R.string.auto_download_contacts -> {
                showAutoDownloadDialog(SettingsData.KEY_AUTO_DOWNLOAD_CONTACTS, R.string.auto_download_contacts, "5242880")
            }
            R.string.auto_download_others -> {
                showAutoDownloadDialog(SettingsData.KEY_AUTO_DOWNLOAD_OTHERS, R.string.auto_download_others, "0")
            }
            R.string.auto_download_groups -> {
                showAutoDownloadDialog(SettingsData.KEY_AUTO_DOWNLOAD_GROUPS, R.string.auto_download_groups, "5242880")
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
                refreshList()
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
                refreshList()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun refreshList() {
        val recycler = findViewById<RecyclerView>(R.id.settingsRecyclerView)
        recycler.adapter = SettingsAdapter(SettingsData.createReceiveSettings(this), this)
    }
}