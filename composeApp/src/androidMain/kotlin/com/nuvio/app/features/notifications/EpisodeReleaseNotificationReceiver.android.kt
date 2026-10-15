package com.nuvio.app.features.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class EpisodeReleaseNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            EpisodeReleaseNotificationPlatform.alarmAction,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.intent.action.QUICKBOOT_POWERON",
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            -> Unit
            else -> return
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        receiverScope.launch {
            try {
                withTimeoutOrNull(deliveryTimeoutMs) {
                    EpisodeReleaseNotificationPlatform.deliverDueAndScheduleNext(appContext)
                }
            } catch (error: Throwable) {
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val deliveryTimeoutMs = 25_000L
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
