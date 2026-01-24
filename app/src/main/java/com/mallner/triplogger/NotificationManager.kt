package com.mallner.triplogger

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat

@SuppressLint("RemoteViewLayout")
class AppNotificationManager(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "driving_service_channel"
    private var notificationBuilder: NotificationCompat.Builder
    private val collapsedRemoteViews: RemoteViews
    private val expandedRemoteViews: RemoteViews

    companion object {
        const val NOTIFICATION_ID = 1
    }

    init {
        createNotificationChannel()

        // Initialize RemoteViews with your custom layout
        collapsedRemoteViews = RemoteViews(context.packageName, R.layout.notification_collapsed)
        expandedRemoteViews = RemoteViews(context.packageName, R.layout.notification_expanded)

        val notificationIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopServiceIntent = Intent(context, DrivingService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopServicePendingIntent = PendingIntent.getService(
            context,
            0,
            stopServiceIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // Set up the builder with the custom layout and base properties
        //collapsedRemoteViews.setOnClickPendingIntent(R.id.button_stop_logging_collapsed, stopServicePendingIntent)
        //expandedRemoteViews.setOnClickPendingIntent(R.id.button_stop_logging, stopServicePendingIntent)

        notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)

            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.logging_active)) // A default status message

            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsedRemoteViews)
            .setCustomBigContentView(expandedRemoteViews) // Use the custom layout
            .addAction(0, context.getString(R.string.stop_service), stopServicePendingIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // The channel's importance only matters for the *first* time a notification is posted to it.
            // LOW is safest to prevent any heads-up notification.
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
    // Build the initial notification when the service starts
    fun buildInitialNotification(title: String, status: String): Notification {
        expandedRemoteViews.setTextViewText(R.id.notification_title, title)
        expandedRemoteViews.setTextViewText(R.id.notification_status_line, status)
        expandedRemoteViews.setTextViewText(R.id.notification_status_last_fix_value, "")
        expandedRemoteViews.setTextViewText(R.id.notification_status_speed_value, "")

        collapsedRemoteViews.setTextViewText(R.id.notification_collapsed_title, title)
        collapsedRemoteViews.setTextViewText(R.id.notification_collapsed_status, status)

        notificationBuilder
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        return notificationBuilder.build()
    }

    // THIS IS THE KEY METHOD FOR YOUR UPDATES
    // It's lightweight and will not reset the screen-off timer.
    fun updateStatus(statusText: String = "", lastFix: String = "", speed: String = "") {
        // 1. Update the text in the RemoteViews object
        if (!statusText.isEmpty()) {
            expandedRemoteViews.setTextViewText(R.id.notification_status_line, statusText)
            collapsedRemoteViews.setTextViewText(R.id.notification_collapsed_status, statusText)
        }
        if (!lastFix.isEmpty()) {
            expandedRemoteViews.setTextViewText(R.id.notification_status_last_fix_value, lastFix)
        }
        if (!speed.isEmpty()) {
            expandedRemoteViews.setTextViewText(R.id.notification_status_speed_value, speed)
        }
        // 2. Notify the manager. Because the builder properties haven't changed and
        //    setOnlyAlertOnce(true) is set, this is a silent, efficient update.
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    fun notify(id: Int, notification: Notification) {
        notificationManager.notify(id, notification)
    }
}


/*package com.mallner.triplogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class AppNotificationManager(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "driving_service_channel"
    private val notificationBuilder: NotificationCompat.Builder = NotificationCompat.Builder(context, channelId)
    private var notificationInitialized = false



    fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Driving Service",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

    }

    fun initNotification() {
        val notificationIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopServiceIntent = Intent(context, DrivingService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopServicePendingIntent = PendingIntent.getService(context, 0, stopServiceIntent, PendingIntent.FLAG_IMMUTABLE)

        notificationBuilder
            .setContentTitle(context.getString(R.string.app_name))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Stop", stopServicePendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        notificationInitialized = true
    }

    fun buildNotification(strMessage: String): Notification {
        if (!notificationInitialized) {
            initNotification()
        }

        return notificationBuilder
            .setContentText(strMessage)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun updateNotification(strMessage: String) {
        notificationManager.notify(1, buildNotification(strMessage))
    }

    fun cancelNotification() {
        notificationManager.cancel(1)
    }
}*/
