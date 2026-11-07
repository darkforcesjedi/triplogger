package com.mallner.triplogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.os.bundleOf
import java.util.concurrent.TimeUnit

/**
 * Builds the persistent "trip recording" notification as an Android 16 Live Update:
 * an ongoing, promoted notification using a standard (BigText) style.
 *
 * Promotion (system-wide top of shade, lock-screen card, Samsung Now bar) requires ALL of:
 *  - android.permission.POST_PROMOTED_NOTIFICATIONS in the manifest,
 *  - the android.requestPromotedOngoing extra (set below),
 *  - setOngoing(true) and a non-empty content title,
 *  - NO custom RemoteViews content (custom views are never promotable),
 *  - a channel importance above MIN.
 *
 * Two layouts:
 *  - idle:     "Service Enabled" / "Awaiting Bluetooth Connection" + Stop
 *  - recording: "Logging Active" / "GPS: Connected | OBD: Connecting"
 *               + "00:12:34 | 137 entries" + New trip + Stop
 * There is intentionally no progress bar: a trip has no known endpoint, so a
 * ProgressStyle track would be decorative.
 */
class AppNotificationManager(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "driving_service_channel"
    private val notificationBuilder: NotificationCompat.Builder
    private val stopServicePendingIntent: PendingIntent
    private val newTripPendingIntent: PendingIntent
    private var recording = false

    companion object {
        const val NOTIFICATION_ID = 1

        // Notification.EXTRA_REQUEST_PROMOTED_ONGOING; requested via extras so the
        // project does not need a QPR (API 36.1) NotificationCompat dependency.
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }

    init {
        createNotificationChannel()

        val notificationIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        stopServicePendingIntent = PendingIntent.getService(
            context,
            0,
            Intent(context, DrivingService::class.java).apply { action = DrivingService.ACTION_STOP_SERVICE },
            PendingIntent.FLAG_IMMUTABLE
        )
        newTripPendingIntent = PendingIntent.getService(
            context,
            1,
            Intent(context, DrivingService::class.java).apply { action = DrivingService.ACTION_NEW_TRIP },
            PendingIntent.FLAG_IMMUTABLE
        )

        notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(pendingIntent)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.logging_active))
            .addExtras(bundleOf(EXTRA_REQUEST_PROMOTED_ONGOING to true))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The channel's importance only matters for the *first* time a notification is posted to it.
            // LOW is the lowest importance that still allows promotion (MIN blocks it).
            val channel = NotificationChannel(
                channelId,
                "Driving Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun notificationID(): Int {
        return NOTIFICATION_ID
    }

    fun notify(id: Int, notification: Notification) {
        notificationManager.notify(id, notification)
    }

    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    /** Idle/armed layout: title + status line, Stop action only. */
    fun buildInitialNotification(title: String, status: String): Notification {
        recording = false
        notificationBuilder
            .setContentTitle(title)
            .setContentText(status)
            .setStyle(NotificationCompat.BigTextStyle().bigText(status))
        applyActions()
        return notificationBuilder.build()
    }

    /**
     * Recording layout, refreshed by the service ticker each second:
     * "Logging Active" / "GPS: Connected | OBD: Connecting" / "00:12:34 | 137 entries".
     * Omitted sensors (toggled off in Settings) drop out of the status line.
     */
    fun updateRecording(durationMs: Long, entries: Int, gpsEnabled: Boolean, hasFix: Boolean, obdEnabled: Boolean, obdConnected: Boolean) {
        recording = true
        val statusLine = listOfNotNull(
            if (gpsEnabled) "GPS: " + context.getString(
                if (hasFix) R.string.notification_connected else R.string.tile_waiting_fix
            ) else null,
            if (obdEnabled) "OBD: " + context.getString(
                if (obdConnected) R.string.notification_connected else R.string.tile_connecting
            ) else null
        ).joinToString(" | ")
        val timerLine = formatDuration(durationMs) + " | " +
            context.resources.getQuantityString(R.plurals.notification_entries, entries, entries)

        notificationBuilder
            .setContentTitle(context.getString(R.string.logging_active))
            .setContentText(statusLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$statusLine\n$timerLine"))
        applyActions()
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun applyActions() {
        notificationBuilder.clearActions()
        if (recording) {
            notificationBuilder.addAction(0, context.getString(R.string.notification_new_trip), newTripPendingIntent)
        }
        notificationBuilder.addAction(0, context.getString(R.string.stop_service), stopServicePendingIntent)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSec = TimeUnit.MILLISECONDS.toSeconds(durationMs.coerceAtLeast(0))
        return "%02d:%02d:%02d".format(
            TimeUnit.SECONDS.toHours(totalSec),
            TimeUnit.SECONDS.toMinutes(totalSec) % 60,
            totalSec % 60
        )
    }
}
