package com.revertron.mimir.ui

import android.content.Context
import androidx.preference.PreferenceManager
import com.revertron.mimir.R

object SettingsData {

    const val KEY_AUTO_UPDATES = "auto-updates"
    const val KEY_IMAGES_FORMAT = "images-format"
    const val KEY_IMAGES_QUALITY = "images-quality"
    const val KEY_MESSAGE_FONT_SIZE = "message-font-size"
    const val KEY_ACCEPT_MESSAGES = "accept-messages"
    const val KEY_AUTO_DOWNLOAD_CONTACTS = "auto-download-contacts"
    const val KEY_AUTO_DOWNLOAD_OTHERS = "auto-download-others"
    const val KEY_VOICE_QUALITY = "voice-quality"
    const val KEY_FILE_SERVER_PUBKEY = "file-server-pubkey"

    fun create(context: Context): List<SettingsAdapter.Item> {
        val sp = PreferenceManager.getDefaultSharedPreferences(context)

        return listOf(
            SettingsAdapter.Item(
                id = R.string.accept_messages_from,
                titleRes = R.string.accept_messages_from,
                descriptionRes = R.string.accept_messages_from_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.auto_download_contacts,
                titleRes = R.string.auto_download_contacts,
                descriptionRes = R.string.auto_download_contacts_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.auto_download_others,
                titleRes = R.string.auto_download_others,
                descriptionRes = R.string.auto_download_others_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.resize_big_pics,
                titleRes = R.string.resize_big_pics,
                descriptionRes = R.string.resize_big_pics_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.voice_message_quality,
                titleRes = R.string.voice_message_quality,
                descriptionRes = R.string.voice_message_quality_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.message_font_size,
                titleRes = R.string.message_font_size,
                descriptionRes = R.string.message_font_size_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.backup_and_restore,
                titleRes = R.string.backup_and_restore,
                descriptionRes = R.string.backup_and_restore_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.manage_files,
                titleRes = R.string.manage_files,
                descriptionRes = R.string.manage_files_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.automatic_updates_checking,
                titleRes = R.string.automatic_updates_checking,
                descriptionRes = R.string.automatic_updates_checking_desc,
                isSwitch = true,
                checked = sp.getBoolean(KEY_AUTO_UPDATES, true)
            ),

            SettingsAdapter.Item(
                id = R.string.check_for_updates,
                titleRes = R.string.check_for_updates,
                descriptionRes = R.string.check_for_updates_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.advanced,
                titleRes = R.string.advanced,
                descriptionRes = R.string.advanced_desc,
                isSwitch = false,
                checked = false
            ),

            SettingsAdapter.Item(
                id = R.string.action_about,
                titleRes = R.string.action_about,
                descriptionRes = R.string.action_about_desc,
                isSwitch = false,
                checked = false
            )
        )
    }
}