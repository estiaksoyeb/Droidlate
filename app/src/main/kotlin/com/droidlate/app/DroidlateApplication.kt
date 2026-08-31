package com.droidlate.app

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class DroidlateApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
        com.droidlate.app.core.notification.NotificationHelper.getInstance(this)
    }
}
