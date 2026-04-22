package com.revertron.mimir

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.ContextThemeWrapper
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Hosts the legacy-DB migration UX. Starts [MigrationService] as a foreground
 * service and mirrors its progress broadcasts into the on-screen ProgressBar.
 * The service drives everything; this activity is purely a view.
 *
 * Lifecycle notes:
 *   - The activity can be destroyed (user swipes away, Android reclaims) —
 *     the service continues, keeps the process alive via its notification,
 *     and re-broadcasts its current progress when we re-register.
 *   - Back is intentionally absorbed: the service can't be cancelled, so
 *     allowing Back just leads to a confusing "where did my migration go"
 *     moment. Users who want to exit can swipe away from recents.
 */
class MigrationActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MigrationActivity"
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                MigrationService.ACTION_MIGRATION_PROGRESS -> {
                    val step = intent.getStringExtra(MigrationService.EXTRA_PROGRESS_STEP) ?: return
                    val percent = intent.getIntExtra(MigrationService.EXTRA_PROGRESS_PERCENT, 0)
                    onProgress(step, percent)
                }
                MigrationService.ACTION_MIGRATION_COMPLETE -> onComplete()
                MigrationService.ACTION_MIGRATION_FAILED -> {
                    val msg = intent.getStringExtra(MigrationService.EXTRA_ERROR_MESSAGE)
                    onFailed(msg)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_migration)

        // If migration already finished before this activity was created
        // (e.g. service completed while we were being recreated), forward
        // immediately — no UI flicker, no second trigger.
        if (App.app.mimirApp != null && !App.app.migrationNeeded) {
            Log.i(TAG, "migration already complete on entry; forwarding to MainActivity")
            launchMainAndFinish()
            return
        }

        // Idempotent: MigrationService.onStartCommand skips duplicate
        // worker threads itself, so a second startForegroundService on
        // activity recreate is safe.
        MigrationService.start(this)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(MigrationService.ACTION_MIGRATION_PROGRESS)
            addAction(MigrationService.ACTION_MIGRATION_COMPLETE)
            addAction(MigrationService.ACTION_MIGRATION_FAILED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, filter)

        // If the service already finished before we resumed (e.g. completed
        // while activity was paused), forward immediately — the COMPLETE
        // broadcast has already fired and we missed it.
        if (App.app.mimirApp != null && !App.app.migrationNeeded) {
            launchMainAndFinish()
        }
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        // Swallow Back: the migration worker can't be cancelled, and letting
        // the activity finish would just leave the user staring at the
        // launcher while the service kept running.
    }

    // ── Broadcast handlers ───────────────────────────────────────────────

    private fun onProgress(step: String, percent: Int) {
        findViewById<ProgressBar>(R.id.migration_progress_bar)?.progress = percent
        findViewById<TextView>(R.id.migration_status)?.text =
            getString(R.string.migration_progress_status, step, percent)
    }

    private fun onComplete() {
        Log.i(TAG, "received MIGRATION_COMPLETE — routing to MainActivity")
        launchMainAndFinish()
    }

    private fun onFailed(errorMessage: String?) {
        Log.e(TAG, "received MIGRATION_FAILED: $errorMessage")
        val wrapper = ContextThemeWrapper(this, R.style.MimirDialog)
        AlertDialog.Builder(wrapper)
            .setTitle(R.string.migration_failed_title)
            .setMessage(getString(R.string.migration_failed_body, errorMessage ?: "unknown"))
            .setCancelable(false)
            .setPositiveButton(R.string.migration_retry) { _, _ ->
                MigrationService.start(this)
            }
            .show()
    }

    private fun launchMainAndFinish() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
