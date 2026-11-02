package com.slidedify.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SlidedifyApplication : Application() {
    override fun onCreate() {
        // Before super.onCreate() so crashes while setting up dependencies are captured too.
        CrashReport.install(this)
        super.onCreate()
    }
}
