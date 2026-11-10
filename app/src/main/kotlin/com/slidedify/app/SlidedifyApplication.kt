package com.slidedify.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class SlidedifyApplication : Application() {
    @Inject lateinit var activeProvider: ActiveProviderMemory

    @Inject lateinit var reminders: NewLikesReminderScheduler

    override fun onCreate() {
        // Before super.onCreate() so crashes while setting up dependencies are captured too.
        CrashReport.install(this)
        super.onCreate()
        activeProvider.start()
        reminders.start()
    }
}
