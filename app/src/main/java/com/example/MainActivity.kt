package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.composed
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VolumeMute
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.Lato
import com.mudita.mmd.components.slider.SliderMMD
import coil.compose.AsyncImage
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import java.lang.ref.WeakReference
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val viewModel: RadioPlayerViewModel by viewModels()

    private val hardCloseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.ACTION_HARD_CLOSE") {
                finishAffinity()
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = WeakReference(this)
        try {
            ContextCompat.registerReceiver(
                this,
                hardCloseReceiver,
                IntentFilter("com.example.ACTION_HARD_CLOSE"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            // Ignore if registration fails
        }
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White)
                ) { innerPadding ->
                    NtsRadioPlayerScreen(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(hardCloseReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
        if (instance?.get() == this) {
            instance = null
        }
        super.onDestroy()
    }

    companion object {
        var instance: WeakReference<MainActivity>? = null
    }
}

@Composable
fun NtsRadioPlayerScreen(
    viewModel: RadioPlayerViewModel,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val vibrator = remember {
        context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    }
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val screenWidthPx = configuration.screenWidthDp * density
    val screenHeightPx = configuration.screenHeightDp * density

    val is480x800 = (screenWidthPx.toInt() == 480 && screenHeightPx.toInt() == 800) ||
                    (screenWidthPx.toInt() == 800 && screenHeightPx.toInt() == 480) ||
                    (configuration.screenWidthDp <= 480 && configuration.screenHeightDp <= 800) ||
                    (configuration.screenWidthDp <= 800 && configuration.screenHeightDp <= 480)

    val itemsPerPage = 4
    val totalPages = (viewModel.mixtapes.size + itemsPerPage - 1) / itemsPerPage

    val currentItem by viewModel.currentItem.collectAsState()
    val playerState by viewModel.playerState.collectAsState()
    val liveChannels by viewModel.liveChannels.collectAsState()

    val initialTabAndPage = remember(currentItem?.id) {
        val current = currentItem
        if (current != null && !current.isLive) {
            val index = viewModel.mixtapes.indexOfFirst { it.id == current.id }
            if (index >= 0) {
                val page = (index / itemsPerPage) + 1
                Pair(1, page)
            } else {
                Pair(0, 1)
            }
        } else {
            Pair(0, 1)
        }
    }

    var selectedTab by remember(currentItem?.id) { mutableStateOf(initialTabAndPage.first) } // 0 = LIVE, 1 = MIXTAPES
    var currentMixtapePage by remember(currentItem?.id) { mutableStateOf(initialTabAndPage.second) }
    var isCardPressed by remember { mutableStateOf(false) }

    var lastSeenItemId by remember { mutableStateOf<Int?>(currentItem?.id) }
    if (currentItem?.id != lastSeenItemId) {
        lastSeenItemId = currentItem?.id
        val item = currentItem
        if (item != null) {
            if (item.isLive) {
                selectedTab = 0
            } else {
                selectedTab = 1
                val index = viewModel.mixtapes.indexOfFirst { it.id == item.id }
                if (index >= 0) {
                    currentMixtapePage = (index / itemsPerPage) + 1
                }
            }
        }
    }

    val pagerState = key(currentItem?.id) {
        rememberPagerState(
            initialPage = remember {
                val current = currentItem
                if (current != null && current.isLive) {
                    liveChannels.indexOfFirst { it.id == current.id }.coerceAtLeast(0)
                } else {
                    0
                }
            },
            pageCount = { liveChannels.size }
        )
    }

    val mixtapePagerState = key(currentItem?.id) {
        rememberPagerState(
            initialPage = (currentMixtapePage - 1).coerceIn(0, totalPages - 1),
            pageCount = { totalPages }
        )
    }

    LaunchedEffect(totalPages) {
        if (currentMixtapePage > totalPages) {
            currentMixtapePage = totalPages.coerceAtLeast(1)
        }
    }

    LaunchedEffect(mixtapePagerState.currentPage) {
        currentMixtapePage = mixtapePagerState.currentPage + 1
    }

    LaunchedEffect(currentMixtapePage) {
        val targetPage = currentMixtapePage - 1
        if (targetPage in 0 until totalPages && mixtapePagerState.currentPage != targetPage) {
            mixtapePagerState.scrollToPage(targetPage)
        }
    }

    // Keep active stream and UI pagers completely synchronized
    LaunchedEffect(currentItem) {
        val item = currentItem ?: return@LaunchedEffect
        if (item.isLive) {
            selectedTab = 0
            val index = liveChannels.indexOfFirst { it.id == item.id }
            if (index in 0 until liveChannels.size && pagerState.currentPage != index) {
                pagerState.scrollToPage(index)
            }
        } else {
            selectedTab = 1
            val index = viewModel.mixtapes.indexOfFirst { it.id == item.id }
            if (index >= 0) {
                val targetPage = index / itemsPerPage
                if (targetPage in 0 until totalPages && mixtapePagerState.currentPage != targetPage) {
                    mixtapePagerState.scrollToPage(targetPage)
                }
            }
        }
    }

    var swipeDragX by remember { mutableStateOf(0f) }
    var swipeHasSwiped by remember { mutableStateOf(false) }
    val tabSwipeModifier = Modifier.pointerInput(selectedTab) {
        detectHorizontalDragGestures(
            onDragStart = {
                swipeDragX = 0f
                swipeHasSwiped = false
            },
            onDragEnd = {},
            onDragCancel = {},
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                if (!swipeHasSwiped) {
                    swipeDragX += dragAmount
                    val threshold = 70f
                    if (swipeDragX > threshold || swipeDragX < -threshold) {
                        selectedTab = if (selectedTab == 0) 1 else 0
                        swipeHasSwiped = true
                    }
                }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        // --- Tab Switcher (Segmented Control) ---
        MuditaTabSwitcher(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it }
        )

        // --- Main Content Container ---
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = if (isCardPressed) 24.dp else 0.dp)
        ) {
            if (selectedTab == 0) {
                // Live broadcast view with swiping HorizontalPager
                var totalDragX by remember { mutableStateOf(0f) }
                var hasSwipedInThisDrag by remember { mutableStateOf(false) }
                val coroutineScope = rememberCoroutineScope()
                var localRefreshing by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = false,
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (isCardPressed) Modifier.fillMaxHeight() else Modifier.height(296.dp)
                            )
                            .pointerInput(pagerState, liveChannels.size) {
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        totalDragX = 0f
                                        hasSwipedInThisDrag = false
                                    },
                                    onDragEnd = {},
                                    onDragCancel = {},
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        if (!hasSwipedInThisDrag) {
                                            totalDragX += dragAmount
                                            val threshold = 100f
                                            if (totalDragX > threshold) {
                                                if (pagerState.currentPage > 0) {
                                                    coroutineScope.launch {
                                                        pagerState.scrollToPage(pagerState.currentPage - 1)
                                                    }
                                                } else {
                                                    coroutineScope.launch {
                                                        pagerState.scrollToPage(liveChannels.size - 1)
                                                    }
                                                }
                                                hasSwipedInThisDrag = true
                                            } else if (totalDragX < -threshold) {
                                                if (pagerState.currentPage < liveChannels.size - 1) {
                                                    coroutineScope.launch {
                                                        pagerState.scrollToPage(pagerState.currentPage + 1)
                                                    }
                                                } else {
                                                    coroutineScope.launch {
                                                        pagerState.scrollToPage(0)
                                                    }
                                                }
                                                hasSwipedInThisDrag = true
                                            }
                                        }
                                    }
                                )
                            }
                            .pointerInput(Unit) {
                                var totalDragY = 0f
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        totalDragY = 0f
                                    },
                                    onDragEnd = {
                                        if (totalDragY > 120f) {
                                            localRefreshing = true
                                            try {
                                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                    vibrator?.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                                                } else {
                                                    @Suppress("DEPRECATION")
                                                    vibrator?.vibrate(100)
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                            viewModel.refreshLiveMetadata(force = true)
                                            coroutineScope.launch {
                                                val startTime = System.currentTimeMillis()
                                                // Ensure the sync icon is displayed for a minimum of 1 second
                                                kotlinx.coroutines.delay(1000)
                                                // Continue displaying up to a maximum of 3 seconds if the viewModel is still refreshing
                                                while (System.currentTimeMillis() - startTime < 3000 && viewModel.isRefreshing.value) {
                                                    kotlinx.coroutines.delay(100)
                                                }
                                                localRefreshing = false
                                            }
                                        }
                                        totalDragY = 0f
                                    },
                                    onDragCancel = {
                                        totalDragY = 0f
                                    },
                                    onVerticalDrag = { change, dragAmount ->
                                        if (dragAmount > 0 || totalDragY > 0) {
                                            change.consume()
                                            totalDragY += dragAmount
                                        }
                                    }
                                )
                            }
                    ) { page ->
                        val channel = liveChannels[page]
                        val isSelected = currentItem?.id == channel.id
                        val isPlaying = isSelected && playerState == PlayerState.Playing
                        val isLoading = isSelected && playerState == PlayerState.Loading

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (isCardPressed) Modifier.fillMaxHeight() else Modifier.wrapContentHeight()
                                ),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            LiveChannelCard(
                                channel = channel,
                                isSelected = isSelected,
                                isPlaying = isPlaying,
                                isLoading = isLoading,
                                isPressed = isCardPressed,
                                onPressedChanged = { isCardPressed = it },
                                onClick = {
                                    if (channel.title == "..." || 
                                        channel.title.startsWith("NTS Radio Channel") || 
                                        channel.subtitle.contains("Unable to retrieve")
                                    ) {
                                        viewModel.refreshLiveMetadata()
                                    }
                                    if (isSelected) {
                                        viewModel.togglePlayPause()
                                    } else {
                                        viewModel.playImmediately(channel)
                                    }
                                }
                            )
                        }
                    }

                    if (!isCardPressed) {
                        // Column for remaining space below Pager to ensure perfect equidistant positioning of dots
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .then(tabSwipeModifier),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Top
                        ) {
                            Spacer(modifier = Modifier.height(2.dp))

                            // 1. Upper Half: pagination dots
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                // Two pagination dots
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.offset(y = (-0.5).dp)
                                ) {
                                    repeat(liveChannels.size) { index ->
                                        val active = pagerState.currentPage == index
                                        if (active) {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .background(Color.Black, CircleShape)
                                            )
                                        } else {
                                            Box(
                                                modifier = Modifier
                                                    .size(10.dp)
                                                    .background(Color.White, CircleShape)
                                                    .border(BorderStroke(1.2.dp, Color.Black), CircleShape)
                                            )
                                        }
                                    }
                                }
                            }

                            // 2. Lower Half: sync refreshing icon
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (localRefreshing) {
                                    Icon(
                                        imageVector = Icons.Default.Sync,
                                        contentDescription = "Refreshing",
                                        tint = Color.Black,
                                        modifier = Modifier
                                            .size(32.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Infinite Mixtapes paginated view
                var totalMixtapeDragX by remember { mutableStateOf(0f) }
                var hasMixtapeSwipedInThisDrag by remember { mutableStateOf(false) }
                val coroutineScope = rememberCoroutineScope()

                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Top
                ) {
                    HorizontalPager(
                        state = mixtapePagerState,
                        userScrollEnabled = false,
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(296.dp)
                            .pointerInput(mixtapePagerState, totalPages) {
                                detectHorizontalDragGestures(
                                    onDragStart = {
                                        totalMixtapeDragX = 0f
                                        hasMixtapeSwipedInThisDrag = false
                                    },
                                    onDragEnd = {},
                                    onDragCancel = {},
                                    onHorizontalDrag = { change, dragAmount ->
                                        change.consume()
                                        if (!hasMixtapeSwipedInThisDrag) {
                                            totalMixtapeDragX += dragAmount
                                            val threshold = 100f
                                            if (totalMixtapeDragX > threshold) {
                                                if (mixtapePagerState.currentPage > 0) {
                                                    coroutineScope.launch {
                                                        mixtapePagerState.scrollToPage(mixtapePagerState.currentPage - 1)
                                                    }
                                                } else {
                                                    coroutineScope.launch {
                                                        mixtapePagerState.scrollToPage(totalPages - 1)
                                                    }
                                                }
                                                hasMixtapeSwipedInThisDrag = true
                                            } else if (totalMixtapeDragX < -threshold) {
                                                if (mixtapePagerState.currentPage < totalPages - 1) {
                                                    coroutineScope.launch {
                                                        mixtapePagerState.scrollToPage(mixtapePagerState.currentPage + 1)
                                                    }
                                                } else {
                                                    coroutineScope.launch {
                                                        mixtapePagerState.scrollToPage(0)
                                                    }
                                                }
                                                hasMixtapeSwipedInThisDrag = true
                                            }
                                        }
                                    }
                                )
                            }
                    ) { pageIndex ->
                        val startIndex = pageIndex * itemsPerPage
                        val endIndex = minOf(startIndex + itemsPerPage, viewModel.mixtapes.size)
                        val pageItems = viewModel.mixtapes.subList(startIndex, endIndex)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pageItems.forEach { mixtape ->
                                val isSelected = currentItem?.id == mixtape.id
                                val isPlaying = isSelected && playerState == PlayerState.Playing
                                val isLoading = isSelected && playerState == PlayerState.Loading

                                MixtapeListItem(
                                    mixtape = mixtape,
                                    isSelected = isSelected,
                                    isPlaying = isPlaying,
                                    isLoading = isLoading,
                                    onClick = {
                                        if (isSelected) {
                                            viewModel.togglePlayPause()
                                        } else {
                                            viewModel.playImmediately(mixtape)
                                        }
                                    }
                                )
                            }
                        }
                    }

                    // Column for remaining space below Pager to ensure perfect equidistant positioning of dots
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .then(tabSwipeModifier),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top
                    ) {
                        Spacer(modifier = Modifier.height(2.dp))

                        // 1. Upper Half: pagination dots
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            // Dynamic pagination dots
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.offset(y = (-0.5).dp)
                            ) {
                                repeat(totalPages) { index ->
                                    val active = mixtapePagerState.currentPage == index
                                    if (active) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(Color.Black, CircleShape)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .background(Color.White, CircleShape)
                                                .border(BorderStroke(1.2.dp, Color.Black), CircleShape)
                                        )
                                    }
                                }
                            }
                        }

                        // 2. Lower Half: empty space matching layout symmetry
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            // Empty space matching layout symmetry
                        }
                    }
                }
            }
        }

        if (!isCardPressed) {
            // --- Persistent Bottom Player ---
            PersistentBottomPlayer(
                viewModel = viewModel,
                currentItem = currentItem,
                playerState = playerState,
                swipeModifier = tabSwipeModifier
            )

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
fun MuditaStatusBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "10:45",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = Lato,
            letterSpacing = 0.5.sp,
            color = Color.Black
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Signal strength graphic
            Canvas(modifier = Modifier.size(14.dp, 10.dp)) {
                val barW = 2.dp.toPx()
                val gap = 1.dp.toPx()
                for (i in 0..3) {
                    val barH = (2 + i * 2.5).dp.toPx()
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(i * (barW + gap), size.height - barH),
                        size = Size(barW, barH)
                    )
                }
            }
            // Battery icon
            Canvas(modifier = Modifier.size(18.dp, 10.dp)) {
                val strokeW = 1.dp.toPx()
                drawRoundRect(
                    color = Color.Black,
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width - 2.dp.toPx(), size.height),
                    cornerRadius = CornerRadius(1.5.dp.toPx()),
                    style = Stroke(strokeW)
                )
                // Battery tip
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(size.width - 2.dp.toPx(), size.height * 0.3f),
                    size = Size(1.5.dp.toPx(), size.height * 0.4f)
                )
                // Inside charge level
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                    size = Size(size.width - 6.dp.toPx(), size.height - 4.dp.toPx())
                )
            }
        }
    }
}

@Composable
fun MuditaTabSwitcher(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(BorderStroke(2.dp, Color.Black), RoundedCornerShape(50.dp))
                .background(Color.White)
                .padding(2.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50.dp))
                    .background(if (selectedTab == 0) Color.Black else Color.White)
                    .noRippleClickable { onTabSelected(0) }
                    .padding(vertical = 8.dp)
                    .testTag("tab_live_broadcasts")
            ) {
                Text(
                    text = "LIVE",
                    color = if (selectedTab == 0) Color.White else Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Lato,
                    letterSpacing = 1.sp
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50.dp))
                    .background(if (selectedTab == 1) Color.Black else Color.White)
                    .noRippleClickable { onTabSelected(1) }
                    .padding(vertical = 8.dp)
                    .testTag("tab_infinite_mixtapes")
            ) {
                Text(
                    text = "MIXTAPES",
                    color = if (selectedTab == 1) Color.White else Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Lato,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun LiveChannelCard(
    channel: PlayableItem,
    isSelected: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    isPressed: Boolean = false,
    onPressedChanged: (Boolean) -> Unit = {},
    onClick: () -> Unit
) {
    val cardBgColor = Color.White
    val cardContentColor = Color.Black
    val currentOnClick by rememberUpdatedState(onClick)
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val vibrator = remember(context) {
        context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
    }
    var displayedText by remember(channel.subtitle) { mutableStateOf(channel.subtitle) }
    var isTextTruncated by remember(channel.subtitle) { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBgColor,
            contentColor = cardContentColor
        ),
        border = BorderStroke(2.dp, Color.Black),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isPressed) Modifier.fillMaxHeight() else Modifier.height(296.dp)
            )
            .pointerInput(isTextTruncated) {
                detectTapGestures(
                    onDoubleTap = { currentOnClick() },
                    onPress = {
                        if (isTextTruncated) {
                            val pressJob = coroutineScope.launch {
                                delay(150)
                                onPressedChanged(true)
                                vibrator?.let { v ->
                                    if (v.hasVibrator()) {
                                        try {
                                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                                v.vibrate(
                                                    android.os.VibrationEffect.createOneShot(
                                                        50,
                                                        android.os.VibrationEffect.DEFAULT_AMPLITUDE
                                                    )
                                                )
                                            } else {
                                                @Suppress("DEPRECATION")
                                                v.vibrate(50)
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("Vibration", "Failed to vibrate", e)
                                        }
                                    }
                                }
                            }
                            try {
                                awaitRelease()
                            } finally {
                                pressJob.cancel()
                                onPressedChanged(false)
                            }
                        }
                    }
                )
            }
            .testTag("live_channel_card_${channel.id}")
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Top Row: EXACTLY same layout, structure and dimensions as MixtapeListItem's Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left side details (pushed by play button to occupy full remaining width)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp, end = 10.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "CHANNEL ${channel.id} • LIVE",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = Lato,
                            letterSpacing = 1.5.sp,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = channel.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = Lato,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Right side play button Box: EXACTLY matching MixtapeListItem down to the pixel
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(48.dp)
                            .noRippleClickable { onClick() }
                            .testTag("live_channel_play_pause_button_${channel.id}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .offset(x = (-6).dp)
                                .size(26.dp)
                                .border(BorderStroke(1.2.dp, cardContentColor), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoading) {
                               Icon(
                                   imageVector = Icons.Default.Sync,
                                   contentDescription = "Loading",
                                   tint = cardContentColor,
                                   modifier = Modifier.size(14.dp)
                               )
                            } else if (isPlaying) {
                                // Small Stop symbol (square)
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .background(cardContentColor)
                                )
                            } else {
                                // Small Play symbol
                                Canvas(modifier = Modifier.size(9.dp)) {
                                    val path = Path().apply {
                                        moveTo(1.5.dp.toPx(), 0f)
                                        lineTo(1.5.dp.toPx(), size.height)
                                        lineTo(size.width, size.height / 2f)
                                        close()
                                    }
                                    drawPath(path = path, color = cardContentColor)
                                }
                            }
                        }
                    }
                }
                if (!isPressed) {
                    // Subtitle Description and Footer below the top 68.dp Row
                    // 1. Body Area: height 161.dp, with horizontal padding of 20.dp
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(161.dp)
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.TopStart
                    ) {
                        Text(
                            text = displayedText,
                            fontSize = 13.sp,
                            color = Color.Black,
                            fontFamily = Lato,
                            lineHeight = 17.sp,
                            maxLines = 8,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(152.dp),
                            onTextLayout = { textLayoutResult ->
                                val maxHeight = textLayoutResult.size.height
                                if (maxHeight >= 100 && !isPressed && displayedText == channel.subtitle) {
                                    var lastVisibleLine = -1
                                    for (i in 0 until textLayoutResult.lineCount) {
                                        if (textLayoutResult.getLineBottom(i) <= maxHeight + 2f) {
                                            lastVisibleLine = i
                                        } else {
                                            break
                                        }
                                    }
                                    if (lastVisibleLine < 0) {
                                        lastVisibleLine = 0
                                    }

                                    val lastCharIndex = textLayoutResult.getLineEnd(lastVisibleLine).coerceAtMost(channel.subtitle.length)
                                    val hasOverflow = channel.subtitle.substring(lastCharIndex).trim().isNotEmpty()
                                    if (hasOverflow) {
                                        isTextTruncated = true
                                        val lineStart = textLayoutResult.getLineStart(lastVisibleLine)
                                        val lineLength = lastCharIndex - lineStart
                                        val lineRight = textLayoutResult.getLineRight(lastVisibleLine)
                                        val lineLeft = textLayoutResult.getLineLeft(lastVisibleLine)
                                        
                                        val avgCharWidth = if (lineLength > 0) {
                                            (lineRight - lineLeft) / lineLength
                                        } else {
                                            10f
                                        }
                                        // " HOLD TO EXPAND" is 15 characters.
                                        // Since they are uppercase and wider, we use 27 average characters for a safe and accurate fit.
                                        val targetWidth = 27f * avgCharWidth
                                        var cutIndex = lastCharIndex
                                        while (cutIndex > lineStart) {
                                            val charPos = textLayoutResult.getHorizontalPosition(cutIndex, true)
                                            val remainingWidth = textLayoutResult.size.width.toFloat() - charPos
                                            if (remainingWidth >= targetWidth) {
                                                break
                                            }
                                            cutIndex--
                                        }
                                        val baseText = channel.subtitle.substring(0, cutIndex).trimEnd { it.isWhitespace() || it in ".,!?;:-" }
                                        displayedText = "$baseText HOLD\u00A0TO\u00A0EXPAND"
                                    } else {
                                        isTextTruncated = false
                                        displayedText = channel.subtitle
                                    }
                                }
                            }
                        )
                    }

                    // 2. Solid line divider of same thickness as outer card border (2.dp)
                    HorizontalDivider(
                        thickness = 2.dp,
                        color = Color.Black,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                    )

                    // 3. Bottom Area: height 65.dp
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(65.dp)
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Footer (NEXT)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "NEXT:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = Lato,
                                letterSpacing = 1.sp,
                                color = Color.Black
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(
                                text = (channel.upNext ?: "").uppercase(),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                fontFamily = Lato,
                                color = Color.Black,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    // When pressed/held, display full content in the remaining space of the card box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                        contentAlignment = Alignment.TopStart
                    ) {
                        Text(
                            text = channel.subtitle,
                            fontSize = 13.sp,
                            color = Color.Black,
                            fontFamily = Lato,
                            lineHeight = 17.sp,
                            maxLines = Int.MAX_VALUE,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MixtapeListItem(
    mixtape: PlayableItem,
    isSelected: Boolean,
    isPlaying: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val cardBg = Color.White
    val cardText = Color.Black
    val borderStroke = BorderStroke(2.dp, Color.Black)
    val currentOnClick by rememberUpdatedState(onClick)

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = cardBg,
            contentColor = cardText
        ),
        border = borderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { currentOnClick() }
                )
            }
            .testTag("mixtape_card_${mixtape.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Custom drawn e-ink vector icon
            MixtapeIcon(
                mixtape = mixtape,
                isHighlighted = isSelected,
                isPlaying = isPlaying,
                isBuffering = isLoading,
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Mixtape details
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = mixtape.title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = Lato,
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = mixtape.subtitle,
                    fontSize = 11.sp,
                    fontFamily = Lato,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.Black
                )
            }

            // Clean circular action trigger wrapped in 36.dp touch target to prevent accidental scrolling clicks
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(48.dp)
                    .noRippleClickable { onClick() }
                    .testTag("mixtape_play_button_${mixtape.id}"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .offset(x = (-6).dp)
                        .size(26.dp)
                        .border(BorderStroke(1.2.dp, cardText), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isLoading) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Loading",
                            tint = cardText,
                            modifier = Modifier.size(14.dp)
                        )
                    } else if (isPlaying) {
                        // Small Stop symbol (square)
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .background(cardText)
                        )
                    } else {
                        // Small Play symbol
                        Canvas(modifier = Modifier.size(9.dp)) {
                            val path = Path().apply {
                                moveTo(1.5.dp.toPx(), 0f)
                                lineTo(1.5.dp.toPx(), size.height)
                                lineTo(size.width, size.height / 2f)
                                close()
                            }
                            drawPath(path = path, color = cardText)
                        }
                    }
                }
            }
        }
    }
}



@kotlin.OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PersistentBottomPlayer(
    viewModel: RadioPlayerViewModel,
    currentItem: PlayableItem?,
    playerState: PlayerState,
    swipeModifier: Modifier = Modifier
) {
    val volume by viewModel.volume.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 44.dp, top = 0.dp, end = 44.dp, bottom = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 0.dp, end = 0.dp, top = 0.dp, bottom = 10.dp)
        ) {
            // Stream title and Play/Pause Control row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(swipeModifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    val displayTitle = currentItem?.title ?: "No stream cued"
                    val subtitleSuffix = if (playerState == PlayerState.Loading) " (BUFFERING...)" else ""
                    
                    if (currentItem?.isLive == true) {
                        // Live broadcast: only show/mixtape title text going up to two lines
                        Text(
                            text = displayTitle + subtitleSuffix,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = Lato,
                            color = Color.Black,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 20.sp
                        )
                    } else {
                        // Infinite mixtape:
                        // First line says 'Infinite Mixtape'
                        Text(
                            text = "Infinite Mixtape",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = Lato,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        // Second line says the name of the mixtape
                        Text(
                            text = displayTitle + subtitleSuffix,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = Lato,
                            color = Color.Black,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 20.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Play/Pause Action Sphere
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(Color.White, CircleShape)
                        .border(BorderStroke(1.2.dp, Color.Black), CircleShape)
                        .clip(CircleShape)
                        .noRippleClickable { viewModel.togglePlayPause() }
                        .testTag("bottom_player_play_pause"),
                    contentAlignment = Alignment.Center
                ) {
                    if (playerState == PlayerState.Playing) {
                        // Small Stop symbol (square)
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(Color.Black)
                        )
                    } else if (playerState == PlayerState.Loading) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Syncing",
                            tint = Color.Black,
                            modifier = Modifier
                                .size(16.dp)
                        )
                    } else {
                        Canvas(modifier = Modifier.size(10.dp)) {
                            val path = Path().apply {
                                moveTo(2.dp.toPx(), 0f)
                                lineTo(2.dp.toPx(), size.height)
                                lineTo(size.width, size.height / 2f)
                                close()
                            }
                            drawPath(path = path, color = Color.Black)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Volume controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (volume > 0f) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeMute,
                    contentDescription = "Volume",
                    tint = Color.Black,
                    modifier = Modifier
                        .size(20.dp)
                        .noRippleClickable {
                            viewModel.toggleMute()
                        }
                )

                Spacer(modifier = Modifier.width(6.dp))

                SliderMMD(
                    value = volume,
                    onValueChange = { viewModel.setVolume(it) },
                    modifier = Modifier
                        .weight(1f)
                        .layout { measurable, constraints ->
                            val extraWidth = 10.dp.roundToPx()
                            val placeable = measurable.measure(
                                constraints.copy(maxWidth = constraints.maxWidth + extraWidth)
                            )
                            layout(placeable.width - extraWidth, placeable.height) {
                                placeable.place(0, 0)
                            }
                        }
                        .testTag("volume_slider")
                )
            }
        }
    }
}

@Composable
fun MuditaBottomNavigation(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Column {
        Divider(color = Color.Black.copy(alpha = 0.1f), modifier = Modifier.height(1.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(top = 10.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavItem(
                icon = Icons.Rounded.PlayArrow,
                label = "Live",
                isSelected = selectedTab == 0,
                onClick = { onTabSelected(0) }
            )
            BottomNavItem(
                icon = Icons.Default.Sync,
                label = "Mixtapes",
                isSelected = selectedTab == 1,
                onClick = { onTabSelected(1) }
            )
            BottomNavItem(
                icon = Icons.Rounded.VolumeUp,
                label = "Mute",
                isSelected = false,
                onClick = { /* Simulated secondary function */ }
            )
        }
    }
}

@Composable
fun BottomNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val opacity = if (isSelected) 1.0f else 0.4f
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .noRippleClickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.Black.copy(alpha = opacity),
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label.uppercase(),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = Lato,
            letterSpacing = 0.5.sp,
            color = Color.Black
        )
    }
}

@Composable
fun DottedLineDivider(
    color: Color = Color.Black,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
    ) {
        val pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
            floatArrayOf(4f, 4f),
            0f
        )
        drawLine(
            color = color,
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            pathEffect = pathEffect,
            strokeWidth = 1.dp.toPx()
        )
    }
}

@Composable
fun MixtapeIcon(
    mixtape: PlayableItem,
    isHighlighted: Boolean,
    isPlaying: Boolean = false,
    isBuffering: Boolean = false,
    modifier: Modifier = Modifier
) {
    val id = mixtape.id
    val isIconActive = isPlaying || isBuffering
    val color = when (id) {
        7 -> Color.Black
        else -> if (isIconActive) Color.White else Color.Black
    }
    val bgColor = when (id) {
        7 -> Color.White
        else -> if (isIconActive) Color.Black else Color.White
    }

    val invertColorFilter = if (isPlaying || isBuffering) {
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -1f,  0f,  0f,  0f, 255f,
                     0f, -1f,  0f,  0f, 255f,
                     0f,  0f, -1f,  0f, 255f,
                     0f,  0f,  0f,  1f,   0f
                )
            )
        )
    } else {
        null
    }

    if (mixtape.imageUrl != null) {
        Box(
            modifier = modifier
                .background(bgColor, RoundedCornerShape(8.dp))
                .border(1.2.dp, Color.Black, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = mixtape.imageUrl,
                contentDescription = mixtape.title,
                colorFilter = invertColorFilter,
                modifier = Modifier.fillMaxSize()
            )
        }
    } else {
        val fallbackImageRes = when (id) {
            3 -> R.drawable.img_poolside
            4 -> R.drawable.img_slow_focus
            5 -> R.drawable.img_low_key
            6 -> R.drawable.img_memory_lane
            7 -> R.drawable.img_four_to_the_floor
            8 -> R.drawable.img_island_time
            9 -> R.drawable.img_the_tube
            10 -> R.drawable.img_sheet_music
            11 -> R.drawable.img_feelings
            12 -> R.drawable.img_expansions
            13 -> R.drawable.img_rap_house
            14 -> R.drawable.img_labyrinth
            15 -> R.drawable.img_sweat
            16 -> R.drawable.img_otaku
            17 -> R.drawable.img_the_pit
            18 -> R.drawable.img_field_recordings
            else -> null
        }

        if (fallbackImageRes != null) {
            Box(
                modifier = modifier
                    .background(bgColor, RoundedCornerShape(8.dp))
                    .border(1.2.dp, Color.Black, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = fallbackImageRes),
                    contentDescription = mixtape.title,
                    colorFilter = invertColorFilter,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Box(
                modifier = modifier
                    .background(bgColor, RoundedCornerShape(8.dp))
                    .border(1.2.dp, Color.Black, RoundedCornerShape(8.dp))
            )
        }
    }
}

fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier = composed {
    this.clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick
    )
}

