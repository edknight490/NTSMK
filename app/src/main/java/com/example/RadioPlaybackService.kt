package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class RadioPlaybackService : Service() {

    private val tag = "RadioPlaybackService"
    private val NOTIFICATION_ID = 8888
    private val CHANNEL_ID = "nts_playback_channel"

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "RadioPlaybackService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(tag, "onStartCommand action: $action")
        
        if (action == ACTION_STOP) {
            try {
                val stopIntent = Intent("com.example.ACTION_MEDIA_STOP").apply {
                    setPackage(packageName)
                }
                sendBroadcast(stopIntent)
            } catch (e: Exception) {
                Log.e(tag, "Error sending stop broadcast", e)
            }
            stopForegroundService()
        } else {
            val title = intent?.getStringExtra(EXTRA_TITLE) ?: "NTS Radio"
            val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE) ?: "Live Stream"
            showForegroundNotification(title, subtitle)
        }
        
        return START_NOT_STICKY
    }

    private fun showForegroundNotification(title: String, subtitle: String) {
        val context = applicationContext
        val notificationIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val stopServiceIntent = Intent(context, RadioPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            1,
            stopServiceIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.ic_media_play) // Use built-in system icon for standard compatibility
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPendingIntent
            )
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.d(tag, "startForeground called successfully")
        } catch (e: Exception) {
            Log.e(tag, "Error starting foreground service", e)
        }
    }

    private fun stopForegroundService() {
        Log.d(tag, "Stopping foreground service")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(tag, "onTaskRemoved called - task removed from recents")
        try {
            val stopIntent = Intent("com.example.ACTION_MEDIA_STOP").apply {
                setPackage(packageName)
            }
            sendBroadcast(stopIntent)
        } catch (e: Exception) {
            Log.e(tag, "Error sending stop broadcast in onTaskRemoved", e)
        }
        stopForegroundService()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Radio Playback",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Used to display playback status for background streaming."
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
                Log.d(tag, "Notification channel created")
            }
        }
    }

    companion object {
        const val ACTION_START = "com.example.action.START"
        const val ACTION_STOP = "com.example.action.STOP"
        const val EXTRA_TITLE = "com.example.extra.TITLE"
        const val EXTRA_SUBTITLE = "com.example.extra.SUBTITLE"
    }
}
