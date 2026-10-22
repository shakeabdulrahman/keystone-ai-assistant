package com.keystone.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.firebase.appcheck.FirebaseAppCheck
import com.keystone.android.auth.appCheckProviderFactory
import com.keystone.android.push.KeystoneMessagingService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class KeystoneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before any API call, so every request can carry an App Check token.
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(appCheckProviderFactory())
        createNotificationChannels()
    }

    /** Channels must exist before a notification uses them (minSdk 26 = channels always). */
    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            KeystoneMessagingService.HR_UPDATES_CHANNEL,
            getString(R.string.notification_channel_hr_updates),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = getString(R.string.notification_channel_hr_updates_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
