package com.streamvault.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.streamvault.app.viewmodel.PlayerSession
import com.streamvault.app.viewmodel.StreamVaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs

private data class MediaTrackOption(
    val label: String,
    val trackGroup: TrackGroup,
    val trackIndex: Int,
    val isSelected: Boolean
)

private enum class GestureHudType {
    NONE, SEEK, BRIGHTNESS, VOLUME
}

@OptIn(UnstableApi::class)
@Composable
fun NetflixVideoPlayer(
    session: PlayerSession,
    hasNextEpisode: Boolean,
    onProgressSaved: (Long, Long) -> Unit,
    onNextEpisode: () -> Unit,
    onClose: () -> Unit
) {
    if (session.webPageUrl != null) {
        WebEmbedVideoPlayer(
            session = session,
            hasNextEpisode = hasNextEpisode,
            onNextEpisode = onNextEpisode,
            onClose = onClose
        )
        return
    }

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember(audioManager) { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    var isLandscape by remember { mutableStateOf(false) }
    var isScreenLocked by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosMs by remember { mutableLongStateOf(session.startPositionMs) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var fallbackDurationMs by remember(session.videoUri) { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    // XPlayer-style Gesture HUD states (Horizontal Swipe Seek, Left Vertical Brightness, Right Vertical Volume)
    var activeHud by remember { mutableStateOf(GestureHudType.NONE) }
    var gestureSeekTargetMs by remember { mutableLongStateOf(0L) }
    var gestureDeltaMs by remember { mutableLongStateOf(0L) }
    var brightnessLevel by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
        )
    }
    var volumeFraction by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume.toFloat())
    }

    var audioTracks by remember { mutableStateOf<List<MediaTrackOption>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<MediaTrackOption>>(emptyList()) }
    var subtitlesDisabled by remember { mutableStateOf(false) }
    var showAudioSubtitleSheet by remember { mutableStateOf(false) }
    var externalSubtitleUri by remember(session.videoUri) { mutableStateOf<Uri?>(null) }

    val subtitlePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            externalSubtitleUri = uri
        }
    }

    // Retrieve metadata duration for unindexed MKV/MP4 files
    LaunchedEffect(session.videoUri) {
        fallbackDurationMs = withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, session.videoUri)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                retriever.release()
                durStr?.toLongOrNull() ?: 0L
            }.getOrDefault(0L)
        }
        if (fallbackDurationMs > 0L && durationMs <= 0L) {
            durationMs = fallbackDurationMs
        }
    }

    LaunchedEffect(isLandscape) {
        activity?.let { act ->
            act.requestedOrientation = if (isLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            val window = act.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.let { act ->
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                val window = act.window
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            setEnableDecoderFallback(true)
        }

        val defaultFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setConstantBitrateSeekingAlwaysEnabled(true)
            .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)

        // Put SeekableMatroskaExtractor FIRST so MKV files without Cues get a real ConstantBitrateSeekMap!
        val customExtractorsFactory = ExtractorsFactory {
            val defaults: Array<Extractor> = defaultFactory.createExtractors()
            val localPath = if (session.videoUri.scheme == "file") session.videoUri.path else null
            val custom: Array<Extractor> = arrayOf(
                SeekableMatroskaExtractor(
                    localFilePath = localPath,
                    knownDurationMs = fallbackDurationMs
                )
            )
            custom + defaults
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context), customExtractorsFactory)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 250, 500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000L)
            .setSeekForwardIncrementMs(10_000L)
            .build().apply {
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                playWhenReady = true
            }
    }

    fun getEffectiveDurationMs(): Long = when {
        exoPlayer.duration > 0L -> exoPlayer.duration
        durationMs > 0L -> durationMs
        fallbackDurationMs > 0L -> fallbackDurationMs
        else -> 0L
    }

    fun performSmoothSeek(targetMs: Long) {
        val dur = getEffectiveDurationMs()
        val safeTarget = if (dur > 0L) targetMs.coerceIn(0L, (dur - 250L).coerceAtLeast(0L)) else targetMs.coerceAtLeast(0L)
        currentPosMs = safeTarget
        exoPlayer.seekTo(safeTarget)
        if (exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playbackState == Player.STATE_ENDED) {
            exoPlayer.prepare()
        }
        exoPlayer.playWhenReady = true
    }

    val saveAndClose = {
        onProgressSaved(exoPlayer.currentPosition, getEffectiveDurationMs())
        onClose()
    }

    BackHandler {
        when {
            isScreenLocked -> controlsVisible = true
            showAudioSubtitleSheet -> showAudioSubtitleSheet = false
            isLandscape -> isLandscape = false
            else -> saveAndClose()
        }
    }

    LaunchedEffect(session.videoUri, externalSubtitleUri) {
        val resumePos = when {
            externalSubtitleUri != null && exoPlayer.currentPosition > 0L -> exoPlayer.currentPosition
            session.startPositionMs > 0L -> session.startPositionMs
            else -> 0L
        }

        val mediaItemBuilder = MediaItem.Builder().setUri(session.videoUri)
        externalSubtitleUri?.let { subUri ->
            val mimeType = detectSubtitleMimeType(context, subUri)
            val subConfig = MediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(mimeType)
                .setLanguage("en")
                .setLabel("External Subtitle")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            mediaItemBuilder.setSubtitleConfigurations(listOf(subConfig))
        }

        exoPlayer.setMediaItem(mediaItemBuilder.build(), resumePos)
        exoPlayer.prepare()
        if (externalSubtitleUri != null) {
            subtitlesDisabled = false
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (!playing) {
                    onProgressSaved(exoPlayer.currentPosition, getEffectiveDurationMs())
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val dur = exoPlayer.duration
                    durationMs = if (dur > 0L) dur else fallbackDurationMs
                    currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val parsedAudio = mutableListOf<MediaTrackOption>()
                val parsedSubs = mutableListOf<MediaTrackOption>()

                for (group in tracks.groups) {
                    val mediaTrackGroup = group.mediaTrackGroup
                    when (group.type) {
                        C.TRACK_TYPE_AUDIO -> {
                            for (i in 0 until group.length) {
                                if (group.isTrackSupported(i)) {
                                    val fmt = group.getTrackFormat(i)
                                    parsedAudio.add(
                                        MediaTrackOption(
                                            label = buildAudioTrackLabel(fmt, parsedAudio.size + 1),
                                            trackGroup = mediaTrackGroup,
                                            trackIndex = i,
                                            isSelected = group.isTrackSelected(i)
                                        )
                                    )
                                }
                            }
                        }
                        C.TRACK_TYPE_TEXT -> {
                            for (i in 0 until group.length) {
                                if (group.isTrackSupported(i)) {
                                    val fmt = group.getTrackFormat(i)
                                    parsedSubs.add(
                                        MediaTrackOption(
                                            label = buildSubtitleTrackLabel(fmt, parsedSubs.size + 1),
                                            trackGroup = mediaTrackGroup,
                                            trackIndex = i,
                                            isSelected = group.isTrackSelected(i)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                audioTracks = parsedAudio
                subtitleTracks = parsedSubs
                subtitlesDisabled = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            onProgressSaved(exoPlayer.currentPosition, getEffectiveDurationMs())
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(exoPlayer, isPlaying) {
        while (isPlaying) {
            delay(5000L)
            if (getEffectiveDurationMs() > 0L) {
                onProgressSaved(exoPlayer.currentPosition, getEffectiveDurationMs())
            }
        }
    }

    LaunchedEffect(exoPlayer, isPlaying, controlsVisible, isScrubbing) {
        if (controlsVisible && !isScrubbing) {
            while (true) {
                currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                val dur = exoPlayer.duration
                if (dur > 0L) {
                    durationMs = dur
                } else if (fallbackDurationMs > 0L && durationMs <= 0L) {
                    durationMs = fallbackDurationMs
                }
                delay(250L)
            }
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, showAudioSubtitleSheet, isScrubbing) {
        if (controlsVisible && isPlaying && !showAudioSubtitleSheet && !isScrubbing) {
            delay(3800L)
            controlsVisible = false
        }
    }

    val config = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx() }.coerceAtLeast(1f)
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }.coerceAtLeast(1f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // XPlayer Double-Tap (Left = -10s, Center = Play/Pause, Right = +10s) & Single Tap toggle
            .pointerInput(isScreenLocked) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                    },
                    onDoubleTap = { offset ->
                        if (isScreenLocked) return@detectTapGestures
                        when {
                            offset.x < screenWidthPx * 0.33f -> {
                                val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                                exoPlayer.seekTo(target)
                                currentPosMs = target
                            }
                            offset.x > screenWidthPx * 0.67f -> {
                                val dur = getEffectiveDurationMs()
                                val target = if (dur > 0L) {
                                    (exoPlayer.currentPosition + 10_000L).coerceAtMost((dur - 500L).coerceAtLeast(0L))
                                } else {
                                    exoPlayer.currentPosition + 10_000L
                                }
                                exoPlayer.seekTo(target)
                                currentPosMs = target
                            }
                            else -> {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            }
                        }
                    }
                )
            }
            // XPlayer Swipe Gestures: Horizontal = Smooth Timeline Scrub, Left Vertical = Brightness, Right Vertical = Volume
            .pointerInput(isScreenLocked) {
                var dragMode = GestureHudType.NONE
                var startX = 0f
                var initialSeekMs = 0L

                detectDragGestures(
                    onDragStart = { startOffset ->
                        if (isScreenLocked) return@detectDragGestures
                        dragMode = GestureHudType.NONE
                        startX = startOffset.x
                        initialSeekMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                    },
                    onDragEnd = {
                        if (dragMode == GestureHudType.SEEK && !isScreenLocked) {
                            performSmoothSeek(gestureSeekTargetMs)
                            isScrubbing = false
                        }
                        dragMode = GestureHudType.NONE
                        activeHud = GestureHudType.NONE
                    },
                    onDragCancel = {
                        isScrubbing = false
                        dragMode = GestureHudType.NONE
                        activeHud = GestureHudType.NONE
                    },
                    onDrag = { change, dragAmount ->
                        if (isScreenLocked) return@detectDragGestures
                        change.consume()

                        if (dragMode == GestureHudType.NONE) {
                            dragMode = if (abs(dragAmount.x) > abs(dragAmount.y)) {
                                GestureHudType.SEEK
                            } else if (startX < screenWidthPx * 0.5f) {
                                GestureHudType.BRIGHTNESS
                            } else {
                                GestureHudType.VOLUME
                            }
                            activeHud = dragMode
                            if (dragMode == GestureHudType.SEEK) {
                                isScrubbing = true
                            }
                        }

                        when (dragMode) {
                            GestureHudType.SEEK -> {
                                val dur = getEffectiveDurationMs()
                                if (dur > 0L) {
                                    // Full screen width drag = up to 3 minutes (180s) of fine scrubbing
                                    val deltaStep = ((dragAmount.x / screenWidthPx) * 180_000L).toLong()
                                    gestureDeltaMs += deltaStep
                                    gestureSeekTargetMs = (initialSeekMs + gestureDeltaMs).coerceIn(0L, dur)
                                    currentPosMs = gestureSeekTargetMs
                                }
                            }
                            GestureHudType.BRIGHTNESS -> {
                                val deltaB = -dragAmount.y / (screenHeightPx * 0.65f)
                                brightnessLevel = (brightnessLevel + deltaB).coerceIn(0.02f, 1.0f)
                                activity?.window?.let { win ->
                                    win.attributes = win.attributes.apply {
                                        screenBrightness = brightnessLevel
                                    }
                                }
                            }
                            GestureHudType.VOLUME -> {
                                val deltaV = -dragAmount.y / (screenHeightPx * 0.65f)
                                volumeFraction = (volumeFraction + deltaV).coerceIn(0f, 1f)
                                val targetVol = (volumeFraction * maxVolume).toInt().coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                            }
                            GestureHudType.NONE -> Unit
                        }
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    subtitleView?.apply {
                        setApplyEmbeddedStyles(true)
                        setApplyEmbeddedFontSizes(true)
                        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f)
                    }
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                if (playerView.resizeMode != resizeMode) {
                    playerView.resizeMode = resizeMode
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // XPlayer Center Gesture HUD (Seek Time / Brightness % / Volume %)
        if (activeHud != GestureHudType.NONE) {
            Surface(
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (activeHud) {
                        GestureHudType.SEEK -> {
                            Text(
                                text = StreamVaultViewModel.formatDuration(gestureSeekTargetMs),
                                color = Color.White,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Total: ${StreamVaultViewModel.formatDuration(getEffectiveDurationMs())}",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                        GestureHudType.BRIGHTNESS -> {
                            Icon(Icons.Default.BrightnessHigh, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Brightness ${(brightnessLevel * 100).toInt()}%",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        GestureHudType.VOLUME -> {
                            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Volume ${(volumeFraction * 100).toInt()}%",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        GestureHudType.NONE -> Unit
                    }
                }
            }
        }

        // Screen Locked Floating Unlock Button
        if (isScreenLocked && controlsVisible) {
            IconButton(
                onClick = { isScreenLocked = false },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE50914))
            ) {
                Icon(Icons.Default.Lock, contentDescription = "Unlock Screen", tint = Color.White)
            }
        }

        // Full XPlayer-inspired Overlay Controls (when unlocked)
        AnimatedVisibility(
            visible = controlsVisible && !isScreenLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.78f),
                                Color.Transparent,
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.88f)
                            )
                        )
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (isLandscape) isLandscape = false else saveAndClose()
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.title,
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = session.subtitle,
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        onClick = { isLandscape = !isLandscape },
                        shape = RoundedCornerShape(50),
                        color = Color(0xFFE50914),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ScreenRotation,
                                contentDescription = "Rotate Screen",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (isLandscape) "Vertical" else "Horizontal",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // CENTER CONTROLS: -10s | Play/Pause | +10s
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(36.dp)
                ) {
                    IconButton(
                        onClick = {
                            val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                            exoPlayer.seekTo(target)
                            currentPosMs = target
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Replay10,
                            contentDescription = "Rewind 10s",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE50914))
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Play or Pause",
                            tint = Color.White,
                            modifier = Modifier.size(42.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val dur = getEffectiveDurationMs()
                            val target = if (dur > 0L) {
                                (exoPlayer.currentPosition + 10_000L).coerceAtMost((dur - 500L).coerceAtLeast(0L))
                            } else {
                                exoPlayer.currentPosition + 10_000L
                            }
                            exoPlayer.seekTo(target)
                            currentPosMs = target
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Forward10,
                            contentDescription = "Forward 10s",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                // BOTTOM SCRUBBER + LOCK + AUDIO & SUBTITLES + SPEED + ASPECT RATIO + NEXT EP
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = StreamVaultViewModel.formatDuration(currentPosMs),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )

                        val safeDuration = getEffectiveDurationMs().coerceAtLeast(1L)
                        Slider(
                            value = (currentPosMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f),
                            onValueChange = { fraction ->
                                isScrubbing = true
                                val dur = getEffectiveDurationMs()
                                if (dur > 0L) {
                                    currentPosMs = (fraction * dur).toLong()
                                }
                            },
                            onValueChangeFinished = {
                                performSmoothSeek(currentPosMs)
                                isScrubbing = false
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFE50914),
                                activeTrackColor = Color(0xFFE50914),
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp)
                        )

                        Text(
                            text = StreamVaultViewModel.formatDuration(getEffectiveDurationMs()),
                            color = Color.LightGray,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Screen Lock button (XPlayer style)
                        IconButton(
                            onClick = { isScreenLocked = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = "Lock Screen",
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // Audio & Subtitles
                        TextButton(
                            onClick = { showAudioSubtitleSheet = true },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Subtitles,
                                contentDescription = "Audio & Subtitles",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Audio/Sub",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Playback Speed
                        TextButton(
                            onClick = {
                                playbackSpeed = when (playbackSpeed) {
                                    1.0f -> 1.25f
                                    1.25f -> 1.5f
                                    1.5f -> 2.0f
                                    else -> 1.0f
                                }
                                exoPlayer.setPlaybackSpeed(playbackSpeed)
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${playbackSpeed}x",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // XPlayer Aspect Ratio Cycle: Fit -> Fill -> Stretch
                        TextButton(
                            onClick = {
                                resizeMode = when (resizeMode) {
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            val aspectLabel = when (resizeMode) {
                                AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
                                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Crop"
                                else -> "Stretch"
                            }
                            Text(
                                text = aspectLabel,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        if (hasNextEpisode) {
                            Button(
                                onClick = onNextEpisode,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black
                                ),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Next Episode",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "Next",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showAudioSubtitleSheet) {
            AudioAndSubtitleOverlay(
                audioTracks = audioTracks,
                subtitleTracks = subtitleTracks,
                subtitlesDisabled = subtitlesDisabled,
                onSelectAudio = { option ->
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(
                            TrackSelectionOverride(option.trackGroup, listOf(option.trackIndex))
                        )
                        .build()
                },
                onSelectSubtitle = { option ->
                    subtitlesDisabled = false
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(
                            TrackSelectionOverride(option.trackGroup, listOf(option.trackIndex))
                        )
                        .build()
                },
                onDisableSubtitles = {
                    subtitlesDisabled = true
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                },
                onLoadExternalSubtitle = {
                    subtitlePickerLauncher.launch(arrayOf("*/*"))
                },
                onDismiss = { showAudioSubtitleSheet = false }
            )
        }
    }
}

/**
 * Full-screen YouTube & Website Video Player with:
 * 1. Built-in Network + Cosmetic AdBlocker (`WebAdBlocker`).
 * 2. Guaranteed Horizontal Fullscreen on YouTube (`m.youtube.com`) and all websites:
 *    - Forces `SCREEN_ORIENTATION_LANDSCAPE` / `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` reliably.
 *    - Injects full-viewport fixed CSS (`100vw x 100vh`) onto YouTube's `<video>` / `#movie_player`
 *      AND clicks YouTube's fullscreen button so it never stays locked in vertical portrait!
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebEmbedVideoPlayer(
    session: PlayerSession,
    hasNextEpisode: Boolean,
    onNextEpisode: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var isLandscape by remember { mutableStateOf(false) }
    var adBlockEnabled by remember { mutableStateOf(true) }
    var blockedAdsCount by remember { mutableIntStateOf(0) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val customViewHolder = remember { arrayOfNulls<View>(1) }
    val customCallbackHolder = remember { arrayOfNulls<WebChromeClient.CustomViewCallback>(1) }

    val playableUrl = remember(session.webPageUrl) {
        convertToEmbedUrl(session.webPageUrl.orEmpty())
    }

    fun exitNativeCustomView() {
        val decorView = activity?.window?.decorView as? FrameLayout
        customViewHolder[0]?.let { view ->
            decorView?.removeView(view)
            customViewHolder[0] = null
        }
        customCallbackHolder[0]?.onCustomViewHidden()
        customCallbackHolder[0] = null
    }

    LaunchedEffect(isLandscape) {
        activity?.let { act ->
            act.requestedOrientation = if (isLandscape) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            val window = act.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }

        if (isLandscape) {
            delay(200L)
            webViewRef?.evaluateJavascript(
                """
                (function() {
                    var ytFsBtn = document.querySelector('button.fullscreen-icon, .ytp-fullscreen-button, button[aria-label*="Full screen" i], button[aria-label*="Fullscreen" i]');
                    if (ytFsBtn) {
                        ytFsBtn.click();
                    }
                    var playerEl = document.querySelector('#movie_player, ytm-player, .html5-video-player');
                    if (playerEl) {
                        playerEl.style.position = 'fixed';
                        playerEl.style.top = '0';
                        playerEl.style.left = '0';
                        playerEl.style.width = '100vw';
                        playerEl.style.height = '100vh';
                        playerEl.style.zIndex = '2147483646';
                        playerEl.style.backgroundColor = '#000';
                    }
                    var v = document.querySelector('video');
                    if (v) {
                        v.style.position = 'fixed';
                        v.style.top = '0';
                        v.style.left = '0';
                        v.style.width = '100vw';
                        v.style.height = '100vh';
                        v.style.zIndex = '2147483647';
                        v.style.objectFit = 'contain';
                        v.style.backgroundColor = '#000';
                        if (v.requestFullscreen) { v.requestFullscreen().catch(function(){}); }
                    }
                })();
                """.trimIndent(),
                null
            )
        } else {
            exitNativeCustomView()
            webViewRef?.evaluateJavascript(
                """
                (function() {
                    if (document.fullscreenElement && document.exitFullscreen) {
                        document.exitFullscreen().catch(function(){});
                    }
                    var playerEl = document.querySelector('#movie_player, ytm-player, .html5-video-player');
                    if (playerEl) {
                        playerEl.style.position = '';
                        playerEl.style.top = '';
                        playerEl.style.left = '';
                        playerEl.style.width = '';
                        playerEl.style.height = '';
                        playerEl.style.zIndex = '';
                    }
                    var v = document.querySelector('video');
                    if (v) {
                        v.style.position = '';
                        v.style.top = '';
                        v.style.left = '';
                        v.style.width = '';
                        v.style.height = '';
                        v.style.zIndex = '';
                    }
                })();
                """.trimIndent(),
                null
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exitNativeCustomView()
            activity?.let { act ->
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                val window = act.window
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    BackHandler {
        if (customViewHolder[0] != null || isLandscape) {
            isLandscape = false
        } else {
            onClose()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isLandscape) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .background(Color(0xFF111111))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f))
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.title,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (adBlockEnabled) "AdBlock Active ($blockedAdsCount blocked)" else "AdBlock Off",
                            color = if (adBlockEnabled) Color(0xFF4ADE80) else Color.Gray,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Surface(
                        onClick = { adBlockEnabled = !adBlockEnabled },
                        shape = RoundedCornerShape(50),
                        color = if (adBlockEnabled) Color(0xFF14532D) else Color.White.copy(alpha = 0.12f),
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .height(32.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = "AdBlocker",
                                tint = if (adBlockEnabled) Color(0xFF4ADE80) else Color.LightGray,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = if (adBlockEnabled) "AdBlock" else "Ads On",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (hasNextEpisode) {
                        Button(
                            onClick = onNextEpisode,
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 4.dp),
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Text("Next Ep", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Surface(
                        onClick = { isLandscape = true },
                        shape = RoundedCornerShape(50),
                        color = Color(0xFFE50914),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.ScreenRotation, contentDescription = "Horizontal", tint = Color.White, modifier = Modifier.size(15.dp))
                            Text(
                                text = "Horizontal",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef = this
                        setBackgroundColor(android.graphics.Color.BLACK)
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            javaScriptCanOpenWindowsAutomatically = false
                            setSupportMultipleWindows(false)
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            cacheMode = WebSettings.LOAD_DEFAULT
                            userAgentString = "Mozilla/5.0 (Linux; Android 14; RMX3393) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                                if (view == null) return
                                if (customViewHolder[0] != null) {
                                    callback?.onCustomViewHidden()
                                    return
                                }
                                customViewHolder[0] = view
                                customCallbackHolder[0] = callback
                                val decorView = activity?.window?.decorView as? FrameLayout
                                decorView?.addView(
                                    view,
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                )
                                isLandscape = true
                            }

                            override fun onHideCustomView() {
                                exitNativeCustomView()
                                isLandscape = false
                            }
                        }

                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): WebResourceResponse? {
                                if (adBlockEnabled && WebAdBlocker.isAdRequest(request?.url)) {
                                    blockedAdsCount++
                                    return WebAdBlocker.emptyResponse()
                                }
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val reqUri = request?.url ?: return false
                                val reqUrl = reqUri.toString()
                                if (adBlockEnabled && WebAdBlocker.isAdRequest(reqUri)) {
                                    blockedAdsCount++
                                    return true
                                }
                                return !(reqUrl.startsWith("http://") || reqUrl.startsWith("https://"))
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (adBlockEnabled) {
                                    view?.evaluateJavascript(WebAdBlocker.COSMETIC_ADBLOCK_JS, null)
                                }
                                view?.evaluateJavascript(
                                    "(function(){var v=document.querySelector('video');if(v){v.muted=false;v.play().catch(function(){});}})();",
                                    null
                                )
                            }
                        }
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        loadUrl(playableUrl)
                    }
                },
                update = { webView ->
                    webViewRef = webView
                    if (webView.url != playableUrl) {
                        webView.loadUrl(playableUrl)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }

        if (isLandscape) {
            Surface(
                onClick = { isLandscape = false },
                shape = RoundedCornerShape(50),
                color = Color(0xFFE50914).copy(alpha = 0.88f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .height(34.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(Icons.Default.ScreenRotation, contentDescription = "Vertical", tint = Color.White, modifier = Modifier.size(16.dp))
                    Text(
                        text = "Vertical",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun convertToEmbedUrl(rawUrl: String): String {
    val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return rawUrl
    val host = uri.host?.lowercase() ?: return rawUrl
    val path = uri.path ?: ""

    val youtubeId: String? = when {
        host.contains("youtu.be") -> path.trimStart('/').substringBefore('/')
        host.contains("youtube.com") && path.startsWith("/watch") -> uri.getQueryParameter("v")
        host.contains("youtube.com") && path.startsWith("/shorts/") -> path.removePrefix("/shorts/").substringBefore('/')
        host.contains("youtube.com") && path.startsWith("/live/") -> path.removePrefix("/live/").substringBefore('/')
        host.contains("youtube.com") && path.startsWith("/embed/") -> path.removePrefix("/embed/").substringBefore('/')
        else -> null
    }

    if (!youtubeId.isNullOrBlank()) {
        return "https://m.youtube.com/watch?v=$youtubeId"
    }

    return rawUrl
}

@Composable
private fun AudioAndSubtitleOverlay(
    audioTracks: List<MediaTrackOption>,
    subtitleTracks: List<MediaTrackOption>,
    subtitlesDisabled: Boolean,
    onSelectAudio: (MediaTrackOption) -> Unit,
    onSelectSubtitle: (MediaTrackOption) -> Unit,
    onDisableSubtitles: () -> Unit,
    onLoadExternalSubtitle: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.86f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = Color(0xFF181818),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.82f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Audio & Subtitles",
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "AUDIO",
                            color = Color(0xFFE50914),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (audioTracks.isEmpty()) {
                            Text(
                                text = "Default Audio",
                                color = Color.LightGray,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        } else {
                            audioTracks.forEach { track ->
                                TrackOptionRow(
                                    label = track.label,
                                    selected = track.isSelected,
                                    onClick = { onSelectAudio(track) }
                                )
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = "SUBTITLES",
                            color = Color(0xFFE50914),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.1.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        TrackOptionRow(
                            label = "Off",
                            selected = subtitlesDisabled || subtitleTracks.none { it.isSelected },
                            onClick = onDisableSubtitles
                        )

                        subtitleTracks.forEach { track ->
                            TrackOptionRow(
                                label = track.label,
                                selected = !subtitlesDisabled && track.isSelected,
                                onClick = { onSelectSubtitle(track) }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = onLoadExternalSubtitle,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Load .SRT / .VTT File",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = if (selected) Color(0xFFE50914) else Color.Transparent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            color = if (selected) Color.White else Color.LightGray,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun buildAudioTrackLabel(format: Format, fallbackIndex: Int): String {
    val langName = format.language
        ?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { code ->
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
                .replaceFirstChar { it.uppercase() }
        }
    val titleLabel = format.label?.takeIf { it.isNotBlank() }
    val channelInfo = when (format.channelCount) {
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }

    val base = when {
        titleLabel != null && langName != null && !titleLabel.contains(langName, ignoreCase = true) ->
            "$langName ($titleLabel)"
        titleLabel != null -> titleLabel
        langName != null -> langName
        else -> "Audio Track $fallbackIndex"
    }
    return if (channelInfo != null) "$base • $channelInfo" else base
}

private fun buildSubtitleTrackLabel(format: Format, fallbackIndex: Int): String {
    val langName = format.language
        ?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { code ->
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
                .replaceFirstChar { it.uppercase() }
        }
    val titleLabel = format.label?.takeIf { it.isNotBlank() }
    return when {
        titleLabel != null && langName != null && !titleLabel.contains(langName, ignoreCase = true) ->
            "$langName ($titleLabel)"
        titleLabel != null -> titleLabel
        langName != null -> langName
        else -> "Subtitle Track $fallbackIndex"
    }
}

private fun detectSubtitleMimeType(context: Context, uri: Uri): String {
    val name = uri.lastPathSegment?.lowercase() ?: ""
    val type = context.contentResolver.getType(uri)?.lowercase() ?: ""
    return when {
        name.endsWith(".vtt") || type.contains("vtt") -> MimeTypes.TEXT_VTT
        name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
        name.endsWith(".ttml") || name.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
