package com.revertron.mimir

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.util.Log
import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import uniffi.mimir.MimirStorage
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess


class App: Application() {

    companion object {
        lateinit var app: App
        private const val TAG = "App"
        private const val PREF_MIGRATED = "migrated_to_mimir"
        const val ACTION_MIGRATION_COMPLETE = "ACTION_MIGRATION_COMPLETE"
    }

    /** Shared reference to the running MimirApp so non-service callers
     *  (e.g. BackupActivity's close_all_connections before import) can reach it. */
    var mimirApp: uniffi.mimir.MimirApp? = null
    enum class MediatorStatus { Disconnected, Connecting, Connected }

    var online: Boolean = false
    @Volatile var trackerAnnounced: Boolean = false
    @Volatile var mediatorStatus: MediatorStatus = MediatorStatus.Disconnected
    var mimirStorage: MimirStorage? = null
    @Volatile var migrationNeeded: Boolean = false
    lateinit var callback: NetState

    override fun onCreate() {
        super.onCreate()

        // Install global exception handler
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleUncaughtException(thread, throwable)
        }

        if (BuildConfig.DEBUG) {
            StrictMode.setVmPolicy(
                VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        NotificationHelper.createCallChannels(this)
        NotificationHelper.migrateNotificationChannels(this)

        app = this

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val alreadyMigrated = prefs.getBoolean(PREF_MIGRATED, false)
        val oldDbFile = getDatabasePath("data.db")
        val mimirDir = File(filesDir, "mimir")

        if (alreadyMigrated) {
            // Normal startup — MimirStorage is ready.
            val store = MimirStorage(mimirDir.absolutePath)
            // Idempotent: earlier versions of the migration left legacy
            // relative-filename avatars in the DB, which broke get_my_info.
            // Each call short-circuits once the path is already absolute.
            Thread {
                try { normalizeMigratedAvatars(store, File(filesDir, "avatars")) }
                catch (e: Exception) { Log.w(TAG, "avatar normalization: ${e.message}") }
            }.start()
            mimirStorage = store
        } else if (oldDbFile.exists()) {
            migrationNeeded = true
            startMigration(oldDbFile, mimirDir, prefs)
        } else {
            // Fresh install — no old DB, create empty MimirStorage.
            mimirStorage = MimirStorage(mimirDir.absolutePath)
            prefs.edit { putBoolean(PREF_MIGRATED, true) }
        }

        callback = NetState(this)
        val handler = Handler(mainLooper)
        handler.postDelayed({
            callback.register()
        }, 500)
    }

    private fun startMigration(oldDbFile: File, mimirDir: File, prefs: android.content.SharedPreferences) {
        Thread {
            try {
                Log.i(TAG, "Starting database migration...")
                val migrationCopy = File(filesDir, "data_migration.db")
                oldDbFile.copyTo(migrationCopy, overwrite = true)
                // Also copy WAL/SHM if they exist (ensures consistent snapshot).
                val walFile = File(oldDbFile.path + "-wal")
                val shmFile = File(oldDbFile.path + "-shm")
                if (walFile.exists()) walFile.copyTo(File(migrationCopy.path + "-wal"), overwrite = true)
                if (shmFile.exists()) shmFile.copyTo(File(migrationCopy.path + "-shm"), overwrite = true)

                val store = MimirStorage(mimirDir.absolutePath)
                store.migrateFromAndroid(migrationCopy.absolutePath)
                Log.i(TAG, "Migration complete, cleaning up...")

                // The old Kotlin SqlStorage stored only the filename in
                // accounts.avatar / contacts.avatar (resolved against
                // <filesDir>/avatars/...). The new Rust side expects an
                // absolute path and reads the file verbatim in get_my_info.
                // Re-save every legacy relative avatar via the canonical
                // updater so it lands at <data_dir>/avatars/<...>.avatar and
                // the DB column holds the absolute path.
                normalizeMigratedAvatars(store, File(filesDir, "avatars"))

                migrationCopy.delete()
                File(migrationCopy.path + "-wal").delete()
                File(migrationCopy.path + "-shm").delete()

                mimirStorage = store
                prefs.edit { putBoolean(PREF_MIGRATED, true) }
                migrationNeeded = false

                Log.i(TAG, "Database migration finished successfully")
                LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(Intent(ACTION_MIGRATION_COMPLETE))
            } catch (e: Exception) {
                Log.e(TAG, "Database migration FAILED", e)
                // Fallback: mark as migrated with empty DB so app doesn't loop.
                mimirStorage = MimirStorage(mimirDir.absolutePath)
                prefs.edit { putBoolean(PREF_MIGRATED, true) }
                migrationNeeded = false
                LocalBroadcastManager.getInstance(this)
                    .sendBroadcast(Intent(ACTION_MIGRATION_COMPLETE))
            }
        }.start()
    }

    /**
     * After `migrate_from_android` imports the legacy rows, walk the account
     * and every contact and re-save any avatar whose DB column holds a
     * legacy *relative* filename (e.g. `abc123.jpg`) — rather than the new
     * absolute path. The canonical updater writes the bytes into
     * `<data_dir>/avatars/…` and stores the resulting absolute path, so
     * `InfoProvider.get_my_info` can actually read the file.
     */
    private fun normalizeMigratedAvatars(store: MimirStorage, legacyAvatarsDir: File) {
        fun legacyPath(name: String): File? {
            if (name.isEmpty()) return null
            if (File(name).isAbsolute) return null // already canonical
            val candidate = File(legacyAvatarsDir, name)
            return if (candidate.exists()) candidate else null
        }

        val account = store.getAccount(1)
        if (account != null) {
            legacyPath(account.avatar)?.let { src ->
                try {
                    store.updateAccountAvatar(1, src.readBytes())
                    Log.i(TAG, "Rehomed account avatar from ${src.name}")
                } catch (e: Exception) {
                    Log.w(TAG, "Rehoming account avatar failed: ${e.message}")
                }
            }
        }

        for (contact in store.getContacts()) {
            val src = legacyPath(contact.avatar) ?: continue
            try {
                store.updateContactAvatar(contact.id, src.readBytes())
                Log.i(TAG, "Rehomed contact ${contact.id} avatar from ${src.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Rehoming contact ${contact.id} avatar failed: ${e.message}")
            }
        }
    }

    override fun onTerminate() {
        callback.unregister()
        super.onTerminate()
    }

    private fun handleUncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val timestamp = System.currentTimeMillis()
            val report = buildCrashReport(thread, throwable, timestamp)
            val file = saveCrashReport(report, timestamp)

            // Launch CrashReportActivity
            val intent = Intent(this, CrashReportActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("crash_file", file.absolutePath)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // If we fail to show the activity, at least the file was saved
        }

        // Kill the process
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(1)
    }

    private fun buildCrashReport(thread: Thread, throwable: Throwable, timestamp: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return buildString {
            appendLine("=== MIMIR CRASH REPORT ===")
            appendLine()
            appendLine("Timestamp: ${dateFormat.format(Date(timestamp))}")
            appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android Version: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine("=== EXCEPTION ===")
            appendLine(throwable.javaClass.name + ": " + throwable.message)
            appendLine()
            appendLine("=== STACK TRACE ===")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            appendLine(sw.toString())
        }
    }

    private fun saveCrashReport(report: String, timestamp: Long): File {
        val tracesDir = File(filesDir, "traces")
        if (!tracesDir.exists()) {
            tracesDir.mkdirs()
        }
        val file = File(tracesDir, "$timestamp.txt")
        file.writeText(report)
        return file
    }
}