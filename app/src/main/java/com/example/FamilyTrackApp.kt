package com.example

import android.app.Application
import com.example.service.NetworkActivityMonitor
import org.osmdroid.config.Configuration
import java.io.File

class FamilyTrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Configure osmdroid cleanly for Android 7-15 without legacy external storage permissions
        val osmConfig = Configuration.getInstance()
        osmConfig.userAgentValue = packageName
        val basePath = File(cacheDir, "osmdroid")
        basePath.mkdirs()
        osmConfig.osmdroidBasePath = basePath
        osmConfig.osmdroidTileCache = File(basePath, "tiles").apply { mkdirs() }
        osmConfig.tileFileSystemCacheMaxBytes = 40L * 1024L * 1024L
        osmConfig.tileFileSystemCacheTrimBytes = 25L * 1024L * 1024L
        osmConfig.tileDownloadThreads = 2.toShort()
        osmConfig.tileFileSystemThreads = 2.toShort()

        // Initialize consent-based network connectivity monitor whenever app process starts
        NetworkActivityMonitor.startMonitoringIfConsented(this)
    }
}
