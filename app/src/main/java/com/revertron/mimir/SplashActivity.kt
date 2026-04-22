package com.revertron.mimir

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File

/**
 * Launcher entry point. Decides where the user should actually land based on
 * install state:
 *   - legacy DB present but not yet migrated → MigrationActivity
 *   - no prior install                       → IntroductionActivity
 *   - returning user                         → MainActivity
 *
 * The window shows [R.style.Theme_Splash]'s background drawable for the brief
 * moment this activity lives; no `setContentView` is called so there's no
 * layout inflation cost on the launcher path.
 *
 * Back-stack hygiene: every hand-off uses `FLAG_ACTIVITY_NEW_TASK |
 * FLAG_ACTIVITY_CLEAR_TASK` so any stale task record left by a prior
 * interrupted session is replaced. Exception: if a live Mimir task already
 * exists (user tapped the launcher after backgrounding e.g. a ChatActivity),
 * we move that task to front instead of clearing it so the user doesn't lose
 * their place.
 */
class SplashActivity : Activity() {

    companion object {
        private const val TAG = "SplashActivity"
        private const val PREF_MIGRATED = "migrated_to_mimir"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val alreadyMigrated = prefs.getBoolean(PREF_MIGRATED, false)
        val oldDbFile = getDatabasePath("data.db")

        when {
            alreadyMigrated -> {
                Log.i(TAG, "routing → MainActivity (returning user)")
                routeToMainActivity()
            }
            oldDbFile.exists() -> {
                Log.i(TAG, "routing → MigrationActivity (legacy DB present)")
                App.app.migrationNeeded = true
                routeSingleShot(MigrationActivity::class.java)
            }
            else -> {
                Log.i(TAG, "routing → IntroductionActivity (fresh install)")
                routeSingleShot(IntroductionActivity::class.java)
            }
        }
    }

    /**
     * Hand off to [target] as the new task root. CLEAR_TASK + NEW_TASK nukes
     * any stale task record from a prior interrupted session (the yesterday
     * bug) and guarantees [target] is the sole root.
     */
    private fun routeSingleShot(target: Class<*>) {
        val intent = Intent(this, target).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun routeToMainActivity() {
        // If a live Mimir task already exists (user minimized from e.g.
        // ChatActivity and tapped the launcher), bring that task to front
        // instead of clobbering it. This preserves the user's in-flight
        // navigation (drafts, scroll position, etc).
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val existing = am.appTasks.firstOrNull { task ->
            val top = task.taskInfo.topActivity ?: task.taskInfo.baseActivity
            val base = task.taskInfo.baseActivity
            // We only resume tasks whose root belongs to this app and isn't
            // SplashActivity itself. `appTasks` includes the current task,
            // which we skip.
            base != null &&
                base.packageName == packageName &&
                base.className != this::class.java.name &&
                top != null &&
                top.packageName == packageName
        }
        if (existing != null) {
            Log.i(TAG, "resuming existing task rooted at ${existing.taskInfo.baseActivity?.className}")
            existing.moveToFront()
            finish()
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
