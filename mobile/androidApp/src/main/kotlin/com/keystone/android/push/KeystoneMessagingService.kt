package com.keystone.android.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.keystone.android.MainActivity
import com.keystone.android.R
import com.keystone.shared.domain.repository.DeviceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * Receives FCM messages and token changes.
 *
 * - In the background, Android shows the message's notification itself (channel
 *   `hr_updates`). In the foreground it doesn't, so we show it here, and tell open
 *   screens to refresh.
 * - Messages are deliberately vague ("Your leave request has an update"): the real
 *   details are fetched over the authenticated API once the app is open.
 */
@AndroidEntryPoint
class KeystoneMessagingService : FirebaseMessagingService() {

    @Inject lateinit var devices: DeviceRepository

    @Inject lateinit var events: PushEvents

    override fun onNewToken(token: String) {
        // Tokens rotate. Only a signed-in user can register (the API needs their token).
        // Firebase calls this on a background thread and may stop the service as soon as it
        // returns, so register *before* returning (bounded, so it can't hang the service).
        if (FirebaseAuth.getInstance().currentUser != null) {
            runBlocking { withTimeoutOrNull(REGISTER_TIMEOUT) { devices.register(token) } }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        events.emit(PushEvent(kind = message.data["kind"] ?: "update", ref = message.data["ref"]))
        val notification = message.notification ?: return
        val id = "${message.data["kind"]}:${message.data["ref"]}".hashCode()
        showNotification(notification.title ?: getString(R.string.app_name), notification.body.orEmpty(), id)
    }

    // The permission is checked just below on Android 13+; older versions don't need it.
    @SuppressLint("MissingPermission")
    private fun showNotification(title: String, body: String, notificationId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // the user said no: respect it
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, HR_UPDATES_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(open)
            // Same text on the lock screen: it already contains no details.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        // One notification per item: a newer update for the same request replaces the older one.
        NotificationManagerCompat.from(this).notify(notificationId, notification)
    }

    companion object {
        const val HR_UPDATES_CHANNEL = "hr_updates"
        private val REGISTER_TIMEOUT = 10.seconds
    }
}
