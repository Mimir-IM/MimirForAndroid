package com.revertron.mimir.ui

import android.graphics.drawable.Drawable

data class Contact(
    val id: Long,
    val pubkey: ByteArray,
    val name: String,
    val avatar: Drawable?
)
