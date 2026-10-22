package com.ahdownload.app.player

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import com.ahdownload.domain.download.DownloadRecord
import com.ahdownload.domain.download.LocalMediaPlaybackType
import com.ahdownload.domain.download.isLocalMediaUri
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun LocalMediaPlayerDialog(
    record: DownloadRecord,
    playbackType: LocalMediaPlaybackType,
    onDismiss: () -> Unit,
    onPlaybackError: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    val mediaUri = remember(record) { resolveLocalMediaUri(record) }
    val currentErrorCallback by rememberUpdatedState(onPlaybackError)
    val playbackPositionStore = remember(context) { PlaybackPositionStore(context) }
    val playbackTaskId = record.task.id
    val initialPlaybackPositionMs = remember(playbackTaskId, playbackPositionStore) {
        restorablePlaybackPosition(playbackPositionStore.read(playbackTaskId))
    }

    if (mediaUri == null) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
            ),
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.AudioFile,
                        contentDescription = null,
                        modifier = Modifier.size(54.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
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
        return
    }

    val mimeType = remember(context, record, mediaUri) {
        resolveMediaMimeType(context, record, mediaUri, playbackType)
    }
    val player = remember(mediaUri, mimeType) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(
                        if (playbackType == LocalMediaPlaybackType.AUDIO) {
                            C.AUDIO_CONTENT_TYPE_MUSIC
                        } else {
                            C.AUDIO_CONTENT_TYPE_MOVIE
                        },
                    )
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            setMediaItem(
                MediaItem.Builder()
                    .setUri(mediaUri)
                    .apply { mimeType?.let(::setMimeType) }
                    .build(),
                initialPlaybackPositionMs,
            )
            prepare()
            playWhenReady = true
        }
    }

    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }
    var isBuffering by remember(player) {
        mutableStateOf(player.playbackState == Player.STATE_BUFFERING)
    }
    var durationMs by remember(player) { mutableLongStateOf(0L) }
    var positionMs by remember(player) { mutableLongStateOf(0L) }
    var isScrubbing by remember(player) { mutableStateOf(false) }
    var scrubPosition by remember(player) { mutableFloatStateOf(0f) }
    var hasPlaybackError by remember(player) { mutableStateOf(false) }
    var showSpeedDialog by remember(player) { mutableStateOf(false) }
    var playbackSpeed by remember(player) { mutableFloatStateOf(1f) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED && player.duration > 0L) {
                    positionMs = player.duration
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
            persistPlaybackPosition(playbackPositionStore, playbackTaskId, player)
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                persistPlaybackPosition(playbackPositionStore, playbackTaskId, player)
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

    LaunchedEffect(player, playbackTaskId, playbackPositionStore) {
        while (true) {
            delay(1_000L)
            persistPlaybackPosition(playbackPositionStore, playbackTaskId, player)
        }
    }

    val title = record.task.displayName
        ?.substringBeforeLast('.', record.task.displayName.orEmpty())
        ?.takeIf { it.isNotBlank() }
        ?: "ملف وسائط"
    val maxSlider = durationMs.coerceAtLeast(1L).toFloat()
    val sliderPosition = if (isScrubbing) scrubPosition else positionMs.toFloat()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(
                                    title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (playbackType == LocalMediaPlaybackType.VIDEO) "مشغل الفيديو" else "مشغل الصوت",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "العودة إلى التنزيلات",
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (playbackType == LocalMediaPlaybackType.VIDEO) {
                        Surface(
                            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large),
                            color = Color.Black,
                            shape = MaterialTheme.shapes.large,
                        ) {
                            PlayerSurface(
                                player = player,
                                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            )
                        }
                    } else {
                        Surface(
                            modifier = Modifier.fillMaxWidth().aspectRatio(1.35f).clip(MaterialTheme.shapes.extraLarge),
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Rounded.Headphones,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(108.dp),
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (playbackType == LocalMediaPlaybackType.VIDEO) "تشغيل محلي من ملفات التنزيلات" else "استمع إلى الملف الصوتي المحفوظ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (isBuffering) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("جارٍ تجهيز التشغيل", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (hasPlaybackError) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "تعذّر تشغيل الملف. قد تكون الصيغة غير مدعومة أو قد يكون الملف تالفًا.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(
                                onClick = {
                                    hasPlaybackError = false
                                    player.seekToDefaultPosition()
                                    player.prepare()
                                    player.play()
                                },
                            ) {
                                Text("إعادة المحاولة")
                            }
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Slider(
                            value = sliderPosition.coerceIn(0f, maxSlider),
                            onValueChange = {
                                isScrubbing = true
                                scrubPosition = it.coerceIn(0f, maxSlider)
                            },
                            onValueChangeFinished = {
                                player.seekTo(scrubPosition.toLong().coerceIn(0L, durationMs))
                                positionMs = scrubPosition.toLong().coerceIn(0L, durationMs)
                                isScrubbing = false
                            },
                            valueRange = 0f..maxSlider,
                            enabled = durationMs > 0L && !hasPlaybackError,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                formatPlaybackTime(if (isScrubbing) scrubPosition.toLong() else positionMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                formatPlaybackTime(durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) },
                            enabled = durationMs > 0L && !hasPlaybackError,
                        ) { Text("−10 ث") }
                        Spacer(Modifier.size(12.dp))
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            IconButton(
                                onClick = {
                                    if (player.isPlaying) {
                                        player.pause()
                                    } else {
                                        if (player.playbackState == Player.STATE_ENDED) {
                                            player.seekToDefaultPosition()
                                        }
                                        player.play()
                                    }
                                },
                                enabled = !hasPlaybackError,
                                modifier = Modifier.size(64.dp),
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                                    modifier = Modifier.size(32.dp),
                                )
                            }
                        }
                        Spacer(Modifier.size(12.dp))
                        TextButton(
                            onClick = { player.seekTo((player.currentPosition + 10_000L).coerceAtMost(durationMs)) },
                            enabled = durationMs > 0L && !hasPlaybackError,
                        ) { Text("+10 ث") }
                    }

                    TextButton(onClick = { showSpeedDialog = true }) {
                        Text("سرعة التشغيل · ${formatPlaybackSpeed(playbackSpeed)}×")
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
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                        TextButton(
                            onClick = {
                                playbackSpeed = speed
                                player.setPlaybackParameters(PlaybackParameters(speed))
                                showSpeedDialog = false
                            },
                        ) {
                            Text(
                                text = buildString {
                                    if (speed == playbackSpeed) append("✓ ")
                                    append(formatPlaybackSpeed(speed))
                                    append("×")
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) { Text("إغلاق") }
            },
        )
    }
}

private fun persistPlaybackPosition(
    store: PlaybackPositionStore,
    taskId: String,
    player: Player,
) {
    val durationMs = player.duration
    if (durationMs <= 0L || durationMs == C.TIME_UNSET || player.playbackState == Player.STATE_IDLE) {
        return
    }
    store.write(
        taskId = taskId,
        positionMs = player.currentPosition.coerceAtLeast(0L),
        durationMs = durationMs,
    )
}

private fun formatPlaybackSpeed(speed: Float): String {
    return if (speed % 1f == 0f) {
        String.format(Locale.ROOT, "%.0f", speed)
    } else {
        String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')
    }
}

private fun resolveLocalMediaUri(record: DownloadRecord): Uri? {
    val destination = record.destinationUri?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
    if (destination != null && isLocalMediaUri(destination.toString())) return destination

    val localFile = File(record.task.destinationPath)
    return localFile.takeIf { it.isFile }?.let(Uri::fromFile)
}

private fun resolveMediaMimeType(
    context: Context,
    record: DownloadRecord,
    uri: Uri,
    type: LocalMediaPlaybackType,
): String? {
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
        "ogg", "oga", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "amr" -> "audio/amr"
        else -> null
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1_000L
    val hours = seconds / 3_600L
    val minutes = (seconds % 3_600L) / 60L
    val remainingSeconds = seconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainingSeconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, remainingSeconds)
    }
}
