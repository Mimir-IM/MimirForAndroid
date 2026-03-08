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
            R.string.action_about -> {
                val intent = Intent(this, AboutActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
        }
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