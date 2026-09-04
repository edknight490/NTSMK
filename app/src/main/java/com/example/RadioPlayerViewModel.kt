package com.example

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.media.AudioManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import coil.imageLoader
import coil.request.ImageRequest

sealed interface PlayerState {
    object Idle : PlayerState
    object Loading : PlayerState
    object Playing : PlayerState
    object Paused : PlayerState
    data class Error(val message: String) : PlayerState
}

data class PlayableItem(
    val id: Int,
    val title: String,
    val subtitle: String,
    val url: String,
    val isLive: Boolean,
    val upNext: String? = null,
    val imageUrl: String? = null
)

class RadioPlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "RadioPlayerViewModel"
    private var mediaPlayer: MediaPlayer? = null
    @Volatile
    private var isMediaPlayerPrepared = false
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private val audioManager = application.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager

    private var mediaSession: MediaSession? = null
    private var focusRequest: android.media.AudioFocusRequest? = null

    private var lastMetadataFetchTime = 0L
    private val METADATA_THROTTLE_MS = 30_000L // 30 seconds throttle limit
    @Volatile
    private var isRefreshingLiveMetadata = false
    private var isUserInitiatedPlayback = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.d(tag, "Audio focus lost permanently, pausing playback.")
                viewModelScope.launch(Dispatchers.Main) {
                    if (_playerState.value == PlayerState.Playing) {
                        togglePlayPause()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d(tag, "Audio focus lost transiently, pausing playback.")
                viewModelScope.launch(Dispatchers.Main) {
                    if (_playerState.value == PlayerState.Playing) {
                        togglePlayPause()
                    }
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(tag, "Audio focus lost with ducking permission. Ducking volume...")
                viewModelScope.launch(Dispatchers.Main) {
                    if (isMediaPlayerPrepared) {
                        try {
                            mediaPlayer?.setVolume(0.2f, 0.2f)
                        } catch (e: Exception) {
                            Log.e(tag, "Error ducking volume", e)
                        }
                    }
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d(tag, "Audio focus gained.")
                viewModelScope.launch(Dispatchers.Main) {
                    if (isMediaPlayerPrepared) {
                        try {
                            mediaPlayer?.setVolume(1.0f, 1.0f)
                        } catch (e: Exception) {
                            Log.e(tag, "Error restoring volume", e)
                        }
                    }
                }
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attribs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attribs)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun setupMediaSession() {
        if (mediaSession == null) {
            val context = getApplication<Application>()
            val attributionContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.createAttributionContext("audioPlayback")
            } else {
                context
            }
            mediaSession = MediaSession(attributionContext, "NtsRadioPlayerSession").apply {
                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                val pendingIntent = android.app.PendingIntent.getActivity(
                    context,
                    99,
                    activityIntent,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    } else {
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT
                    }
                )
                setSessionActivity(pendingIntent)

                setCallback(object : MediaSession.Callback() {
                    override fun onPlay() {
                        Log.d(tag, "MediaSession.onPlay() callback. isUserInitiatedPlayback=$isUserInitiatedPlayback")
                        if (isUserInitiatedPlayback) {
                            viewModelScope.launch(Dispatchers.Main) {
                                play()
                            }
                        } else {
                            Log.d(tag, "Ignoring MediaSession.onPlay() before first user playback interaction.")
                        }
                    }

                    override fun onPause() {
                        viewModelScope.launch(Dispatchers.Main) {
                            pause()
                        }
                    }

                    override fun onStop() {
                        viewModelScope.launch(Dispatchers.Main) {
                            pause()
                        }
                    }

                    override fun onSkipToNext() {
                        if (isUserInitiatedPlayback) {
                            skipToNext()
                        }
                    }

                    override fun onSkipToPrevious() {
                        if (isUserInitiatedPlayback) {
                            skipToPrevious()
                        }
                    }
                })
            }
        }
    }

    private fun updateMediaSessionState() {
        val state = when (_playerState.value) {
            PlayerState.Playing -> PlaybackState.STATE_PLAYING
            PlayerState.Loading -> PlaybackState.STATE_BUFFERING
            PlayerState.Paused -> PlaybackState.STATE_PAUSED
            is PlayerState.Error -> PlaybackState.STATE_ERROR
            else -> PlaybackState.STATE_NONE
        }
        
        val position = if (isMediaPlayerPrepared && _playerState.value == PlayerState.Playing && _currentItem.value?.isLive == false) {
            try {
                mediaPlayer?.currentPosition?.toLong() ?: 0L
            } catch (e: Exception) {
                0L
            }
        } else {
            0L
        }
        
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        
        val stateBuilder = PlaybackState.Builder()
            .setState(state, position, 1.0f)
            .setActions(actions)
            
        mediaSession?.setPlaybackState(stateBuilder.build())
        mediaSession?.isActive = (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED)
    }

    private fun drawableToBitmap(drawable: android.graphics.drawable.Drawable): android.graphics.Bitmap {
        if (drawable is android.graphics.drawable.BitmapDrawable) {
            if (drawable.bitmap != null) {
                return drawable.bitmap
            }
        }
        val width = if (drawable.intrinsicWidth <= 0) 512 else drawable.intrinsicWidth
        val height = if (drawable.intrinsicHeight <= 0) 512 else drawable.intrinsicHeight
        val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun updateMediaSessionMetadata(item: PlayableItem) {
        val metadataBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, item.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, item.subtitle)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "NTS Radio")
            
        try {
            val context = getApplication<Application>()
            val appIconDrawable = context.packageManager.getApplicationIcon(context.packageName)
            val appIconBitmap = drawableToBitmap(appIconDrawable)
            
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, appIconBitmap)
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, appIconBitmap)
            metadataBuilder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, appIconBitmap)
        } catch (e: Exception) {
            Log.e(tag, "Error setting app icon in metadata", e)
        }
        
        mediaSession?.setMetadata(metadataBuilder.build())
    }

    private fun acquireWifiLock() {
        try {
            if (wifiLock == null) {
                val wifiManager = getApplication<Application>().applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                wifiLock = wifiManager?.createWifiLock(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    } else {
                        @Suppress("DEPRECATION")
                        android.net.wifi.WifiManager.WIFI_MODE_FULL
                    },
                    "NtsPlayerWifiLock"
                )
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                Log.d(tag, "WifiLock acquired.")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error acquiring WifiLock", e)
        }
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(tag, "WifiLock released.")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error releasing WifiLock", e)
        }
    }

    private fun updateForegroundService(state: PlayerState) {
        val context = getApplication<Application>()
        val item = _currentItem.value
        if ((state == PlayerState.Playing || state == PlayerState.Loading) && item != null) {
            acquireWifiLock()
            val intent = Intent(context, RadioPlaybackService::class.java).apply {
                action = RadioPlaybackService.ACTION_START
                putExtra(RadioPlaybackService.EXTRA_TITLE, item.title)
                putExtra(RadioPlaybackService.EXTRA_SUBTITLE, item.subtitle)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to start foreground service", e)
            }
        } else {
            releaseWifiLock()
            val intent = Intent(context, RadioPlaybackService::class.java).apply {
                action = RadioPlaybackService.ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(tag, "Failed to stop foreground service", e)
            }
        }
    }

    private val volumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                val vol = currentVolume.toFloat() / maxVolume
                _volume.value = vol
                if (vol > 0f) {
                    lastNonZeroVolume = vol
                }
            }
        }
    }

    private val _liveChannels = MutableStateFlow(listOf(
        PlayableItem(
            id = 1,
            title = "NTS Radio Channel 1",
            subtitle = "Live broadcast from London, Los Angeles, and beyond.",
            url = "https://stream-relay-geo.ntslive.net/stream",
            isLive = true,
            upNext = "Up Next on Channel 1"
        ),
        PlayableItem(
            id = 2,
            title = "NTS Radio Channel 2",
            subtitle = "Live broadcast of alternative music from around the globe.",
            url = "https://stream-relay-geo.ntslive.net/stream2",
            isLive = true,
            upNext = "Up Next on Channel 2"
        )
    ))
    val liveChannels: StateFlow<List<PlayableItem>> = _liveChannels.asStateFlow()

    val mixtapes = listOf(
        PlayableItem(
            id = 3,
            title = "Poolside",
            subtitle = "Balearic, boogie, and sophisti-pop",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape4",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/4bd68475-5fb4-457c-9a16-0ae353962c10_1542844800.png"
        ),
        PlayableItem(
            id = 4,
            title = "Slow Focus",
            subtitle = "Ambient, drone, and beatless calm",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/939bd1f4-490e-40f8-99ed-90c36bb7546e_1542844800.png"
        ),
        PlayableItem(
            id = 5,
            title = "Low Key",
            subtitle = "Lo-fi hip-hop and smooth slow jams",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape2",
            isLive = false,
            imageUrl = "https://media2.ntslive.co.uk/crop/128x128/84f68576-4bd8-40b2-a333-30a1884ef307_1626134400.png"
        ),
        PlayableItem(
            id = 6,
            title = "Memory Lane",
            subtitle = "Psychedelia, folk, and garage rock",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape6",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/b38d4552-08bb-4ce4-b431-3c722ba740c8_1560470400.png"
        ),
        PlayableItem(
            id = 7,
            title = "4 To The Floor",
            subtitle = "Classic house, techno, and disco",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape5",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/4a8a020c-855d-41eb-890b-2f374a5c3179_1542844800.png"
        ),
        PlayableItem(
            id = 8,
            title = "Island Time",
            subtitle = "Roots reggae, dub, and lovers rock",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape21",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/da1ded99-fef8-4c7e-9dfa-e8aaed4d503c_1590537600.png"
        ),
        PlayableItem(
            id = 9,
            title = "The Tube",
            subtitle = "Post-punk, industrial, and more",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape26",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/477d4fbf-d609-48ca-9155-940c8b57be70_1626134400.png"
        ),
        PlayableItem(
            id = 10,
            title = "Sheet Music",
            subtitle = "Classical and contemporary",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape35",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/2ba19a01-8050-4ed9-b339-d712b0b036b8_1668038400.png"
        ),
        PlayableItem(
            id = 11,
            title = "Feelings",
            subtitle = "Gospel, boogie, and soul",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape27",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/7669d8d3-e277-4ccd-a945-2743bf2ea358_1626134400.png"
        ),
        PlayableItem(
            id = 12,
            title = "Expansions",
            subtitle = "Jazz and its many variations",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape3",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/f521368c-9b2f-413f-bd70-9d77658a827b_1542844800.png"
        ),
        PlayableItem(
            id = 13,
            title = "Rap House",
            subtitle = "Non-stop rap, trap, and drill",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape22",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/63561375-b38e-4b72-908e-d494c6bf6535_1542844800.png"
        ),
        PlayableItem(
            id = 14,
            title = "Labyrinth",
            subtitle = "Breaks, techno, and electronics",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape31",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/4f2070c2-e66a-4afc-8988-57933a6a33fe_1638230400.png"
        ),
        PlayableItem(
            id = 15,
            title = "Sweat",
            subtitle = "International party music",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape24",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/5012a9b1-2f62-4a1f-ae2b-61604306de0e_1622592000.png"
        ),
        PlayableItem(
            id = 16,
            title = "Otaku",
            subtitle = "Video game and anime soundtracks",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape36",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/bb218e69-8c0e-4cc9-9094-7527db53aa6c_1668038400.png"
        ),
        PlayableItem(
            id = 17,
            title = "The Pit",
            subtitle = "Metal and all its subgenres",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape34",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/8c638d1c-8728-4f9b-9ae2-2f1975d61cf6_1668038400.png"
        ),
        PlayableItem(
            id = 18,
            title = "Field Recordings",
            subtitle = "Natural ambient sounds",
            url = "https://stream-mixtape-geo.ntslive.net/mixtape23",
            isLive = false,
            imageUrl = "https://media.ntslive.co.uk/crop/128x128/6790fb8c-fd67-428d-8e88-50296d89ddeb_1622592000.png"
        )
    )

    private val _currentItem = MutableStateFlow<PlayableItem?>(null)
    val currentItem: StateFlow<PlayableItem?> = _currentItem.asStateFlow()

    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _volume = MutableStateFlow(0.8f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private var lastNonZeroVolume: Float = 0.8f

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private fun saveCurrentItem(item: PlayableItem) {
        try {
            val prefs = getApplication<Application>().getSharedPreferences("nts_player_prefs", android.content.Context.MODE_PRIVATE)
            prefs.edit().apply {
                putInt("last_played_id", item.id)
                putBoolean("last_played_is_live", item.isLive)
                apply()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error saving current item to prefs", e)
        }
    }

    private val stopReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.example.ACTION_MEDIA_STOP") {
                Log.d(tag, "Stop broadcast received, pausing playback.")
                viewModelScope.launch(Dispatchers.Main) {
                    pause()
                }
            }
        }
    }

    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.d(tag, "Audio becoming noisy, pausing playback.")
                viewModelScope.launch(Dispatchers.Main) {
                    pause()
                }
            }
        }
    }

    init {
        // Initialize volume state with current device media volume
        try {
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val initialVolume = currentVolume.toFloat() / maxVolume
            _volume.value = initialVolume
            if (initialVolume > 0f) {
                lastNonZeroVolume = initialVolume
            }
            
            val filter = android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            application.registerReceiver(volumeReceiver, filter)
        } catch (e: Exception) {
            Log.e(tag, "Error registering volume receiver", e)
        }

        // Register broadcast receivers for system stop action and audio becoming noisy (headphone unplugged)
        try {
            val stopFilter = android.content.IntentFilter("com.example.ACTION_MEDIA_STOP")
            androidx.core.content.ContextCompat.registerReceiver(
                application,
                stopReceiver,
                stopFilter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.e(tag, "Error registering stop receiver", e)
        }

        try {
            val noisyFilter = android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            application.registerReceiver(noisyReceiver, noisyFilter)
        } catch (e: Exception) {
            Log.e(tag, "Error registering noisy receiver", e)
        }

        // Load last played item from SharedPreferences or default to Channel 1
        var initialItem: PlayableItem? = null
        try {
            val prefs = application.getSharedPreferences("nts_player_prefs", android.content.Context.MODE_PRIVATE)
            if (prefs.contains("last_played_id")) {
                val lastId = prefs.getInt("last_played_id", 1)
                val isLive = prefs.getBoolean("last_played_is_live", true)
                initialItem = if (isLive) {
                    _liveChannels.value.find { it.id == lastId }
                } else {
                    mixtapes.find { it.id == lastId }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error loading last played item from prefs", e)
        }

        val restoredItem = initialItem ?: _liveChannels.value[0]
        _currentItem.value = restoredItem
        setupMediaPlayer()
        setupMediaSession()
        refreshLiveMetadata()

        // Observe currentItem to keep MediaSession metadata synchronized
        viewModelScope.launch {
            currentItem.collect { item ->
                if (item != null) {
                    updateMediaSessionMetadata(item)
                    val state = _playerState.value
                    if (state == PlayerState.Playing || state == PlayerState.Loading) {
                        updateForegroundService(state)
                    }
                }
            }
        }

        // Observe playerState to keep MediaSession transport/playback state synchronized
        viewModelScope.launch {
            playerState.collect { state ->
                updateMediaSessionState()
                updateForegroundService(state)
            }
        }
        
        // Deterministic Scheduler: queries clock on launch and at trigger boundaries to sleep precisely
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val calendar = java.util.Calendar.getInstance()
                val minute = calendar.get(java.util.Calendar.MINUTE)
                val second = calendar.get(java.util.Calendar.SECOND)
                val millisecond = calendar.get(java.util.Calendar.MILLISECOND)

                val targets = listOf(5, 13, 65, 73, 125, 133) // 65, 73 are next hour's 5, 13. 125, 133 are subsequent hour's 5, 13.
                val nextTargetMin = targets.firstOrNull { it > minute } ?: 65
                val minutesToWait = nextTargetMin - minute

                val delayMs = (minutesToWait * 60 * 1000L) - (second * 1000L) - millisecond
                val finalDelayMs = if (delayMs > 0) delayMs else 1000L

                Log.d(tag, "Scheduler: next target minute is $nextTargetMin (current: $minute). Sleeping for ${finalDelayMs / 1000} seconds.")
                kotlinx.coroutines.delay(finalDelayMs)

                Log.d(tag, "Scheduled refresh triggered via deterministic timer!")
                refreshLiveMetadata()
                
                // Extra small delay to prevent rapid triggers in edge-case clock inaccuracies
                kotlinx.coroutines.delay(2000)
            }
        }

        // Preload mixtape icons on startup to avoid visible loading when scrolling
        viewModelScope.launch(Dispatchers.IO) {
            mixtapes.forEach { mixtape ->
                mixtape.imageUrl?.let { url ->
                    try {
                        val request = ImageRequest.Builder(application)
                            .data(url)
                            .build()
                        application.imageLoader.enqueue(request)
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to preload mixtape icon: ${mixtape.title}", e)
                    }
                }
            }
        }
    }

    private fun setupMediaPlayer() {
        if (mediaPlayer == null) {
            val context = getApplication<Application>()
            val attributionContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.createAttributionContext("audioPlayback")
            } else {
                context
            }
            mediaPlayer = MediaPlayer().apply {
                setWakeMode(attributionContext, android.os.PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setOnPreparedListener {
                    Log.d(tag, "MediaPlayer prepared, starting audio...")
                    isMediaPlayerPrepared = true
                    try {
                        it.start()
                        _playerState.value = PlayerState.Playing
                        applyVolume()
                    } catch (e: Exception) {
                        Log.e(tag, "Error starting audio playback", e)
                        _playerState.value = PlayerState.Error("Failed to start audio.")
                    }
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(tag, "MediaPlayer error: what=$what, extra=$extra")
                    isMediaPlayerPrepared = false
                    _playerState.value = PlayerState.Error("Stream offline or connection failed.")
                    
                    val current = _currentItem.value
                    if (current != null && current.isLive && isUserInitiatedPlayback) {
                        Log.d(tag, "Live stream error. Attempting auto-reconnect in 3 seconds...")
                        viewModelScope.launch(Dispatchers.Main) {
                            kotlinx.coroutines.delay(3000)
                            if (_currentItem.value?.id == current.id && _playerState.value is PlayerState.Error) {
                                startPlayback(current.url)
                            }
                        }
                    }
                    true
                }
                setOnInfoListener { _, what, extra ->
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            Log.d(tag, "MediaPlayer buffering start")
                            _playerState.value = PlayerState.Loading
                            true
                        }
                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                            Log.d(tag, "MediaPlayer buffering end")
                            if (isMediaPlayerPrepared) {
                                _playerState.value = PlayerState.Playing
                            }
                            true
                        }
                        else -> false
                    }
                }
                setOnCompletionListener {
                    Log.d(tag, "MediaPlayer completed playback")
                    isMediaPlayerPrepared = false
                    viewModelScope.launch(Dispatchers.Main) {
                        val current = _currentItem.value
                        if (current != null) {
                            if (current.isLive) {
                                Log.d(tag, "Live stream completed (disconnected). Retrying in 2 seconds...")
                                _playerState.value = PlayerState.Loading
                                kotlinx.coroutines.delay(2000)
                                if (_currentItem.value?.id == current.id && _playerState.value == PlayerState.Loading) {
                                    startPlayback(current.url)
                                }
                            } else {
                                skipToNext()
                            }
                        } else {
                            _playerState.value = PlayerState.Idle
                        }
                    }
                }
            }
        }
    }

    fun selectItem(item: PlayableItem) {
        val wasPlaying = _playerState.value == PlayerState.Playing || _playerState.value == PlayerState.Loading
        _currentItem.value = item
        saveCurrentItem(item)
        
        if (wasPlaying) {
            startPlayback(item.url)
        } else {
            // Just cue it without loading or preparing stream
            _playerState.value = PlayerState.Paused
            isMediaPlayerPrepared = false
            try {
                mediaPlayer?.reset()
            } catch (e: Exception) {
                Log.e(tag, "Error resetting mediaPlayer in selectItem", e)
            }
        }
        
        if (item.isLive) {
            refreshLiveMetadata()
        }
    }

    fun playImmediately(item: PlayableItem) {
        isUserInitiatedPlayback = true
        _currentItem.value = item
        saveCurrentItem(item)
        startPlayback(item.url)
        if (item.isLive) {
            refreshLiveMetadata()
        }
    }

    fun skipToNext() {
        isUserInitiatedPlayback = true
        val current = _currentItem.value ?: return
        if (current.isLive) {
            val channels = _liveChannels.value
            if (channels.isNotEmpty()) {
                val currentIndex = channels.indexOfFirst { it.id == current.id }
                if (currentIndex != -1) {
                    val nextIndex = (currentIndex + 1) % channels.size
                    selectItem(channels[nextIndex])
                }
            }
        } else {
            if (mixtapes.isNotEmpty()) {
                val currentIndex = mixtapes.indexOfFirst { it.id == current.id }
                if (currentIndex != -1) {
                    val nextIndex = (currentIndex + 1) % mixtapes.size
                    selectItem(mixtapes[nextIndex])
                }
            }
        }
    }

    fun skipToPrevious() {
        isUserInitiatedPlayback = true
        val current = _currentItem.value ?: return
        if (current.isLive) {
            val channels = _liveChannels.value
            if (channels.isNotEmpty()) {
                val currentIndex = channels.indexOfFirst { it.id == current.id }
                if (currentIndex != -1) {
                    val prevIndex = if (currentIndex - 1 < 0) channels.size - 1 else currentIndex - 1
                    selectItem(channels[prevIndex])
                }
            }
        } else {
            if (mixtapes.isNotEmpty()) {
                val currentIndex = mixtapes.indexOfFirst { it.id == current.id }
                if (currentIndex != -1) {
                    val prevIndex = if (currentIndex - 1 < 0) mixtapes.size - 1 else currentIndex - 1
                    selectItem(mixtapes[prevIndex])
                }
            }
        }
    }

    private fun preparePlayback(url: String, startImmediately: Boolean) {
        try {
            setupMediaPlayer()
            isMediaPlayerPrepared = false
            mediaPlayer?.run {
                reset()
                
                val context = getApplication<Application>()
                val attributionContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    context.createAttributionContext("audioPlayback")
                } else {
                    context
                }
                setDataSource(attributionContext, Uri.parse(url))
                
                if (startImmediately) {
                    _playerState.value = PlayerState.Loading
                    prepareAsync()
                } else {
                    _playerState.value = PlayerState.Paused
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error preparing playback", e)
            isMediaPlayerPrepared = false
            _playerState.value = PlayerState.Error("Failed to prepare stream.")
        }
    }

    private fun startPlayback(url: String) {
        if (!isUserInitiatedPlayback) {
            Log.d(tag, "Ignoring startPlayback before first user playback interaction.")
            return
        }
        requestAudioFocus()
        preparePlayback(url, startImmediately = true)
    }

    fun togglePlayPause() {
        isUserInitiatedPlayback = true
        val current = _currentItem.value ?: return
        when (_playerState.value) {
            PlayerState.Playing -> {
                if (isMediaPlayerPrepared && mediaPlayer?.isPlaying == true) {
                    try {
                        mediaPlayer?.pause()
                    } catch (e: Exception) {
                        Log.e(tag, "Error pausing mediaPlayer", e)
                    }
                }
                _playerState.value = PlayerState.Paused
                abandonAudioFocus()
            }
            PlayerState.Paused, PlayerState.Idle -> {
                startPlayback(current.url)
                if (current.isLive) {
                    refreshLiveMetadata()
                }
            }
            is PlayerState.Error -> {
                startPlayback(current.url)
                if (current.isLive) {
                    refreshLiveMetadata()
                }
            }
            PlayerState.Loading -> {
                // If loading, stop it
                isMediaPlayerPrepared = false
                try {
                    mediaPlayer?.reset()
                } catch (e: Exception) {
                    Log.e(tag, "Error resetting mediaPlayer", e)
                }
                _playerState.value = PlayerState.Paused
                abandonAudioFocus()
            }
        }
    }

    fun play() {
        isUserInitiatedPlayback = true
        val current = _currentItem.value ?: return
        if (_playerState.value != PlayerState.Playing && _playerState.value != PlayerState.Loading) {
            startPlayback(current.url)
            if (current.isLive) {
                refreshLiveMetadata()
            }
        }
    }

    fun pause() {
        when (_playerState.value) {
            PlayerState.Playing -> {
                if (isMediaPlayerPrepared && mediaPlayer?.isPlaying == true) {
                    try {
                        mediaPlayer?.pause()
                    } catch (e: Exception) {
                        Log.e(tag, "Error pausing mediaPlayer", e)
                    }
                }
                _playerState.value = PlayerState.Paused
                abandonAudioFocus()
            }
            PlayerState.Loading -> {
                isMediaPlayerPrepared = false
                try {
                    mediaPlayer?.reset()
                } catch (e: Exception) {
                    Log.e(tag, "Error resetting mediaPlayer", e)
                }
                _playerState.value = PlayerState.Paused
                abandonAudioFocus()
            }
            else -> {}
        }
    }

    fun setVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        _volume.value = clamped
        if (clamped > 0f) {
            lastNonZeroVolume = clamped
        }
        try {
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val targetIndex = (clamped * maxVolume).toInt()
            audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetIndex, 0)
        } catch (e: Exception) {
            Log.e(tag, "Error setting system volume", e)
        }
        applyVolume()
    }

    fun toggleMute() {
        val current = _volume.value
        if (current > 0f) {
            lastNonZeroVolume = current
            setVolume(0f)
        } else {
            setVolume(lastNonZeroVolume)
        }
    }

    private fun applyVolume() {
        if (!isMediaPlayerPrepared) return
        try {
            // Set MediaPlayer volume to 1.0f as we are adjusting the system STREAM_MUSIC volume directly
            mediaPlayer?.setVolume(1f, 1f)
        } catch (e: Exception) {
            Log.e(tag, "Error setting volume on MediaPlayer", e)
        }
    }

    fun sync() {
        val current = _currentItem.value ?: return
        Log.d(tag, "Syncing live head...")
        startPlayback(current.url)
        if (current.isLive) {
            refreshLiveMetadata()
        }
    }

    fun refreshLiveMetadata(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastMetadataFetchTime < METADATA_THROTTLE_MS)) {
            Log.d(tag, "Throttling live metadata refresh. Last successful fetch was less than ${METADATA_THROTTLE_MS / 1000}s ago.")
            return
        }

        synchronized(this) {
            if (isRefreshingLiveMetadata) {
                Log.d(tag, "Metadata refresh already in progress. Skipping duplicate request.")
                return
            }
            isRefreshingLiveMetadata = true
            _isRefreshing.value = true
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(tag, "Fetching live metadata from NTS API...")
                val url = URL("https://www.nts.live/api/v2/live")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("Accept", "application/json")
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    
                    val jsonResponse = JSONObject(response.toString())
                    val results = jsonResponse.optJSONArray("results")
                    if (results != null) {
                        val updatedChannels = _liveChannels.value.map { channel ->
                            var title = channel.title
                            var subtitle = channel.subtitle
                            var upNext = channel.upNext
                            
                            var found = false
                            for (i in 0 until results.length()) {
                                val result = results.optJSONObject(i) ?: continue
                                val channelName = result.optString("channel_name")
                                if (channelName == channel.id.toString()) {
                                    val nowObj = result.optJSONObject("now")
                                    if (nowObj != null) {
                                        val broadcastTitle = nowObj.optString("broadcast_title")
                                        val embeds = nowObj.optJSONObject("embeds")
                                        val details = embeds?.optJSONObject("details")
                                        
                                        val showName = details?.optString("name")
                                        title = if (!showName.isNullOrEmpty()) showName else if (!broadcastTitle.isNullOrEmpty()) broadcastTitle else "NTS Radio Channel ${channel.id}"
                                        
                                        val showDesc = details?.optString("description")
                                        subtitle = if (!showDesc.isNullOrEmpty()) showDesc else "Live broadcast from NTS."
                                    }
                                    
                                    val nextObj = result.optJSONObject("next")
                                    if (nextObj != null) {
                                        val nextTitle = nextObj.optString("broadcast_title")
                                        if (!nextTitle.isNullOrEmpty()) {
                                            upNext = nextTitle
                                        }
                                    }
                                    found = true
                                    break
                                }
                            }
                            
                            if (found) {
                                channel.copy(
                                    title = title,
                                    subtitle = subtitle,
                                    upNext = upNext
                                )
                            } else {
                                val hasRealTitle = channel.title != "NTS Radio Channel ${channel.id}" && channel.title != "..." && channel.title.isNotEmpty()
                                if (hasRealTitle) {
                                    channel
                                } else {
                                    channel.copy(
                                        title = "...",
                                        subtitle = "Unable to retrieve programme information. Press the play button to try again.",
                                        upNext = ""
                                    )
                                }
                            }
                        }
                        
                        _liveChannels.value = updatedChannels
                        
                        // Also, if the currentItem is a live channel, update it to keep UI / player in sync!
                        val current = _currentItem.value
                        if (current != null && current.isLive) {
                            updatedChannels.find { it.id == current.id }?.let { updatedCurrent ->
                                _currentItem.value = updatedCurrent
                            }
                        }
                        lastMetadataFetchTime = System.currentTimeMillis()
                        Log.d(tag, "Live metadata refreshed successfully!")
                    } else {
                        handleMetadataFetchError()
                    }
                } else {
                    Log.e(tag, "Failed to fetch live metadata, HTTP response code: $responseCode")
                    handleMetadataFetchError()
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.e(tag, "Error fetching live metadata", e)
                handleMetadataFetchError()
            } finally {
                synchronized(this@RadioPlayerViewModel) {
                    isRefreshingLiveMetadata = false
                }
                _isRefreshing.value = false
            }
        }
    }

    private fun handleMetadataFetchError() {
        val updatedChannels = _liveChannels.value.map { channel ->
            val hasRealTitle = channel.title != "NTS Radio Channel ${channel.id}" && channel.title != "..." && channel.title.isNotEmpty()
            if (hasRealTitle) {
                channel
            } else {
                channel.copy(
                    title = "...",
                    subtitle = "Unable to retrieve programme information. Press the play button to try again.",
                    upNext = ""
                )
            }
        }
        _liveChannels.value = updatedChannels
        
        // Keep currentItem in sync if it is a live channel
        val current = _currentItem.value
        if (current != null && current.isLive) {
            updatedChannels.find { it.id == current.id }?.let { updatedCurrent ->
                _currentItem.value = updatedCurrent
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(volumeReceiver)
        } catch (e: Exception) {
            Log.e(tag, "Error unregistering volume receiver", e)
        }
        try {
            getApplication<Application>().unregisterReceiver(stopReceiver)
        } catch (e: Exception) {
            Log.e(tag, "Error unregistering stop receiver", e)
        }
        try {
            getApplication<Application>().unregisterReceiver(noisyReceiver)
        } catch (e: Exception) {
            Log.e(tag, "Error unregistering noisy receiver", e)
        }
        abandonAudioFocus()
        releaseWifiLock()
        val context = getApplication<Application>()
        val intent = Intent(context, RadioPlaybackService::class.java).apply {
            action = RadioPlaybackService.ACTION_STOP
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Log.e(tag, "Failed to stop foreground service on cleared", e)
        }
        isMediaPlayerPrepared = false
        try {
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (e: Exception) {
            Log.e(tag, "Error releasing mediaPlayer", e)
        }
        mediaPlayer = null
        mediaSession?.release()
        mediaSession = null
    }
}
