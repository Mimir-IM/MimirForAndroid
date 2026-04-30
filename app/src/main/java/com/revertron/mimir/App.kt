package com.revertron.mimir

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.StrictMode
import android.os.StrictMode.VmPolicy
import android.util.Log
import androidx.preference.PreferenceManager
import uniffi.mimir.MimirApp
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
        internal const val PREF_MIGRATED = "migrated_to_mimir"
        const val ACTION_MIGRATION_COMPLETE = "ACTION_MIGRATION_COMPLETE"
    }

    /** Single source of truth: owns storage + networking.
     *  Constructed here in `onCreate` (storage-only via MimirApp.open).
     *  ConnectionService later calls mimirApp.start(...) to light up networking. */
    var mimirApp: MimirApp? = null
    enum class MediatorStatus { Disconnected, Connecting, Connected }

    var online: Boolean = false
    @Volatile var trackerAnnounced: Boolean = false
    @Volatile var mediatorStatus: MediatorStatus = MediatorStatus.Disconnected
    @Volatile var migrationNeeded: Boolean = false
    lateinit var callback: NetState

    /** Currently-foreground 1:1 chat (contact id) — set by ChatActivity in
     *  onResume / cleared in onPause. ConnectionService consults this to
     *  suppress notifications for messages whose chat the user is already
     *  reading. `null` means no chat is in the foreground. */
    @Volatile var foregroundContactId: Long? = null
    /** Currently-foreground group chat id. See `foregroundContactId`. */
    @Volatile var foregroundGroupChatId: Long? = null

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

        // Routing decisions (fresh install vs migration vs returning user)
        // live in SplashActivity now. Here we only open MimirApp for the
        // fast, synchronous returning-user path so activities that land
        // directly in MainActivity find storage ready. If we're about to
        // route through MigrationActivity or IntroductionActivity,
        // App.app.mimirApp stays null and the respective flow populates it.
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val alreadyMigrated = prefs.getBoolean(PREF_MIGRATED, false)
        if (alreadyMigrated) {
            val mimirDir = File(filesDir, "mimir")
            val app = MimirApp.open(mimirDir.absolutePath)
            // Idempotent: earlier versions of the migration left legacy
            // relative-filename avatars in the DB, which broke get_my_info.
            // Each call short-circuits once the path is already absolute.
            Thread {
                try { normalizeMigratedAvatars(app, File(filesDir, "avatars")) }
                catch (e: Exception) { Log.w(TAG, "avatar normalization: ${e.message}") }
            }.start()
            mimirApp = app
        }

        callback = NetState(this)
        val handler = Handler(mainLooper)
        handler.postDelayed({
            callback.register()
        }, 500)
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