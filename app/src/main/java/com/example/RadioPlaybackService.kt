package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log

class RadioPlaybackService : Service() {

    private val tag = "RadioPlaybackService"
    private val NOTIFICATION_ID = 8888
    private val CHANNEL_ID = "nts_playback_channel"

    private var currentTitle: String = "NTS Radio"
    private var currentSubtitle: String = "Live Stream"
    private var currentPlaybackState: String = STATE_IDLE

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "RadioPlaybackService created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(tag, "onStartCommand action: $action")
        
        when (action) {
            ACTION_PLAY -> {
                Log.d(tag, "ACTION_PLAY triggered from notification")
                val playIntent = Intent("com.example.ACTION_MEDIA_PLAY").apply {
                    setPackage(packageName)
                }
                sendBroadcast(playIntent)
            }
            ACTION_PAUSE -> {
                Log.d(tag, "ACTION_PAUSE triggered from notification")
                val pauseIntent = Intent("com.example.ACTION_MEDIA_PAUSE").apply {
                    setPackage(packageName)
                }
                sendBroadcast(pauseIntent)
            }
            ACTION_STOP, ACTION_HARD_CLOSE -> {
                Log.d(tag, "ACTION_STOP / HARD_CLOSE triggered - shutting down app")
                triggerHardClose()
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: currentTitle
                val subtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: currentSubtitle
                val pbState = intent.getStringExtra(EXTRA_PLAYBACK_STATE) ?: currentPlaybackState
                currentTitle = title
                currentSubtitle = subtitle
                currentPlaybackState = pbState
                showForegroundNotification(title, subtitle, pbState)
            }
            else -> {
                if (intent != null) {
                    val title = intent.getStringExtra(EXTRA_TITLE) ?: currentTitle
                    val subtitle = intent.getStringExtra(EXTRA_SUBTITLE) ?: currentSubtitle
                    val pbState = intent.getStringExtra(EXTRA_PLAYBACK_STATE) ?: currentPlaybackState
                    currentTitle = title
                    currentSubtitle = subtitle
                    currentPlaybackState = pbState
                    showForegroundNotification(title, subtitle, pbState)
                }
            }
        }
        
        return START_NOT_STICKY
    }

    private fun showForegroundNotification(title: String, subtitle: String, playbackState: String) {
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

        // PendingIntent for Play action
        val playServiceIntent = Intent(context, RadioPlaybackService::class.java).apply {
            action = ACTION_PLAY
        }
        val playPendingIntent = PendingIntent.getService(
            context,
            10,
            playServiceIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        // PendingIntent for Pause action
        val pauseServiceIntent = Intent(context, RadioPlaybackService::class.java).apply {
            action = ACTION_PAUSE
        }
        val pausePendingIntent = PendingIntent.getService(
            context,
            11,
            pauseServiceIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        // PendingIntent for Stop action (hard closes the app)
        val stopServiceIntent = Intent(context, RadioPlaybackService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            12,
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

        val isPaused = (playbackState == STATE_PAUSED)
        val isBuffering = (playbackState == STATE_BUFFERING)

        val displayText = if (isBuffering) {
            if (subtitle.isNotEmpty()) "$subtitle • Buffering..." else "Buffering..."
        } else if (isPaused) {
            if (subtitle.isNotEmpty()) "$subtitle • Paused" else "Paused"
        } else {
            subtitle
        }

        builder
            .setContentTitle(title)
            .setContentText(displayText)
            .setSmallIcon(if (isPaused) R.drawable.ic_media_play else R.drawable.ic_media_pause)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        // Action 0: Toggle Play / Pause
        if (isPaused) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_media_play),
                    "Play",
                    playPendingIntent
                ).build()
            )
        } else {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_media_pause),
                    "Pause",
                    pausePendingIntent
                ).build()
            )
        }

        // Action 1: Stop (hard close)
        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_media_stop),
                "Stop",
                stopPendingIntent
            ).build()
        )

        // Attach MediaStyle so standard device media controls on home screen & lock screen work
        val mediaStyle = Notification.MediaStyle()
        mediaSessionToken?.let { token ->
            mediaStyle.setMediaSession(token)
        }
        mediaStyle.setShowActionsInCompactView(0, 1)
        builder.setStyle(mediaStyle)

        val notification = builder.build()

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
            Log.d(tag, "startForeground called successfully. state=$playbackState")
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
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    private fun triggerHardClose() {
        Log.d(tag, "triggerHardClose initiated - closing app completely")
        try {
            val stopIntent = Intent("com.example.ACTION_HARD_CLOSE").apply {
                setPackage(packageName)
            }
            sendBroadcast(stopIntent)
        } catch (e: Exception) {
            Log.e(tag, "Error sending stop broadcast", e)
        }

        stopForegroundService()

        try {
            MainActivity.instance?.get()?.let { act ->
                act.finishAffinity()
                act.finishAndRemoveTask()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error finishing activity", e)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            Process.killProcess(Process.myPid())
            System.exit(0)
        }, 150)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(tag, "onTaskRemoved called - keeping service alive in background if active")
        // Maintain background playback and device controls when task is removed from recents.
        // Hard close only occurs when the stop button is pressed on device controls.
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
                    description = "Displays playback status and device media controls."
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)
                Log.d(tag, "Notification channel created")
            }
        }
    }

    companion object {
        const val ACTION_START = "com.example.action.START"
        const val ACTION_UPDATE = "com.example.action.UPDATE"
        const val ACTION_PLAY = "com.example.action.PLAY"
        const val ACTION_PAUSE = "com.example.action.PAUSE"
        const val ACTION_STOP = "com.example.action.STOP"
        const val ACTION_HARD_CLOSE = "com.example.action.HARD_CLOSE"

        const val EXTRA_TITLE = "com.example.extra.TITLE"
        const val EXTRA_SUBTITLE = "com.example.extra.SUBTITLE"
        const val EXTRA_PLAYBACK_STATE = "com.example.extra.PLAYBACK_STATE"

        const val STATE_PLAYING = "PLAYING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_BUFFERING = "BUFFERING"
        const val STATE_IDLE = "IDLE"

        @Volatile
        var mediaSessionToken: MediaSession.Token? = null
    }
}
