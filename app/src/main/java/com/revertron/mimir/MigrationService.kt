package com.revertron.mimir

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import uniffi.mimir.MimirApp
import java.io.File

/**
 * Foreground service that imports data from the legacy Kotlin `SqlStorage`
 * (`<databases>/data.db`) into the new Rust-owned `mimir.db`. Runs on its own
 * lifecycle so the migration can't be interrupted by an `MainActivity`
 * reclaim, config change, or process death during activity churn. The
 * progress notification keeps the process alive until the whole import +
 * avatar normalization + WAL checkpoint succeeds.
 *
 * Broadcasts:
 *   - [ACTION_MIGRATION_PROGRESS] — fires after every worker step with
 *     [EXTRA_PROGRESS_STEP] (ordinal 0..4) and [EXTRA_PROGRESS_PERCENT].
 *   - [ACTION_MIGRATION_COMPLETE] — fires exactly once when everything
 *     succeeded. Pairs with `PREF_MIGRATED=true`.
 *   - [ACTION_MIGRATION_FAILED] — fires on a caught exception. Includes
 *     [EXTRA_ERROR_MESSAGE]. `PREF_MIGRATED` is left `false` so the next
 *     launch re-enters the migration flow via [SplashActivity].
 *
 * Idempotent: re-invocations while the worker thread is already running are
 * no-ops. The SQLite migration itself runs in a single transaction inside
 * Rust, and file copies are overwritten on retry, so resuming after a crash
 * is safe.
 */
class MigrationService : Service() {

    companion object {
        private const val TAG = "MigrationService"

        private const val NOTIFICATION_ID = 4480
        private const val CHANNEL_ID = "mimir.migration"

        private const val PREF_MIGRATED = "migrated_to_mimir"

        // ── Broadcasts ────────────────────────────────────────────────────
        const val ACTION_MIGRATION_PROGRESS = "com.revertron.mimir.MIGRATION_PROGRESS"
        const val ACTION_MIGRATION_COMPLETE = App.ACTION_MIGRATION_COMPLETE
        const val ACTION_MIGRATION_FAILED   = "com.revertron.mimir.MIGRATION_FAILED"

        const val EXTRA_PROGRESS_STEP     = "step"     // String label
        const val EXTRA_PROGRESS_PERCENT  = "percent"  // Int 0..100
        const val EXTRA_ERROR_MESSAGE     = "error_message"

        // ── Steps, in order — advancing the progress bar ──────────────────
        private val STEPS = listOf(
            "copy"        to 15,
            "open"        to 25,
            "rows"        to 70,
            "avatars"     to 90,
            "checkpoint"  to 100,
        )

        fun start(context: Context) {
            val intent = Intent(context, MigrationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    @Volatile private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithProgress(stepLabel = "copy", percent = 0)

        // Idempotent: if already running, do nothing.
        if (worker?.isAlive == true) {
            Log.i(TAG, "migration worker already running, ignoring duplicate start")
            return START_STICKY
        }

        worker = Thread({ runMigration() }, "MigrationWorker").apply { start() }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Worker ───────────────────────────────────────────────────────────

    private fun runMigration() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val oldDbFile = getDatabasePath("data.db")
        val mimirDir = File(filesDir, "mimir")
        val migrationCopy = File(filesDir, "data_migration.db")
        val legacyAvatarsDir = File(filesDir, "avatars")

        try {
            // Step 1 — snapshot the legacy DB (+ WAL/SHM sidecars).
            Log.i(TAG, "step 1/5: copying legacy DB")
            announce("copy", 15)
            oldDbFile.copyTo(migrationCopy, overwrite = true)
            val wal = File(oldDbFile.path + "-wal")
            val shm = File(oldDbFile.path + "-shm")
            if (wal.exists()) wal.copyTo(File(migrationCopy.path + "-wal"), overwrite = true)
            if (shm.exists()) shm.copyTo(File(migrationCopy.path + "-shm"), overwrite = true)

            // Step 2 — open MimirApp (storage-only; networking starts later
            // in ConnectionService after MainActivity is reached).
            Log.i(TAG, "step 2/5: opening MimirApp")
            announce("open", 25)
            val app = MimirApp.open(mimirDir.absolutePath)

            // Step 3 — Rust-side row import (single SQLite transaction).
            Log.i(TAG, "step 3/5: importing rows")
            announce("rows", 70)
            app.migrateFromAndroid(migrationCopy.absolutePath)

            // Step 4 — avatar path rehoming (legacy rows held relative
            // filenames; the new Rust side expects absolute paths).
            Log.i(TAG, "step 4/5: rehoming avatars")
            announce("avatars", 90)
            normalizeMigratedAvatars(app, legacyAvatarsDir)

            // Step 5 — flush WAL so the freshly imported rows land in the
            // main DB file even if the process dies before the next normal
            // shutdown.
            Log.i(TAG, "step 5/5: checkpoint")
            announce("checkpoint", 100)
            app.checkpoint()

            // Clean up the migration snapshot.
            migrationCopy.delete()
            File(migrationCopy.path + "-wal").delete()
            File(migrationCopy.path + "-shm").delete()

            // Commit state. Only flip PREF_MIGRATED after everything above
            // succeeded so a crash mid-way leaves us in a retryable state.
            App.app.mimirApp = app
            prefs.edit { putBoolean(PREF_MIGRATED, true) }
            App.app.migrationNeeded = false

            // The legacy database (and its WAL/SHM sidecars) is no longer
            // needed — every row has been copied into mimir.db, the
            // checkpoint above flushed the WAL, and PREF_MIGRATED is set
            // so SplashActivity won't re-enter migration. Delete it so
            // users don't permanently double-pay for storage.
            //
            // Best-effort: a failure here is logged but doesn't fail the
            // migration (migration *did* succeed; the worst outcome is a
            // few MB of stale file on disk).
            runCatching {
                if (oldDbFile.exists() && oldDbFile.delete()) {
                    Log.i(TAG, "deleted legacy data.db after successful migration")
                }
                File(oldDbFile.path + "-wal").delete()
                File(oldDbFile.path + "-shm").delete()
            }.onFailure { Log.w(TAG, "could not remove legacy data.db: ${it.message}") }

            Log.i(TAG, "migration completed successfully")
            LocalBroadcastManager.getInstance(this)
                .sendBroadcast(Intent(ACTION_MIGRATION_COMPLETE))
        } catch (e: Exception) {
            Log.e(TAG, "migration FAILED", e)
            LocalBroadcastManager.getInstance(this).sendBroadcast(
                Intent(ACTION_MIGRATION_FAILED).putExtra(EXTRA_ERROR_MESSAGE, e.message)
            )
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
    }

    private fun announce(step: String, percent: Int) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
            Intent(ACTION_MIGRATION_PROGRESS)
                .putExtra(EXTRA_PROGRESS_STEP, step)
                .putExtra(EXTRA_PROGRESS_PERCENT, percent)
        )
        // Keep the notification in sync with the UI progress bar.
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(step, percent))
    }

    // ── Notification ─────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val name = getString(R.string.channel_name_migration)
        val descriptionText = getString(R.string.channel_description_migration)
        val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply {
            description = descriptionText
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun startForegroundWithProgress(stepLabel: String, percent: Int) {
        val n = buildNotification(stepLabel, percent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun buildNotification(stepLabel: String, percent: Int): Notification {
        val title = getString(R.string.migration_notification_title)
        val body = getString(R.string.migration_notification_body, stepLabel, percent)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_mannaz_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }
}

/**
 * After `migrate_from_android` imports legacy rows, walk the account and each
 * contact and re-save any avatar whose DB column still holds a legacy
 * *relative* filename (e.g. `abc123.jpg`). The canonical `updateXxxAvatar`
 * path writes the bytes into `<data_dir>/avatars/…` and stores the resulting
 * absolute path — so `InfoProvider.get_my_info` can actually read the file.
 *
 * Lives at file-level (not inside MigrationService) so [App.onCreate] can
 * still invoke it on its own background thread for the returning-user path,
 * where avatars migrated in an earlier release might still need rehoming.
 */
internal fun normalizeMigratedAvatars(app: MimirApp, legacyAvatarsDir: File) {
    fun legacyPath(name: String): File? {
        if (name.isEmpty()) return null
        if (File(name).isAbsolute) return null // already canonical
        val candidate = File(legacyAvatarsDir, name)
        return if (candidate.exists()) candidate else null
    }

    val account = app.getAccount(1)
    if (account != null) {
        legacyPath(account.avatar)?.let { src ->
            try {
                app.updateAccountAvatar(1, src.readBytes())
                Log.i("MigrationAvatars", "Rehomed account avatar from ${src.name}")
            } catch (e: Exception) {
                Log.w("MigrationAvatars", "Rehoming account avatar failed: ${e.message}")
            }
        }
    }

    for (contact in app.getContacts()) {
        val src = legacyPath(contact.avatar) ?: continue
        try {
            app.updateContactAvatar(contact.id, src.readBytes())
            Log.i("MigrationAvatars", "Rehomed contact ${contact.id} avatar from ${src.name}")
        } catch (e: Exception) {
            Log.w("MigrationAvatars", "Rehoming contact ${contact.id} avatar failed: ${e.message}")
        }
    }
}
