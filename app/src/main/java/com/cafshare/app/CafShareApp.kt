package com.cafshare.app

import android.app.Application
import com.google.android.material.color.DynamicColors

class CafShareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
