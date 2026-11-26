package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.local.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = AppPreferences(context).settingsFlow.first()
                if (settings.activeFamilyId.isNotBlank()) {
                    SyncScheduler.schedulePeriodicSync(context, settings.locationIntervalMinutes)
                    if (settings.networkMonitoringConsent && settings.networkMonitoringEnabled) {
                        NetworkActivityMonitor.recordDeviceBootEvent(context)
                        NetworkActivityMonitor.startMonitoringIfConsented(context)
                        SyncScheduler.enqueueImmediateNetworkSync(context)
                    }
                    val canStartFgsOnBoot = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                        LocationCaptureHelper.hasBackgroundLocationPermission(context)
                    if (settings.consentGranted && settings.locationSharingEnabled &&
                        LocationCaptureHelper.hasForegroundLocationPermission(context) &&
                        canStartFgsOnBoot
                    ) {
                        LocationForegroundService.startIfPermitted(context)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
