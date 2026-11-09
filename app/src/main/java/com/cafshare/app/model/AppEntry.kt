package com.cafshare.app.model

import android.graphics.drawable.Drawable

data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val sizeBytes: Long,
    val isSystem: Boolean,
    val icon: Drawable
)
