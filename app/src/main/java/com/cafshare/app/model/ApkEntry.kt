package com.cafshare.app.model

import java.io.File

data class ApkEntry(
    val file: File,
    val label: String,
    val size: Long,
    val modified: Long
)
