package dev.bridge.android

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Restarts [BridgeService] if the OS (or an OEM task killer) stopped it. WorkManager survives reboots, so this
 * also brings Bridge back within 15 minutes of boot. 15 minutes is WorkManager's shortest period.
 */
class ServiceWatchdog(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        try {
            // Allowed from the background only while Bridge is exempt from battery optimisation (Android 12+).
            applicationContext.startForegroundService(Intent(applicationContext, BridgeService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: not exempt; the service comes back when the user opens Bridge.
            Log.w("BridgeWatchdog", "can't restart the service from the background: ${e.javaClass.simpleName}")
        }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServiceWatchdog>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("service-watchdog", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
