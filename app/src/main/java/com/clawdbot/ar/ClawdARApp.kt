package com.clawdbot.ar

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.ffalcon.mercury.android.sdk.MercurySDK

/**
 * ClawdAR Application class.
 * Initializes Mercury SDK and core runtime for RayNeo X3 Pro glasses.
 */
class ClawdARApp : Application() {

    val runtime: NodeRuntime by lazy { NodeRuntime(this) }

    override fun onCreate() {
        super.onCreate()

        // Initialize RayNeo Mercury SDK
        MercurySDK.init(this)

        // Create notification channel for foreground service
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "clawdar_connection"
    }
}
