package com.revertron.mimir

import android.app.ActivityOptions
import android.os.Bundle
import android.os.PersistableBundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import uniffi.mimir.MimirApp

open class BaseActivity: AppCompatActivity() {

    lateinit var animFromRight: ActivityOptions
    lateinit var animFromLeft: ActivityOptions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        animFromRight = ActivityOptions.makeCustomAnimation(applicationContext, R.anim.slide_in_right, R.anim.hold_still)
        animFromLeft = ActivityOptions.makeCustomAnimation(applicationContext, R.anim.slide_in_left, R.anim.hold_still)
    }

    /** Single source of truth for both storage and networking. */
    fun getMimir(): MimirApp {
        return App.app.mimirApp!!
    }
}