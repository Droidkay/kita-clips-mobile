package com.kita.clips

import android.app.Application

class ClipsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
    }
}
