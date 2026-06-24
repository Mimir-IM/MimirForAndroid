package com.revertron.mimir

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.widget.EditText
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
                if (isChecked) {
                    // Restart the update tick loop that was stopped when disabled
                    val intent = Intent(this, ConnectionService::class.java)
                    intent.putExtra("command", "restart_update_tick")
                    startService(intent)
                }
            }
        }
    }

    override fun onItemClicked(id: Int) {
        when (id) {
            R.string.receive_settings -> {
                val intent = Intent(this, ReceiveSettingsActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
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
            R.string.manage_files -> {
                val intent = Intent(this, FilesActivity::class.java)
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
            R.string.voice_message_quality -> {
                showVoiceQualityDialog()
            }
            R.string.client_priority -> {
                showPriorityDialog()
            }
            R.string.action_about -> {
                val intent = Intent(this, AboutActivity::class.java)
                startActivity(intent, animFromRight.toBundle())
            }
        }
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

    private fun showPriorityDialog() {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val current = preferences.getInt(SettingsData.KEY_CLIENT_PRIORITY, SettingsData.DEFAULT_CLIENT_PRIORITY)

        val editText = EditText(wrapper).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            isSingleLine = true
            setSelection(text.length)
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(wrapper)
            .setTitle(R.string.client_priority)
            .setView(editText)
            .setPositiveButton(R.string.save_button) { _, _ ->
                val value = editText.text.toString().trim().toIntOrNull()
                    ?.coerceIn(1, 100) ?: SettingsData.DEFAULT_CLIENT_PRIORITY
                preferences.edit().putInt(SettingsData.KEY_CLIENT_PRIORITY, value).apply()
                // Live update + re-announce to trackers; ConnectionService also
                // reads this value into AppConfig on its next start.
                getMimir().setPriority(value.toUByte())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}