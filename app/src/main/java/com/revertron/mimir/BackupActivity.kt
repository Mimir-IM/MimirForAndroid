package com.revertron.mimir

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.revertron.mimir.ui.SettingsAdapter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * Backup/restore for the Mimir data directory.
 *
 * On-disk layout (must match what the Rust storage layer writes — see
 * `MimirStorage::new` and `save_avatar`):
 * ```
 * <filesDir>/mimir/                 ← data_dir passed to MimirApp.open
 *     mimir.db (+ -wal, -shm)       ← SQLite database
 *     avatars/                      ← account + contact avatars
 *     group_avatars/<chat_id>/      ← chat picture + per-member avatars
 *         chat.avatar
 *         <pubkey_hex>.avatar
 *     invite_avatars/               ← pending group invite previews
 *     request_avatars/              ← pending contact request previews
 * <filesDir>/files/                 ← message attachments (configured via
 *                                     AppConfig.attachmentsDir)
 * <appDataDir>/shared_prefs/        ← Android SharedPreferences
 * ```
 *
 * Backup archive entry format:
 *   `mimir.db`                              — DB snapshot via VACUUM INTO
 *   `mimir/avatars/<file>`                  — every file under data_dir
 *   `mimir/group_avatars/<chat_id>/<file>`    (excluding the live DB +
 *   `mimir/invite_avatars/<file>`             sidecars; those go through
 *   `mimir/request_avatars/<file>`            VACUUM INTO instead)
 *   `files/<file>`                          — attachments
 *   `shared_prefs/<file>`                   — preferences
 *
 * On import, both the new layout and the legacy intermediate layout
 * (top-level `avatars/` etc.) are recognized so users restoring an old
 * backup made by the broken release still get something useful.
 */
class BackupActivity : BaseActivity(), SettingsAdapter.Listener {

    companion object {
        private const val TAG = "BackupActivity"
        /** On-disk SQLite filename managed by MimirApp. */
        private const val DATABASE_NAME = "mimir.db"
        /** Subdirectory of filesDir that holds the Rust-owned DB + sidecars
         *  + every avatar subdir. Single rooted path makes export/import
         *  one walk and one delete. */
        private const val MIMIR_DIR = "mimir"
        private const val BACKUP_FILE_PREFIX = "mimir_backup_"

        /** Subdirectories under `<filesDir>/mimir/` that the Rust storage
         *  layer writes to. Used by export and import-clean alike so the
         *  list lives in one place. */
        private val MIMIR_SUBDIRS = listOf(
            "avatars",
            "group_avatars",
            "invite_avatars",
            "request_avatars",
        )
    }

    /** Absolute File handle for the current MimirApp database. */
    private fun mimirDbFile(): File = File(File(filesDir, MIMIR_DIR), DATABASE_NAME)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressDialog: AlertDialog? = null
    private var savedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let { exportData(it) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importData(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)

        val toolbar = findViewById<View>(R.id.toolbar) as Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val items = listOf(
            SettingsAdapter.Item(
                id = R.string.backup_export,
                titleRes = R.string.backup_export,
                descriptionRes = R.string.backup_export_desc,
                isSwitch = false,
                checked = false
            ),
            SettingsAdapter.Item(
                id = R.string.backup_import,
                titleRes = R.string.backup_import,
                descriptionRes = R.string.backup_import_desc,
                isSwitch = false,
                checked = false
            )
        )

        val recycler = findViewById<RecyclerView>(R.id.backupRecyclerView)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = SettingsAdapter(items, this)
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
        // No switches in backup activity
    }

    private fun lockOrientation() {
        savedOrientation = requestedOrientation
        val currentOrientation = resources.configuration.orientation
        requestedOrientation = if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    private fun unlockOrientation() {
        requestedOrientation = savedOrientation
    }

    private fun showProgressDialog(messageResId: Int) {
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        val builder = AlertDialog.Builder(wrapper)

        // Create a simple layout with progress bar and text
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(48, 32, 48, 32)

            val progressBar = ProgressBar(context).apply {
                isIndeterminate = true
            }
            addView(progressBar)

            val textView = TextView(context).apply {
                text = getString(messageResId)
                setPadding(32, 0, 0, 0)
            }
            addView(textView)
        }

        builder.setView(layout)
        builder.setCancelable(false)
        progressDialog = builder.show()
    }

    private fun dismissProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    override fun onItemClicked(id: Int) {
        when (id) {
            R.string.backup_export -> {
                val timestamp = System.currentTimeMillis()
                exportLauncher.launch("${BACKUP_FILE_PREFIX}${timestamp}.zip")
            }
            R.string.backup_import -> {
                importLauncher.launch(arrayOf("application/zip"))
            }
        }
    }

    private fun exportData(uri: Uri) {
        val outputStream = contentResolver.openOutputStream(uri)
        if (outputStream == null) {
            Toast.makeText(this, R.string.backup_export_failed, Toast.LENGTH_LONG).show()
            return
        }

        // Lock orientation and show progress
        lockOrientation()
        showProgressDialog(R.string.backup_exporting)

        // Capture paths on main thread before background work
        val mimirDir = File(filesDir, MIMIR_DIR)
        val attachmentsDir = File(filesDir, "files")
        val prefsDir = File(applicationInfo.dataDir, "shared_prefs")
        val snapshotFile = File(cacheDir, "backup_snapshot.db")

        Thread {
            var success = false
            var errorMessage: String? = null

            try {
                // Produce an atomic snapshot of the DB via VACUUM INTO. This is
                // safe while the app keeps running (it walks the DB under a
                // read lock and writes a fresh .db with no WAL sidecar).
                snapshotFile.delete() // VACUUM INTO refuses to overwrite.
                App.app.mimirApp?.backupTo(snapshotFile.absolutePath)
                    ?: throw Exception("MimirApp not available")

                ZipOutputStream(outputStream).use { zipOut ->
                    if (snapshotFile.exists()) {
                        addFileToZip(zipOut, snapshotFile, DATABASE_NAME)
                    }

                    // Walk every Rust-managed avatar subdir under the data
                    // dir. Each one becomes `mimir/<sub>/...` inside the
                    // archive so import can put it back without ambiguity.
                    for (sub in MIMIR_SUBDIRS) {
                        val dir = File(mimirDir, sub)
                        if (dir.exists()) {
                            addDirectoryToZip(zipOut, dir, "$MIMIR_DIR/$sub")
                        }
                    }

                    // Message attachments live OUTSIDE mimir/ — they sit in
                    // <filesDir>/files/ because the attachments dir is
                    // configured separately in AppConfig.attachmentsDir.
                    if (attachmentsDir.exists()) {
                        addDirectoryToZip(zipOut, attachmentsDir, "files")
                    }

                    if (prefsDir.exists()) {
                        addDirectoryToZip(zipOut, prefsDir, "shared_prefs")
                        Log.i(TAG, "Exported shared preferences")
                    }
                }

                success = true
                Log.i(TAG, "Backup exported successfully to $uri")

            } catch (e: Exception) {
                Log.e(TAG, "Error exporting backup", e)
                errorMessage = e.message
            } finally {
                snapshotFile.delete()
            }

            // Post results back to main thread
            mainHandler.post {
                dismissProgressDialog()
                unlockOrientation()

                if (success) {
                    Toast.makeText(this, R.string.backup_export_success, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, getString(R.string.backup_export_failed_with_error, errorMessage), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun importData(uri: Uri) {
        try {
            // Show warning dialog
            val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
            val builder = AlertDialog.Builder(wrapper)
            builder.setTitle(R.string.backup_import_warning_title)
            builder.setMessage(R.string.backup_import_warning_message)
            builder.setPositiveButton(R.string.backup_import_continue) { _, _ ->
                performImport(uri)
            }
            builder.setNegativeButton(R.string.cancel, null)
            builder.show()

        } catch (e: Exception) {
            Log.e(TAG, "Error showing import dialog", e)
            Toast.makeText(this, getString(R.string.backup_import_failed_with_error, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun performImport(uri: Uri) {
        // Lock orientation and show progress
        lockOrientation()
        showProgressDialog(R.string.backup_importing)

        // Capture paths on main thread before background work
        val dbPath = mimirDbFile()
        val mimirDir = File(filesDir, MIMIR_DIR)
        val appFilesDir = filesDir
        val appDataDir = applicationInfo.dataDir

        Thread {
            var success = false
            var errorMessage: String? = null
            var isInvalidFile = false

            try {
                // Validate the backup file
                if (!isValidBackupFile(uri)) {
                    Log.w(TAG, "Invalid backup file selected: $uri")
                    isInvalidFile = true
                    throw Exception("Invalid backup file")
                }

                val inputStream = contentResolver.openInputStream(uri)
                if (inputStream == null) {
                    throw Exception("Cannot open backup file")
                }

                // Cut every network connection synchronously BEFORE touching
                // the service lifecycle. close_all_connections() blocks until
                // the yggdrasil node is closed and includes a small drain
                // window, so after this no packet can reach a handler that
                // would write to the DB we're about to replace.
                Log.i(TAG, "Closing all network connections before import")
                App.app.mimirApp?.closeAllConnections()

                // Stop the service too, so it doesn't restart anything.
                mainHandler.post {
                    stopService(Intent(this, ConnectionService::class.java))
                }
                Thread.sleep(200)

                // Close the MimirApp handle so the DB file on disk is released
                // before we overwrite it. We also null it out: the process is
                // about to be killed and restarted, but if the user dismisses
                // the restart dialog, any touch of the dangling handle would
                // corrupt the restored DB. Better to NPE loudly.
                Log.i(TAG, "Closing application database connection")
                App.app.mimirApp?.close()
                App.app.mimirApp = null

                // Delete existing files before importing
                deleteExistingData()

                ZipInputStream(inputStream).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val entryName = entry.name

                        if (entry.isDirectory) {
                            // Create directory mirror of whatever the entry
                            // names — placement is decided when we hit a
                            // file inside it, so just ensure path exists.
                            val dir = resolveImportPath(entryName, appFilesDir, appDataDir, dbPath)
                            dir?.mkdirs()
                        } else {
                            val targetFile = resolveImportPath(entryName, appFilesDir, appDataDir, dbPath)
                            if (targetFile == null) {
                                Log.w(TAG, "Skipping unknown file: $entryName")
                                zipIn.closeEntry()
                                entry = zipIn.nextEntry
                                continue
                            }
                            targetFile.parentFile?.mkdirs()
                            FileOutputStream(targetFile).use { output ->
                                zipIn.copyTo(output)
                            }
                        }

                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }

                success = true
                Log.i(TAG, "Backup imported successfully from $uri")

            } catch (e: Exception) {
                Log.e(TAG, "Error importing backup", e)
                errorMessage = e.message
            }

            // Post results back to main thread
            mainHandler.post {
                dismissProgressDialog()
                unlockOrientation()

                if (success) {
                    Toast.makeText(this, R.string.backup_import_success, Toast.LENGTH_LONG).show()

                    // Restart the app after import
                    val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
                    val builder = AlertDialog.Builder(wrapper)
                    builder.setTitle(R.string.backup_import_restart_title)
                    builder.setMessage(R.string.backup_import_restart_message)
                    builder.setPositiveButton(R.string.backup_restart_now) { _, _ ->
                        // Restart app by scheduling launch and killing the process
                        // This ensures clean restart with fresh Application instance
                        val intent = packageManager.getLaunchIntentForPackage(packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(intent)

                        // Kill the process to force complete restart
                        // This clears App singleton and all database connections
                        android.os.Process.killProcess(android.os.Process.myPid())
                        exitProcess(0)
                    }
                    builder.setCancelable(false)
                    builder.show()
                } else if (isInvalidFile) {
                    Toast.makeText(this, R.string.backup_import_invalid_file, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, getString(R.string.backup_import_failed_with_error, errorMessage), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /**
     * Map a zip entry name to the on-disk destination, recognizing both
     * the new layout and the legacy intermediate layout. Returns `null`
     * when the entry name doesn't match any known prefix (caller should
     * skip it).
     */
    private fun resolveImportPath(entryName: String, appFilesDir: File, appDataDir: String, dbPath: File): File? {
        // Normalize away any leading "./" some zip writers produce.
        val name = entryName.trimStart('/').removePrefix("./")
        return when {
            // The DB always lives at the top of the archive.
            name == DATABASE_NAME -> dbPath
            // New layout: everything under mimir/ goes back into <filesDir>/mimir/.
            name.startsWith("$MIMIR_DIR/") -> File(appFilesDir, name)
            // Attachments are stored at the top level by both layouts.
            name == "files" || name.startsWith("files/") -> File(appFilesDir, name)
            // Shared prefs live under the app's data dir, not filesDir.
            name == "shared_prefs" || name.startsWith("shared_prefs/") -> File(appDataDir, name)
            // Legacy layout (intermediate broken release): top-level
            // `avatars/`, `avatars_<chat_id>/` directories. Re-home them
            // under the new <filesDir>/mimir/ tree so a restored backup
            // matches what the current Rust code actually reads.
            name == "avatars" || name.startsWith("avatars/") ->
                File(File(appFilesDir, MIMIR_DIR), name)
            name.startsWith("avatars_") -> {
                // Legacy per-chat dir like avatars_123/<pubkey>.jpg —
                // move into group_avatars/<chat_id>/<...>.
                val rest = name.removePrefix("avatars_")
                val slashIdx = rest.indexOf('/')
                if (slashIdx <= 0) return null
                val chatId = rest.substring(0, slashIdx)
                val tail = rest.substring(slashIdx + 1)
                File(File(appFilesDir, MIMIR_DIR), "group_avatars/$chatId/$tail")
            }
            else -> null
        }
    }

    private fun isValidBackupFile(uri: Uri): Boolean {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return false

            var hasDatabaseFile = false
            val foundEntries = mutableListOf<String>()

            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    val entryName = entry.name.trimStart('/').removePrefix("./")
                    foundEntries.add(entryName)

                    // Check for required database file
                    if (entryName == DATABASE_NAME) {
                        hasDatabaseFile = true
                    }

                    // Validate that all entries match expected patterns —
                    // both new (`mimir/...`) and legacy (`avatars/...`,
                    // `avatars_*`) layouts are accepted.
                    val isValidEntry = entryName == DATABASE_NAME ||
                            entryName == MIMIR_DIR || entryName.startsWith("$MIMIR_DIR/") ||
                            entryName == "files" || entryName.startsWith("files/") ||
                            entryName == "shared_prefs" || entryName.startsWith("shared_prefs/") ||
                            entryName == "avatars" || entryName.startsWith("avatars/") ||
                            entryName.startsWith("avatars_")

                    if (!isValidEntry) {
                        Log.w(TAG, "Invalid entry found in backup: $entryName")
                        return false
                    }

                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            // Must contain database file
            if (!hasDatabaseFile) {
                Log.w(TAG, "Backup file does not contain database file")
                return false
            }

            Log.i(TAG, "Backup file validation passed. Found entries: $foundEntries")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Error validating backup file", e)
            return false
        }
    }

    /**
     * Wipe the on-disk state the importer is about to repopulate. Covers
     * BOTH the new and legacy layouts so a re-import doesn't leave stale
     * files (e.g. an old `<filesDir>/avatars/` from before this release)
     * lying around to be picked up by some future code path that scans
     * legacy locations.
     */
    private fun deleteExistingData() {
        try {
            val appFilesDir = filesDir

            // New layout — entire <filesDir>/mimir/ gets nuked. The DB
            // file gets re-extracted from the archive immediately after.
            val mimirDir = File(appFilesDir, MIMIR_DIR)
            if (mimirDir.exists()) {
                val deleted = mimirDir.deleteRecursively()
                Log.i(TAG, "Mimir data dir deleted: $deleted")
            }

            // Attachments dir — outside mimir/ in both layouts.
            val attachmentsDir = File(appFilesDir, "files")
            if (attachmentsDir.exists()) {
                val deleted = attachmentsDir.deleteRecursively()
                Log.i(TAG, "Attachments directory deleted: $deleted")
            }

            // Legacy paths — only present on devices upgraded from the
            // intermediate broken release. Sweep them so a no-op restore
            // can't resurrect stale avatars under names the new code no
            // longer reads.
            val legacyAvatars = File(appFilesDir, "avatars")
            if (legacyAvatars.exists()) {
                legacyAvatars.deleteRecursively()
                Log.i(TAG, "Legacy avatars/ dir deleted")
            }
            appFilesDir.listFiles()?.forEach { file ->
                if (file.isDirectory && file.name.startsWith("avatars_")) {
                    file.deleteRecursively()
                    Log.i(TAG, "Legacy ${file.name}/ dir deleted")
                }
            }

            // Shared preferences directory.
            val prefsDir = File(applicationInfo.dataDir, "shared_prefs")
            if (prefsDir.exists()) {
                val deleted = prefsDir.deleteRecursively()
                Log.i(TAG, "Shared preferences directory deleted: $deleted")
            }

            Log.i(TAG, "All existing data deleted successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting existing data", e)
            throw e
        }
    }

    private fun addFileToZip(zipOut: ZipOutputStream, file: File, entryName: String) {
        FileInputStream(file).use { input ->
            val entry = ZipEntry(entryName)
            zipOut.putNextEntry(entry)
            input.copyTo(zipOut)
            zipOut.closeEntry()
        }
    }

    private fun addDirectoryToZip(zipOut: ZipOutputStream, directory: File, basePath: String) {
        directory.listFiles()?.forEach { file ->
            val entryName = "$basePath/${file.name}"
            if (file.isDirectory) {
                addDirectoryToZip(zipOut, file, entryName)
            } else {
                addFileToZip(zipOut, file, entryName)
            }
        }
    }
}
