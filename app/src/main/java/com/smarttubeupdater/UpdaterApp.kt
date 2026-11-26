package com.smarttubeupdater

import android.app.Application

class UpdaterApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        UpdateScheduler.schedule(this)
    }
}
