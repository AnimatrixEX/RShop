package com.rshop.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.rshop.data.work.AppNotifications
import com.rshop.di.ApplicationScope
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The Pause / Resume / Cancel buttons of a download's notification. They do what the same
 * buttons do in the app, so the two never disagree.
 */
class DownloadActionReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun downloads(): DownloadManager
        fun notifications(): AppNotifications

        @ApplicationScope
        fun scope(): CoroutineScope
    }

    override fun onReceive(context: Context, intent: Intent) {
        val gameId = intent.getStringExtra(EXTRA_GAME_ID) ?: return
        val action = intent.action ?: return
        val deps = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
        val downloads = deps.downloads()
        val notifications = deps.notifications()
        val pending = goAsync()
        deps.scope().launch {
            try {
                when (action) {
                    ACTION_PAUSE -> {
                        downloads.pause(gameId)
                        val title = downloads.titleOf(gameId)
                        if (title != null) notifications.notifyPaused(gameId, title)
                    }
                    ACTION_RESUME -> downloads.resume(gameId)
                    ACTION_CANCEL -> {
                        downloads.cancel(gameId)
                        notifications.dismiss(AppNotifications.downloadNotificationId(gameId))
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Notification action %s failed for %s", action, gameId)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.rshop.download.PAUSE"
        const val ACTION_RESUME = "com.rshop.download.RESUME"
        const val ACTION_CANCEL = "com.rshop.download.CANCEL"
        const val EXTRA_GAME_ID = "game_id"
    }
}
