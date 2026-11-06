package dev.fetchly

import android.app.Application

class FetchlyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadManager.init(this)
    }
}
