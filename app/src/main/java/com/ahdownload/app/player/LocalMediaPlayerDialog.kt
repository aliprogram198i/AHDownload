package com.ahdownload.app.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.view.Window
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.LocalMediaPlaybackType
import com.ahdownload.domain.download.isLocalMediaUri
import com.ahdownload.domain.download.resolveLocalMediaPlaybackType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun LocalMediaPlayerDialog(
    record: DownloadRecord,
    playbackType: LocalMediaPlaybackType,
    queueRecords: List<DownloadRecord> = listOf(record),
    onDismiss: () -> Unit,
    onPlaybackError: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val currentErrorCallback by rememberUpdatedState(onPlaybackError)
    val playbackPositionStore = remember(context) { PlaybackPositionStore(context) }
    val queueOrderStore = remember(context) { PlaybackQueueStore(context) }

    val initialQueueCandidates = remember(record, queueRecords) {
        val supplied = queueRecords.map { if (it.task.id == record.task.id) record else it }
        val withSelected = if (supplied.any { it.task.id == record.task.id }) supplied else supplied + record
        val resolved = withSelected.distinctBy { it.task.id }.mapNotNull { candidate ->
            val uri = resolveLocalMediaUri(candidate) ?: return@mapNotNull null
            candidate.copy(destinationUri = uri.toString())
        }
        if (resolved.any { it.task.id == record.task.id }) resolved
        else resolveLocalMediaUri(record)?.let { listOf(record.copy(destinationUri = it.toString())) } ?: emptyList()
    }
    val initialQueue = remember(initialQueueCandidates, queueOrderStore) {
        PlayerEnhancementPolicy.restoreQueueOrder(initialQueueCandidates, queueOrderStore.readTaskIds())
    }
    val initialQueueIndex = initialQueue.indexOfFirst { it.task.id == record.task.id }.coerceAtLeast(0)
    val initialPositionMs = remember(initialQueue, initialQueueIndex, playbackPositionStore) {
        val taskId = initialQueue.getOrNull(initialQueueIndex)?.task?.id ?: record.task.id
        restorablePlaybackPosition(playbackPositionStore.read(taskId))
    }

    if (initialQueue.isEmpty()) {
        UnavailableMediaDialog(onDismiss)
        return
    }
    val initialRecord = initialQueue[initialQueueIndex.coerceIn(0, initialQueue.lastIndex)]
    if (resolveLocalMediaUri(initialRecord) == null) {
        UnavailableMediaDialog(onDismiss)
        return
    }

    val initialType = mediaTypeFor(initialRecord, playbackType)
    val mediaItems = remember(initialQueue, context) {
        initialQueue.mapNotNull { entry ->
            val uri = resolveLocalMediaUri(entry) ?: return@mapNotNull null
            val itemType = mediaTypeFor(entry, initialType)
            MediaItem.Builder()
                .setMediaId(entry.task.id)
                .setUri(uri)
                .apply { resolveMediaMimeType(context, entry, uri, itemType)?.let(::setMimeType) }
                .setMediaMetadata(MediaMetadata.Builder().setTitle(displayTitle(entry)).build())
                .build()
        }
    }
    val startIndex = initialQueueIndex.coerceIn(0, mediaItems.lastIndex.coerceAtLeast(0))
    val player = remember(initialQueue, mediaItems) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(if (initialType == LocalMediaPlaybackType.AUDIO) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            setMediaItems(mediaItems, startIndex, initialPositionMs)
            prepare()
            playWhenReady = true
        }
    }

    var activeQueue by remember(player) { mutableStateOf(initialQueue) }
    var currentQueueIndex by remember(player) { mutableIntStateOf(startIndex) }
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var isBuffering by remember(player) { mutableStateOf(player.playbackState == Player.STATE_BUFFERING) }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var positionMs by remember(player) { mutableLongStateOf(initialPositionMs) }
    var isScrubbing by remember(player) { mutableStateOf(false) }
    var scrubPosition by remember(player) { mutableFloatStateOf(initialPositionMs.toFloat()) }
    var hasPlaybackError by remember(player) { mutableStateOf(false) }
    var showSpeedDialog by remember(player) { mutableStateOf(false) }
    var showSleepTimerDialog by remember(player) { mutableStateOf(false) }
    var showQueueDialog by remember(player) { mutableStateOf(false) }
    var playbackSpeed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    var repeatMode by remember(player) { mutableIntStateOf(player.repeatMode) }
    var shuffleEnabled by remember(player) { mutableStateOf(player.shuffleModeEnabled) }
    var sleepTimerDeadlineElapsedMs by remember(player) { mutableLongStateOf(0L) }
    var sleepTimerRemainingSeconds by remember(player) { mutableLongStateOf(0L) }
    var isFullscreen by remember(player) { mutableStateOf(false) }
    var isMiniPlayer by remember(player) { mutableStateOf(false) }
    var controlsVisible by remember(player) { mutableStateOf(true) }
    var touchLocked by remember(player) { mutableStateOf(false) }
    var gestureFeedback by remember(player) { mutableStateOf<String?>(null) }
    var aspectMode by remember(player) { mutableStateOf(PlayerAspectMode.FIT) }
    var videoAspectRatio by remember(player) { mutableFloatStateOf(16f / 9f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentRecord = activeQueue.getOrNull(currentQueueIndex) ?: record
    val currentTaskId by rememberUpdatedState(currentRecord.task.id)
    val currentType = remember(currentRecord, playbackType) { mediaTypeFor(currentRecord, playbackType) }
    val currentUri = remember(currentRecord) { resolveLocalMediaUri(currentRecord) }
    val currentTitle = remember(currentRecord) { displayTitle(currentRecord) }
    var mediaMetadata by remember(currentRecord.task.id) { mutableStateOf<LocalArtworkMetadata?>(null) }

    LaunchedEffect(currentRecord.task.id, currentUri) {
        mediaMetadata = currentUri?.let { uri ->
            withContext(Dispatchers.IO) { readLocalArtworkMetadata(context, uri) }
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED && player.duration > 0L) positionMs = player.duration
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = player.currentMediaItemIndex
                if (index in activeQueue.indices) {
                    currentQueueIndex = index
                    if (mediaTypeFor(activeQueue[index], playbackType) == LocalMediaPlaybackType.AUDIO && isFullscreen) {
                        isFullscreen = false
                    }
                }
                hasPlaybackError = false
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    if (ratio.isFinite() && ratio in 0.25f..4f) videoAspectRatio = ratio
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                hasPlaybackError = true
                isBuffering = false
                currentErrorCallback(error)
            }
        }
        player.addListener(listener)
        onDispose {
            persistPlaybackPosition(playbackPositionStore, currentTaskId, player)
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                persistPlaybackPosition(playbackPositionStore, currentTaskId, player)
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player) {
        while (true) {
            val duration = player.duration
            durationMs = if (duration > 0L && duration != C.TIME_UNSET) duration else 0L
            if (!isScrubbing) positionMs = player.currentPosition.coerceAtLeast(0L)
            delay(250L)
        }
    }
    LaunchedEffect(player, currentTaskId) {
        while (true) {
            delay(1_000L)
            persistPlaybackPosition(playbackPositionStore, currentTaskId, player)
        }
    }
    LaunchedEffect(player, sleepTimerDeadlineElapsedMs) {
        val deadline = sleepTimerDeadlineElapsedMs
        if (deadline <= 0L) {
            sleepTimerRemainingSeconds = 0L
            return@LaunchedEffect
        }
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val remainingMs = (deadline - now).coerceAtLeast(0L)
            sleepTimerRemainingSeconds = PlaybackTimerPolicy.remainingSeconds(deadline, now)
            if (remainingMs == 0L) {
                player.pause()
                sleepTimerDeadlineElapsedMs = 0L
                sleepTimerRemainingSeconds = 0L
                break
            }
            delay(minOf(1_000L, remainingMs.coerceAtLeast(1L)))
        }
    }
    LaunchedEffect(isFullscreen, controlsVisible, touchLocked) {
        if (isFullscreen && controlsVisible && !touchLocked) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(gestureFeedback) {
        if (gestureFeedback != null) {
            delay(GESTURE_FEEDBACK_DURATION_MS)
            gestureFeedback = null
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        if (from !in activeQueue.indices || to !in activeQueue.indices || from == to) return
        val reordered = PlayerEnhancementPolicy.moveItem(activeQueue, from, to)
        player.moveMediaItem(from, to)
        activeQueue = reordered
        queueOrderStore.writeTaskIds(reordered.map { it.task.id })
        currentQueueIndex = player.currentMediaItemIndex.coerceIn(0, reordered.lastIndex)
    }
    fun selectQueueItem(index: Int) {
        if (index !in activeQueue.indices) return
        currentQueueIndex = index
        player.seekTo(index, 0L)
        player.play()
        showQueueDialog = false
    }

    val canSkipPrevious = activeQueue.size > 1 &&
        (currentQueueIndex > 0 || shuffleEnabled || repeatMode == Player.REPEAT_MODE_ALL)
    val canSkipNext = activeQueue.size > 1 &&
        (currentQueueIndex < activeQueue.lastIndex || shuffleEnabled || repeatMode == Player.REPEAT_MODE_ALL)
    val maxSlider = durationMs.coerceAtLeast(1L).toFloat()
    val sliderPosition = if (isScrubbing) scrubPosition else positionMs.toFloat()
    val containerRatio = PlayerEnhancementPolicy.aspectRatio(aspectMode, videoAspectRatio)
    val resizeMode = when (aspectMode) {
        PlayerAspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        PlayerAspectMode.ZOOM, PlayerAspectMode.CLASSIC_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    if (isMiniPlayer) {
        MiniPlayerBar(
            title = currentTitle,
            subtitle = mediaMetadata?.artist?.takeIf { it.isNotBlank() } ?: if (currentType == LocalMediaPlaybackType.AUDIO) "ملف صوتي محلي" else "فيديو محلي",
            isPlaying = isPlaying,
            onExpand = { isMiniPlayer = false },
            onTogglePlay = {
                if (player.isPlaying) player.pause() else player.play()
            },
            onClose = onDismiss,
        )
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                decorFitsSystemWindows = !isFullscreen,
            ),
        ) {
            val dialogView = LocalView.current
            val dialogWindow = (dialogView.parent as? DialogWindowProvider)?.window
            ImmersiveWindowBarsEffect(isFullscreen, dialogWindow, dialogView)
            Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (isFullscreen) Color.Black else MaterialTheme.colorScheme.background,
        ) {
            if (isFullscreen) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    if (currentType == LocalMediaPlaybackType.VIDEO) {
                        VideoPlayerSurface(
                            player = player,
                            resizeMode = resizeMode,
                            modifier = Modifier.fillMaxSize()
                                .pointerInput(isFullscreen, touchLocked, currentQueueIndex) {
                                    detectTapGestures(
                                        onTap = { if (!touchLocked) controlsVisible = !controlsVisible },
                                        onDoubleTap = { offset ->
                                            if (!touchLocked) {
                                                when {
                                                    offset.x < size.width * 0.35f -> {
                                                        player.seekTo(PlayerEnhancementPolicy.seekTarget(player.currentPosition, player.duration, false))
                                                        gestureFeedback = "رجوع 10 ثوانٍ"
                                                    }
                                                    offset.x > size.width * 0.65f -> {
                                                        player.seekTo(PlayerEnhancementPolicy.seekTarget(player.currentPosition, player.duration, true))
                                                        gestureFeedback = "تقديم 10 ثوانٍ"
                                                    }
                                                    else -> if (player.isPlaying) player.pause() else player.play()
                                                }
                                                controlsVisible = true
                                            }
                                        },
                                    )
                                }
                                .pointerInput(isFullscreen, touchLocked, dialogWindow) {
                                    var accumulatedDrag = 0f
                                    detectVerticalDragGestures { change, dragAmount ->
                                        if (touchLocked) return@detectVerticalDragGestures
                                        val heightPx = size.height.coerceAtLeast(1)
                                        if (change.position.x < size.width / 2f) {
                                            val window = dialogWindow ?: return@detectVerticalDragGestures
                                            val attributes = window.attributes
                                            val baseline = attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                                            attributes.screenBrightness = (baseline - dragAmount / heightPx).coerceIn(0.03f, 1f)
                                            window.attributes = attributes
                                            gestureFeedback = "السطوع " + (attributes.screenBrightness * 100f).roundToInt() + "%"
                                        } else {
                                            accumulatedDrag += -dragAmount
                                            val stepPixels = (heightPx / 20f).coerceAtLeast(1f)
                                            val steps = (accumulatedDrag / stepPixels).toInt()
                                            if (steps != 0) {
                                                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                                val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                                val newVolume = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, maxVolume)
                                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
                                                accumulatedDrag -= steps * stepPixels
                                                gestureFeedback = "الصوت " + newVolume + " / " + maxVolume
                                            }
                                        }
                                        change.consume()
                                    }
                                },
                        )
                    } else {
                        AudioArtworkSurface(mediaMetadata, currentTitle, Modifier.fillMaxSize())
                    }

                    if (controlsVisible && !touchLocked) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { isFullscreen = false }) {
                                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "إنهاء ملء الشاشة", tint = Color.White)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(currentTitle, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("مشغل الفيديو", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { aspectMode = nextAspectMode(aspectMode) }) {
                                    Icon(Icons.Rounded.AspectRatio, "تغيير نسبة العرض", tint = Color.White)
                                }
                                IconButton(onClick = { touchLocked = true; controlsVisible = false }) {
                                    Icon(Icons.Rounded.LockOpen, "قفل اللمس", tint = Color.White)
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Slider(
                                    value = sliderPosition.coerceIn(0f, maxSlider),
                                    onValueChange = { isScrubbing = true; scrubPosition = it.coerceIn(0f, maxSlider) },
                                    onValueChangeFinished = {
                                        val target = scrubPosition.toLong().coerceIn(0L, durationMs)
                                        player.seekTo(target)
                                        positionMs = target
                                        isScrubbing = false
                                    },
                                    valueRange = 0f..maxSlider,
                                    enabled = durationMs > 0L && !hasPlaybackError,
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(formatPlaybackTime(if (isScrubbing) scrubPosition.toLong() else positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    Text(formatPlaybackTime(durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                                PlaybackTransportRow(
                                    isPlaying = isPlaying,
                                    hasError = hasPlaybackError,
                                    canPrevious = canSkipPrevious,
                                    canNext = canSkipNext,
                                    onPrevious = { player.seekToPreviousMediaItem() },
                                    onRewind = { player.seekTo(PlayerEnhancementPolicy.seekTarget(player.currentPosition, player.duration, false)) },
                                    onTogglePlay = {
                                        if (player.isPlaying) player.pause()
                                        else {
                                            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                                            player.play()
                                        }
                                    },
                                    onForward = { player.seekTo(PlayerEnhancementPolicy.seekTarget(player.currentPosition, player.duration, true)) },
                                    onNext = { player.seekToNextMediaItem() },
                                    light = true,
                                )
                            }
                        }
                    }
                    if (touchLocked) {
                        Surface(
                            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                            shape = RoundedCornerShape(50),
                            color = Color.Black.copy(alpha = 0.72f),
                        ) {
                            IconButton(onClick = { touchLocked = false; controlsVisible = true }) {
                                Icon(Icons.Rounded.Lock, "فتح قفل اللمس", tint = Color.White)
                            }
                        }
                    }
                    gestureFeedback?.let {
                        Surface(Modifier.align(Alignment.Center), shape = RoundedCornerShape(18.dp), color = Color.Black.copy(alpha = 0.72f)) {
                            Text(it, Modifier.padding(horizontal = 18.dp, vertical = 12.dp), color = Color.White)
                        }
                    }
                    if (isBuffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                    if (hasPlaybackError) {
                        Surface(Modifier.align(Alignment.Center).padding(24.dp), color = Color.Black.copy(alpha = 0.88f), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("تعذّر تشغيل الملف", color = Color.White)
                                TextButton(onClick = { retryPlayback(player) }) { Text("إعادة المحاولة") }
                            }
                        }
                    }
                }
            } else {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(currentTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        if (currentType == LocalMediaPlaybackType.VIDEO) "مشغل الفيديو" else "مشغل الصوت",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = {
                                    if (currentType == LocalMediaPlaybackType.AUDIO) isMiniPlayer = true
                                    else onDismiss()
                                }) {
                                    Icon(
                                        if (currentType == LocalMediaPlaybackType.AUDIO) Icons.Rounded.KeyboardArrowDown
                                        else Icons.AutoMirrored.Rounded.ArrowBack,
                                        if (currentType == LocalMediaPlaybackType.AUDIO) "تصغير المشغل ومتابعة التصفح"
                                        else "العودة إلى التنزيلات",
                                    )
                                }
                            },
                            actions = {
                                if (currentType == LocalMediaPlaybackType.VIDEO) {
                                    IconButton(onClick = { isFullscreen = true; controlsVisible = true }) {
                                        Icon(Icons.Rounded.Fullscreen, "ملء الشاشة")
                                    }
                                }
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.Rounded.Close, "إغلاق المشغل")
                                }
                            },
                        )
                    },
                ) { padding ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (currentType == LocalMediaPlaybackType.VIDEO) {
                            Box(
                                modifier = Modifier.fillMaxWidth().aspectRatio(containerRatio)
                                    .clip(MaterialTheme.shapes.large).background(Color.Black),
                            ) {
                                VideoPlayerSurface(player, resizeMode, Modifier.fillMaxSize())
                                if (isBuffering) CircularProgressIndicator(Modifier.align(Alignment.Center), strokeWidth = 2.dp)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PlayerAspectMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = aspectMode == mode,
                                        onClick = { aspectMode = mode },
                                        label = { Text(mode.label, maxLines = 1) },
                                    )
                                }
                            }
                        } else {
                            AudioArtworkSurface(mediaMetadata, currentTitle, Modifier.fillMaxWidth().aspectRatio(1f))
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    mediaMetadata?.title?.takeIf { it.isNotBlank() } ?: currentTitle,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    mediaMetadata?.artist?.takeIf { it.isNotBlank() } ?: "ملف صوتي محلي",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    formatMediaBytes(currentRecord.bytesDownloaded) + " · " +
                                        currentRecord.task.destinationPath.substringAfterLast('.', ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (isBuffering && currentType == LocalMediaPlaybackType.AUDIO) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text("جارٍ تجهيز التشغيل", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (hasPlaybackError) {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "تعذّر تشغيل الملف. قد تكون الصيغة غير مدعومة أو قد يكون الملف تالفًا.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                TextButton(onClick = { retryPlayback(player) }) { Text("إعادة المحاولة") }
                            }
                        }

                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Slider(
                                value = sliderPosition.coerceIn(0f, maxSlider),
                                onValueChange = { isScrubbing = true; scrubPosition = it.coerceIn(0f, maxSlider) },
                                onValueChangeFinished = {
                                    val target = scrubPosition.toLong().coerceIn(0L, durationMs)
                                    player.seekTo(target)
                                    positionMs = target
                                    isScrubbing = false
                                },
                                valueRange = 0f..maxSlider,
                                enabled = durationMs > 0L && !hasPlaybackError,
                            )
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatPlaybackTime(if (isScrubbing) scrubPosition.toLong() else positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatPlaybackTime(durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        PlaybackTransportRow(
                            isPlaying = isPlaying,
                            hasError = hasPlaybackError,
                            canPrevious = canSkipPrevious,
                            canNext = canSkipNext,
                            onPrevious = { player.seekToPreviousMediaItem() },
                            onRewind = { player.seekTo(PlayerEnhancementPolicy.seekTarget(player.currentPosition, player.duration, false)) },
                            onTogglePlay = {
                                if (player.isPlaying) player.pause()
                                else {
                                    if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                                    player.play()
                                }
                            },
                            onForward = { player.seekTo(PlayerEnhancementPolicy.seekTarget(player.currentPosition, player.duration, true)) },
                            onNext = { player.seekToNextMediaItem() },
                            light = false,
                        )
                        TextButton(onClick = { showSpeedDialog = true }) {
                            Text("سرعة التشغيل · " + formatPlaybackSpeed(playbackSpeed) + "×")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { repeatMode = nextRepeatMode(repeatMode); player.repeatMode = repeatMode }) {
                                Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(repeatModeLabel(repeatMode))
                            }
                            TextButton(onClick = { shuffleEnabled = !shuffleEnabled; player.shuffleModeEnabled = shuffleEnabled }) {
                                Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (shuffleEnabled) "العشوائي يعمل" else "تشغيل عشوائي")
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { showQueueDialog = true }) {
                                Icon(Icons.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("قائمة التشغيل (" + activeQueue.size + ")")
                            }
                            TextButton(onClick = { showSleepTimerDialog = true }) {
                                Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (sleepTimerDeadlineElapsedMs > 0L) {
                                        "مؤقت · " + formatPlaybackTime(sleepTimerRemainingSeconds * 1_000L)
                                    } else "مؤقت النوم",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }

    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("سرعة التشغيل") },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    PlayerEnhancementPolicy.supportedSpeeds.forEach { speed ->
                        TextButton(onClick = {
                            playbackSpeed = speed
                            player.setPlaybackParameters(PlaybackParameters(speed))
                            showSpeedDialog = false
                        }) {
                            Text(
                                (if (speed == playbackSpeed) "✓ " else "") +
                                    formatPlaybackSpeed(speed) + "×",
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSpeedDialog = false }) { Text("إغلاق") } },
        )
    }

    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("مؤقت إيقاف التشغيل") },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("أوقف التشغيل تلقائيًا بعد المدة المحددة.")
                    PlaybackTimerPolicy.presetMinutes.forEach { minutes ->
                        TextButton(onClick = {
                            sleepTimerDeadlineElapsedMs = PlaybackTimerPolicy.deadlineAfterMinutes(
                                nowElapsedMs = SystemClock.elapsedRealtime(),
                                minutes = minutes,
                            ) ?: 0L
                            showSleepTimerDialog = false
                        }) { Text(minutes.toString() + " دقيقة") }
                    }
                    if (sleepTimerDeadlineElapsedMs > 0L) {
                        TextButton(onClick = {
                            sleepTimerDeadlineElapsedMs = 0L
                            sleepTimerRemainingSeconds = 0L
                            showSleepTimerDialog = false
                        }) { Text("إلغاء المؤقت الحالي") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSleepTimerDialog = false }) { Text("إغلاق") } },
        )
    }

    if (showQueueDialog) {
        AlertDialog(
            onDismissRequest = { showQueueDialog = false },
            title = { Text("قائمة التشغيل") },
            text = {
                if (activeQueue.isEmpty()) Text("لا توجد ملفات متاحة في قائمة التشغيل.")
                else LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    itemsIndexed(activeQueue, key = { _, item -> item.task.id }) { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (index == currentQueueIndex) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                                .clickable { selectQueueItem(index) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(end = 4.dp)) {
                                Text(displayTitle(item), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                if (index == currentQueueIndex) Text("يعمل الآن", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { moveQueueItem(index, index - 1) }, enabled = index > 0) { Text("↑") }
                            IconButton(onClick = { moveQueueItem(index, index + 1) }, enabled = index < activeQueue.lastIndex) { Text("↓") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showQueueDialog = false }) { Text("إغلاق") } },
        )
    }
}

@Composable
private fun MiniPlayerBar(
    title: String,
    subtitle: String,
    isPlaying: Boolean,
    onExpand: () -> Unit,
    onTogglePlay: () -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onExpand) {
                    Icon(Icons.Rounded.Fullscreen, contentDescription = "تكبير المشغل")
                }
                Column(
                    modifier = Modifier.weight(1f).clickable(onClick = onExpand).padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onTogglePlay) {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "إغلاق المشغل")
                }
            }
        }
    }
}

@Composable
private fun ImmersiveWindowBarsEffect(
    enabled: Boolean,
    window: Window?,
    view: android.view.View,
) {
    DisposableEffect(enabled, window, view) {
        if (!enabled || window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val originalBrightness = window.attributes.screenBrightness
            val oldBehavior = controller.systemBarsBehavior
            val barsWereVisible = runCatching {
                WindowInsetsCompat.toWindowInsetsCompat(window.decorView.rootWindowInsets, window.decorView)
                    .isVisible(WindowInsetsCompat.Type.systemBars())
            }.getOrDefault(true)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            onDispose {
                val attributes = window.attributes
                attributes.screenBrightness = originalBrightness
                window.attributes = attributes
                controller.systemBarsBehavior = oldBehavior
                if (barsWereVisible) controller.show(WindowInsetsCompat.Type.systemBars())
                else controller.hide(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
        }
    }
}

@Composable
private fun PlaybackTransportRow(
    isPlaying: Boolean,
    hasError: Boolean,
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onRewind: () -> Unit,
    onTogglePlay: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    light: Boolean,
) {
    val foreground = if (light) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(onClick = onPrevious, enabled = canPrevious) { Icon(Icons.Rounded.SkipPrevious, "السابق", tint = foreground) }
        IconButton(onClick = onRewind, enabled = !hasError) { Icon(Icons.Rounded.FastRewind, "رجوع 10 ثوانٍ", tint = foreground) }
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            IconButton(onClick = onTogglePlay, enabled = !hasError, modifier = Modifier.size(58.dp)) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        IconButton(onClick = onForward, enabled = !hasError) { Icon(Icons.Rounded.FastForward, "تقديم 10 ثوانٍ", tint = foreground) }
        IconButton(onClick = onNext, enabled = canNext) { Icon(Icons.Rounded.SkipNext, "التالي", tint = foreground) }
    }
}

@Composable
private fun UnavailableMediaDialog(onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Rounded.AudioFile, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("الملف غير متاح للتشغيل", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "تعذّر الوصول إلى النسخة المحلية. حدّث سجل التنزيلات أو أعد تنزيل الملف.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                TextButton(onClick = onDismiss) { Text("إغلاق") }
            }
        }
    }
}

@Composable
private fun VideoPlayerSurface(player: Player, resizeMode: Int, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = player
                useController = false
                this.resizeMode = resizeMode
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                keepScreenOn = true
            }
        },
        update = { view ->
            view.player = player
            view.resizeMode = resizeMode
            view.keepScreenOn = player.isPlaying
        },
        onRelease = { view ->
            view.player = null
            view.keepScreenOn = false
        },
    )
}

@Composable
private fun AudioArtworkSurface(metadata: LocalArtworkMetadata?, title: String, modifier: Modifier) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(24.dp)),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(24.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            val artwork = metadata?.artwork
            if (artwork != null) {
                Image(
                    bitmap = artwork.asImageBitmap(),
                    contentDescription = "غلاف " + title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Rounded.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(108.dp),
                )
            }
        }
    }
}

private data class LocalArtworkMetadata(val title: String?, val artist: String?, val artwork: Bitmap?)

private fun readLocalArtworkMetadata(context: Context, uri: Uri): LocalArtworkMetadata? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val cover = retriever.embeddedPicture
        val bitmap = cover?.takeIf { it.size <= MAX_COVER_ART_BYTES }?.let(::decodeArtwork)
        LocalArtworkMetadata(
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
            artwork = bitmap,
        )
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun decodeArtwork(data: ByteArray): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > MAX_ARTWORK_DIMENSION || bounds.outHeight / sampleSize > MAX_ARTWORK_DIMENSION) {
        sampleSize *= 2
    }
    BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) })
}.getOrNull()

private fun persistPlaybackPosition(store: PlaybackPositionStore, taskId: String, player: Player) {
    val durationMs = player.duration
    if (durationMs <= 0L || durationMs == C.TIME_UNSET || player.playbackState == Player.STATE_IDLE) return
    store.write(taskId, player.currentPosition.coerceAtLeast(0L), durationMs)
}

private fun retryPlayback(player: Player) {
    player.seekToDefaultPosition()
    player.prepare()
    player.play()
}

private fun mediaTypeFor(record: DownloadRecord, fallback: LocalMediaPlaybackType): LocalMediaPlaybackType =
    resolveLocalMediaPlaybackType(
        mediaKind = record.task.mediaKind,
        processingMode = record.task.processingMode,
        displayName = record.task.displayName,
        destinationPath = record.task.destinationPath,
    ) ?: fallback

private fun displayTitle(record: DownloadRecord): String =
    record.task.displayName
        ?.substringAfterLast('/')
        ?.substringBeforeLast('.', record.task.displayName.orEmpty())
        ?.takeIf { it.isNotBlank() }
        ?: "ملف وسائط"

private fun resolveLocalMediaUri(record: DownloadRecord): Uri? {
    val destination = record.destinationUri
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    if (destination != null && isLocalMediaUri(destination.toString())) return destination
    val localFile = File(record.task.destinationPath)
    return localFile.takeIf { it.isFile && it.canRead() }?.let(Uri::fromFile)
}

private fun resolveMediaMimeType(context: Context, record: DownloadRecord, uri: Uri, type: LocalMediaPlaybackType): String? {
    val fromProvider = runCatching { context.contentResolver.getType(uri) }
        .getOrNull()
        ?.takeIf { it.startsWith("audio/") || it.startsWith("video/") }
    if (fromProvider != null) return fromProvider
    val extension = sequenceOf(record.task.displayName, record.task.destinationPath)
        .filterNotNull()
        .map { it.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase(Locale.ROOT) }
        .firstOrNull { it.isNotBlank() }
        ?: return null
    return when (extension) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> if (type == LocalMediaPlaybackType.AUDIO) "audio/webm" else "video/webm"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "3gp" -> "video/3gpp"
        "mpg", "mpeg" -> "video/mpeg"
        "ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg", "oga" -> "audio/ogg"
        "opus" -> "audio/opus"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "amr" -> "audio/amr"
        else -> null
    }
}

private fun nextAspectMode(current: PlayerAspectMode): PlayerAspectMode {
    val modes = PlayerAspectMode.entries
    return modes[(modes.indexOf(current) + 1) % modes.size]
}

private fun nextRepeatMode(current: Int): Int = when (current) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
    Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
    else -> Player.REPEAT_MODE_OFF
}

private fun repeatModeLabel(mode: Int): String = when (mode) {
    Player.REPEAT_MODE_ONE -> "تكرار الملف"
    Player.REPEAT_MODE_ALL -> "تكرار القائمة"
    else -> "التكرار متوقف"
}

private fun formatPlaybackTime(valueMs: Long): String {
    val totalSeconds = valueMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
}

private fun formatPlaybackSpeed(speed: Float): String =
    if (speed % 1f == 0f) String.format(Locale.ROOT, "%.0f", speed)
    else String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')

private fun formatMediaBytes(value: Long): String {
    val bytes = value.coerceAtLeast(0L)
    if (bytes < 1_024L) return bytes.toString() + " B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var amount = bytes.toDouble()
    var unitIndex = -1
    do {
        amount /= 1_024.0
        unitIndex++
    } while (amount >= 1_024.0 && unitIndex < units.lastIndex)
    return String.format(Locale.ROOT, "%.1f %s", amount, units[unitIndex])
}

private const val CONTROLS_AUTO_HIDE_MS = 3_500L
private const val GESTURE_FEEDBACK_DURATION_MS = 1_200L
private const val MAX_COVER_ART_BYTES = 8 * 1024 * 1024
private const val MAX_ARTWORK_DIMENSION = 512
