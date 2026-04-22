package com.revertron.mimir

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.button.MaterialButton
import uniffi.mimir.MimirApp
import java.io.File

/**
 * First-run onboarding. Shown when no legacy DB is present and the app has
 * never been opened before. Minimal content for this patch — the slide
 * structure is wired so pages can grow later without changing the routing
 * logic that feeds into [SplashActivity].
 *
 * On "Get started": open a fresh [MimirApp], commit `PREF_MIGRATED=true`,
 * and hand off to [MainActivity] via a clean task replacement so there's no
 * residue of IntroductionActivity in the user's back stack.
 */
class IntroductionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_introduction)

        findViewById<MaterialButton>(R.id.get_started_button).setOnClickListener {
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {
        val mimirDir = File(filesDir, "mimir")
        // Fresh install ⇒ a brand-new DB here. MimirApp.open generates the
        // Ed25519 keypair on first call; subsequent launches reuse it.
        App.app.mimirApp = MimirApp.open(mimirDir.absolutePath)

        PreferenceManager.getDefaultSharedPreferences(this)
            .edit { putBoolean(App.PREF_MIGRATED, true) }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }
}
