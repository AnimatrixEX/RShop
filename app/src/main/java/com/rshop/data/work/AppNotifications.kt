package com.rshop.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.rshop.MainActivity
import com.rshop.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Notification channels and the foreground notifications of long-running work. */
@Singleton
class AppNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_SYNC, context.getString(R.string.notif_channel_sync), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_DOWNLOADS, context.getString(R.string.notif_channel_downloads), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    /**
     * Foreground info for a worker. [progress] in 0..100, or null for an indeterminate bar.
     * Data-sync type: it is what both catalogue sync and file downloads are.
     */
    fun foregroundInfo(id: Int, channel: String, title: String, text: String?, progress: Int?): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent())
            .setProgress(100, progress ?: 0, progress == null)
            .build()
        return ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Final, dismissible notification (replaces the progress one with the same id). */
    fun notifyFinished(id: Int, title: String, text: String) {
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        manager.notify(id, notification)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_SYNC = "sync"
        const val CHANNEL_DOWNLOADS = "downloads"
        const val SYNC_NOTIFICATION_ID = 1
    }
}
