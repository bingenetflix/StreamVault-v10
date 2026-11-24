package com.streamvault.app.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ImageRequest
import com.streamvault.app.model.EpisodeItem
import com.streamvault.app.model.GalleryPhoto
import com.streamvault.app.model.GalleryVideo
import com.streamvault.app.model.LastWatchedState
import com.streamvault.app.model.WebSeries
import com.streamvault.app.viewmodel.StreamVaultViewModel
import java.io.File

private val NetflixBlack = Color(0xFF0A0A0A)
private val NetflixCard = Color(0xFF161616)
private val NetflixRed = Color(0xFFE50914)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreamVaultAppScreen(viewModel: StreamVaultViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val mediaImageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.28)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "streamvault_thumbs"))
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .allowHardware(true)
            .build()
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var showCreateSeriesSheet by remember { mutableStateOf(false) }

    // Step-by-step Back navigation:
    // 1. Close Create Series Dialog if open
    // 2. If on Photos (tab 1) or Web Series (tab 2), return to Home (tab 0) instead of exiting app!
    BackHandler(enabled = showCreateSeriesSheet || selectedTab != 0) {
        when {
            showCreateSeriesSheet -> showCreateSeriesSheet = false
            selectedTab != 0 -> selectedTab = 0
        }
    }

    val permissionsToRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshGallery()
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(permissionsToRequest)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NetflixBlack)
    ) {
        Scaffold(
            containerColor = NetflixBlack,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                StreamVaultTopBar(
                    selectedTab = selectedTab,
                    onRefresh = { viewModel.refreshGallery() },
                    onNewSeriesClick = { showCreateSeriesSheet = true }
                )
            },
            bottomBar = {
                NavigationBar(
                    containerColor = Color(0xFF111111),
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NetflixRed,
                            selectedTextColor = Color.White,
                            indicatorColor = NetflixRed.copy(alpha = 0.16f),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = "Photos") },
                        label = { Text("Photos (${uiState.photos.size})") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NetflixRed,
                            selectedTextColor = Color.White,
                            indicatorColor = NetflixRed.copy(alpha = 0.16f),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        )
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.MovieFilter, contentDescription = "Web Series") },
                        label = { Text("Web Series") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = NetflixRed,
                            selectedTextColor = Color.White,
                            indicatorColor = NetflixRed.copy(alpha = 0.16f),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        )
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (selectedTab) {
                    0 -> HomeAndVideosTab(
                        lastWatched = uiState.lastWatched,
                        seriesList = uiState.seriesList,
                        videos = uiState.videos,
                        isLoading = uiState.isLoadingMedia,
                        imageLoader = mediaImageLoader,
                        onResumeLastWatched = { viewModel.resumeLastWatched() },
                        onSeriesClick = { viewModel.openSeriesDetail(it) },
                        onCreateSeriesClick = { showCreateSeriesSheet = true },
                        onRequestPermission = { permissionLauncher.launch(permissionsToRequest) },
                        onVideoClick = { viewModel.playGalleryVideo(it) }
                    )
                    1 -> GalleryPhotosTab(
                        photos = uiState.photos,
                        isLoading = uiState.isLoadingMedia,
                        imageLoader = mediaImageLoader,
                        onRequestPermission = { permissionLauncher.launch(permissionsToRequest) },
                        onPhotoClick = { index -> viewModel.openPhotoViewer(index) }
                    )
                    2 -> WebSeriesStudioTab(
                        seriesList = uiState.seriesList,
                        imageLoader = mediaImageLoader,
                        onCreateSeriesClick = { showCreateSeriesSheet = true },
                        onSeriesClick = { viewModel.openSeriesDetail(it) }
                    )
                }
            }
        }

        uiState.selectedSeriesForDetail?.let { selectedSeries ->
            SeriesDetailOverlay(
                series = selectedSeries,
                imageLoader = mediaImageLoader,
                isDownloading = uiState.isDownloadingEpisode,
                downloadProgress = uiState.downloadProgress,
                onBack = { viewModel.closeSeriesDetail() },
                onPlayEpisode = { idx -> viewModel.playSeriesEpisode(selectedSeries, idx) },
                onAddEpisodeUrl = { epNum, title, desc, url ->
                    viewModel.addEpisodeFromUrl(selectedSeries.id, epNum, title, desc, url)
                },
                onAddEpisodeGallery = { epNum, title, desc, uri ->
                    viewModel.addEpisodeFromGalleryUri(selectedSeries.id, epNum, title, desc, uri)
                },
                onDeleteEpisode = { ep -> viewModel.deleteEpisode(selectedSeries.id, ep) },
                onDeleteSeries = { viewModel.deleteSeries(selectedSeries) }
            )
        }

        uiState.activePhotoIndex?.let { photoIdx ->
            PhotoLightboxViewer(
                photos = uiState.photos,
                initialIndex = photoIdx,
                onClose = { viewModel.closePhotoViewer() }
            )
        }

        uiState.activePlayer?.let { session ->
            NetflixVideoPlayer(
                session = session,
                hasNextEpisode = viewModel.hasNextEpisode(),
                onProgressSaved = { pos, dur -> viewModel.updatePlaybackProgress(pos, dur) },
                onNextEpisode = { viewModel.playNextEpisode() },
                onClose = { viewModel.closePlayer() }
            )
        }

        if (showCreateSeriesSheet) {
            CreateWebSeriesDialog(
                onDismiss = { showCreateSeriesSheet = false },
                onCreate = { title, genre, synopsis, posterUri, accent ->
                    viewModel.createNewWebSeries(title, genre, synopsis, posterUri, accent)
                    showCreateSeriesSheet = false
                }
            )
        }
    }
}

@Composable
private fun StreamVaultTopBar(
    selectedTab: Int,
    onRefresh: () -> Unit,
    onNewSeriesClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(NetflixBlack)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "N",
                color = NetflixRed,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "STREAMVAULT",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.5.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selectedTab == 2) {
                Surface(
                    onClick = onNewSeriesClick,
                    shape = RoundedCornerShape(6.dp),
                    color = NetflixRed
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("New Series", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Gallery", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun HomeAndVideosTab(
    lastWatched: LastWatchedState?,
    seriesList: List<WebSeries>,
    videos: List<GalleryVideo>,
    isLoading: Boolean,
    imageLoader: ImageLoader,
    onResumeLastWatched: () -> Unit,
    onSeriesClick: (WebSeries) -> Unit,
    onCreateSeriesClick: () -> Unit,
    onRequestPermission: () -> Unit,
    onVideoClick: (GalleryVideo) -> Unit
) {
    val context = LocalContext.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(span = { GridItemSpan(2) }) {
            when {
                lastWatched != null -> {
                    val progressFraction = if (lastWatched.durationMs > 0L) {
                        (lastWatched.positionMs.toFloat() / lastWatched.durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(245.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(NetflixCard)
                            .clickable { onResumeLastWatched() }
                    ) {
                        if (lastWatched.posterUriString != null) {
                            AsyncImage(
                                model = Uri.parse(lastWatched.posterUriString),
                                imageLoader = imageLoader,
                                contentDescription = lastWatched.seriesTitle,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF4A070B), Color(0xFF141414))
                                        )
                                    )
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.94f))
                                    )
                                )
                        )

                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp)
                        ) {
                            Text(
                                text = "CONTINUE WATCHING • ${lastWatched.seriesTitle.uppercase()}",
                                color = NetflixRed,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.1.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Episode ${lastWatched.episodeNumber}: ${lastWatched.episodeTitle}",
                                color = Color.White,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (lastWatched.positionMs > 0L) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Stopped at ${StreamVaultViewModel.formatDuration(lastWatched.positionMs)}" +
                                        if (lastWatched.durationMs > 0L) " / ${StreamVaultViewModel.formatDuration(lastWatched.durationMs)}" else "",
                                    color = Color.LightGray,
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { progressFraction },
                                    color = NetflixRed,
                                    trackColor = Color.White.copy(alpha = 0.25f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { onResumeLastWatched() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (lastWatched.positionMs > 0L) "Resume Episode" else "Play Episode ${lastWatched.episodeNumber}",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                seriesList.isNotEmpty() -> {
                    val featuredSeries = seriesList.first()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(230.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(NetflixCard)
                            .clickable { onSeriesClick(featuredSeries) }
                    ) {
                        if (featuredSeries.posterUriString != null) {
                            AsyncImage(
                                model = Uri.parse(featuredSeries.posterUriString),
                                imageLoader = imageLoader,
                                contentDescription = featuredSeries.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Brush.linearGradient(listOf(Color(0xFF4A070B), Color(0xFF141414))))
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(16.dp)
                        ) {
                            Text("FEATURED WEB SERIES", color = NetflixRed, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            Text(featuredSeries.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { onSeriesClick(featuredSeries) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Watch Series", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                else -> {
                    Surface(
                        color = NetflixCard,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Text("STREAMVAULT OTT", color = NetflixRed, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("Start Your First Web Series", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                "Create a series and add episodes from your phone or YouTube/web links. Your last-watched episode will appear right here to resume.",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = onCreateSeriesClick,
                                colors = ButtonDefaults.buttonColors(containerColor = NetflixRed),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Create Web Series", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        if (seriesList.isNotEmpty()) {
            item(span = { GridItemSpan(2) }) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        text = "You Can Also Watch",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 10.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(seriesList, key = { it.id }) { series ->
                            Column(
                                modifier = Modifier
                                    .width(126.dp)
                                    .clickable { onSeriesClick(series) }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(126.dp)
                                        .height(176.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF221012)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (series.posterUriString != null) {
                                        AsyncImage(
                                            model = Uri.parse(series.posterUriString),
                                            imageLoader = imageLoader,
                                            contentDescription = series.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Text(
                                            text = series.title.take(2).uppercase(),
                                            color = Color.White,
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.Black
                                        )
                                    }
                                    Surface(
                                        color = Color.Black.copy(alpha = 0.78f),
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(6.dp)
                                    ) {
                                        Text(
                                            text = "${series.episodes.size} Ep",
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = series.title,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = series.genre,
                                    color = Color.Gray,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        item(span = { GridItemSpan(2) }) {
            Text(
                text = "Phone Gallery Videos (${videos.size})",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (isLoading && videos.isEmpty()) {
            item(span = { GridItemSpan(2) }) {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NetflixRed)
                }
            }
        } else if (videos.isEmpty()) {
            item(span = { GridItemSpan(2) }) {
                EmptyPermissionPrompt(
                    title = "No Gallery Videos Found",
                    subtitle = "Grant media access to browse your local videos.",
                    buttonLabel = "Allow Gallery Access",
                    onClick = onRequestPermission
                )
            }
        } else {
            items(videos, key = { it.id }, contentType = { "video_card" }) { video ->
                VideoPosterCard(
                    video = video,
                    imageLoader = imageLoader,
                    onClick = { onVideoClick(video) }
                )
            }
        }
    }
}

@Composable
private fun VideoPosterCard(
    video: GalleryVideo,
    imageLoader: ImageLoader,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val thumbRequest = remember(video.id) {
        ImageRequest.Builder(context)
            .data(video.uri)
            .size(360, 220)
            .memoryCacheKey("vid_${video.id}")
            .diskCacheKey("vid_${video.id}")
            .build()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(NetflixCard)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            AsyncImage(
                model = thumbRequest,
                imageLoader = imageLoader,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Surface(
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            ) {
                Text(
                    text = video.durationLabel,
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            text = video.title,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun GalleryPhotosTab(
    photos: List<GalleryPhoto>,
    isLoading: Boolean,
    imageLoader: ImageLoader,
    onRequestPermission: () -> Unit,
    onPhotoClick: (Int) -> Unit
) {
    val context = LocalContext.current

    if (isLoading && photos.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = NetflixRed)
        }
        return
    }

    if (photos.isEmpty()) {
        EmptyPermissionPrompt(
            title = "No Phone Gallery Photos Found",
            subtitle = "Allow gallery photo access to browse your camera roll and screenshots.",
            buttonLabel = "Allow Photos Access",
            onClick = onRequestPermission
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(photos, key = { _, item -> item.id }, contentType = { _, _ -> "photo_tile" }) { index, photo ->
            val photoRequest = remember(photo.id) {
                ImageRequest.Builder(context)
                    .data(photo.uri)
                    .size(280, 360)
                    .memoryCacheKey("pic_${photo.id}")
                    .diskCacheKey("pic_${photo.id}")
                    .build()
            }
            Box(
                modifier = Modifier
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NetflixCard)
                    .clickable { onPhotoClick(index) }
            ) {
                AsyncImage(
                    model = photoRequest,
                    imageLoader = imageLoader,
                    contentDescription = photo.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun WebSeriesStudioTab(
    seriesList: List<WebSeries>,
    imageLoader: ImageLoader,
    onCreateSeriesClick: () -> Unit,
    onSeriesClick: (WebSeries) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Surface(
                color = NetflixCard,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "YOUR PERSONAL OTT STUDIO",
                        color = NetflixRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Create Your Own Web Series",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Build custom series, add YouTube or website video links, or import videos from your phone gallery to binge episode-by-episode.",
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = onCreateSeriesClick,
                        colors = ButtonDefaults.buttonColors(containerColor = NetflixRed),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Create New Web Series", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (seriesList.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No custom web series yet. Tap '+ Create New Web Series' above to start.",
                        color = Color.Gray,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            item {
                Text(
                    text = "My Web Series (${seriesList.size})",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            items(seriesList, key = { it.id }) { series ->
                SeriesCatalogCard(series = series, imageLoader = imageLoader, onClick = { onSeriesClick(series) })
            }
        }
    }
}

@Composable
private fun SeriesCatalogCard(series: WebSeries, imageLoader: ImageLoader, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = NetflixCard,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(84.dp)
                    .height(116.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(series.accentColorHex).copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                if (series.posterUriString != null) {
                    AsyncImage(
                        model = Uri.parse(series.posterUriString),
                        imageLoader = imageLoader,
                        contentDescription = series.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = series.title.take(2).uppercase(),
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = series.genre.uppercase(),
                    color = NetflixRed,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = series.title,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = series.synopsis,
                    color = Color.LightGray,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = Color.White.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "${series.episodes.size} Episode${if (series.episodes.size == 1) "" else "s"} Ready",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesDetailOverlay(
    series: WebSeries,
    imageLoader: ImageLoader,
    isDownloading: Boolean,
    downloadProgress: Float,
    onBack: () -> Unit,
    onPlayEpisode: (Int) -> Unit,
    onAddEpisodeUrl: (Int, String, String, String) -> Unit,
    onAddEpisodeGallery: (Int, String, String, Uri) -> Unit,
    onDeleteEpisode: (EpisodeItem) -> Unit,
    onDeleteSeries: () -> Unit
) {
    var showAddEpisodeDialog by remember { mutableStateOf(false) }

    BackHandler {
        if (showAddEpisodeDialog) {
            showAddEpisodeDialog = false
        } else {
            onBack()
        }
    }

    Surface(
        color = NetflixBlack,
        modifier = Modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .background(Color(series.accentColorHex).copy(alpha = 0.3f))
                ) {
                    if (series.posterUriString != null) {
                        AsyncImage(
                            model = Uri.parse(series.posterUriString),
                            imageLoader = imageLoader,
                            contentDescription = series.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Black.copy(alpha = 0.45f), NetflixBlack)
                                )
                            )
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }

                        IconButton(
                            onClick = onDeleteSeries,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete Series", tint = Color.White)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp)
                    ) {
                        Text(series.genre.uppercase(), color = NetflixRed, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(series.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                        Text(series.synopsis, color = Color.LightGray, fontSize = 13.sp, maxLines = 2)
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { if (series.episodes.isNotEmpty()) onPlayEpisode(0) },
                            enabled = series.episodes.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Play S1:E1", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { showAddEpisodeDialog = true },
                            enabled = !isDownloading,
                            colors = ButtonDefaults.buttonColors(containerColor = NetflixRed, contentColor = Color.White),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Episode", fontWeight = FontWeight.Bold)
                        }
                    }

                    if (isDownloading) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Saving episode to offline vault...", color = Color.White, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(6.dp))
                        if (downloadProgress >= 0f) {
                            LinearProgressIndicator(
                                progress = { downloadProgress },
                                color = NetflixRed,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(
                                color = NetflixRed,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        text = "Season 1 Episodes (${series.episodes.size})",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (series.episodes.isEmpty()) {
                item {
                    Text(
                        text = "No episodes added yet. Tap '+ Add Episode' to paste a YouTube/website link or import from your gallery.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)
                    )
                }
            } else {
                itemsIndexed(series.episodes, key = { _, ep -> ep.id }) { idx, ep ->
                    Surface(
                        onClick = { onPlayEpisode(idx) },
                        color = NetflixCard,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 5.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(NetflixRed.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (ep.webStreamUrl.isNotBlank()) Icons.Default.Language else Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Episode ${ep.episodeNumber}: ${ep.title}",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = ep.description,
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { onDeleteEpisode(ep) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove Episode", tint = Color.Gray)
                            }
                        }
                    }
                }
            }
        }

        if (showAddEpisodeDialog) {
            AddEpisodeDialog(
                nextEpisodeNumber = (series.episodes.maxOfOrNull { it.episodeNumber } ?: 0) + 1,
                onDismiss = { showAddEpisodeDialog = false },
                onDownloadUrl = { epNum, title, desc, url ->
                    onAddEpisodeUrl(epNum, title, desc, url)
                    showAddEpisodeDialog = false
                },
                onImportGallery = { epNum, title, desc, uri ->
                    onAddEpisodeGallery(epNum, title, desc, uri)
                    showAddEpisodeDialog = false
                }
            )
        }
    }
}

@Composable
private fun CreateWebSeriesDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, Uri?, Long) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("Sci-Fi Thriller") }
    var synopsis by remember { mutableStateOf("") }
    var posterUri by remember { mutableStateOf<Uri?>(null) }

    val posterPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> posterUri = uri }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NetflixCard,
        title = { Text("Create New Web Series", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Series Title") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text("Genre") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = synopsis,
                    onValueChange = { synopsis = it },
                    label = { Text("Short Description") },
                    maxLines = 2,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                OutlinedButton(
                    onClick = {
                        posterPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (posterUri != null) "Cover Poster Selected" else "Pick Cover Poster from Gallery",
                        color = Color.White
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(title, genre, synopsis, posterUri, 0xFFE50914) },
                colors = ButtonDefaults.buttonColors(containerColor = NetflixRed)
            ) {
                Text("Create Series", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.LightGray)
            }
        }
    )
}

@Composable
private fun AddEpisodeDialog(
    nextEpisodeNumber: Int,
    onDismiss: () -> Unit,
    onDownloadUrl: (Int, String, String, String) -> Unit,
    onImportGallery: (Int, String, String, Uri) -> Unit
) {
    var epNumText by remember { mutableStateOf(nextEpisodeNumber.toString()) }
    var epTitle by remember { mutableStateOf("Episode $nextEpisodeNumber") }
    var epDesc by remember { mutableStateOf("") }
    var videoUrl by remember { mutableStateOf("") }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val num = epNumText.toIntOrNull() ?: nextEpisodeNumber
            onImportGallery(num, epTitle, epDesc, uri)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NetflixCard,
        title = { Text("Add Episode $nextEpisodeNumber", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = epNumText,
                    onValueChange = { epNumText = it },
                    label = { Text("Episode Number") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = epTitle,
                    onValueChange = { epTitle = it },
                    label = { Text("Episode Title") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = videoUrl,
                    onValueChange = { videoUrl = it },
                    label = { Text("YouTube, Website, or Direct MP4 Link") },
                    placeholder = { Text("https://youtu.be/... or https://.../ep1.mp4") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                OutlinedButton(
                    onClick = {
                        videoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Or Import Video from Phone Gallery", color = Color.White, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val num = epNumText.toIntOrNull() ?: nextEpisodeNumber
                    onDownloadUrl(num, epTitle, epDesc, videoUrl)
                },
                enabled = videoUrl.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = NetflixRed)
            ) {
                Text("Add Link Episode", color = Color.White, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.LightGray)
            }
        }
    )
}

@Composable
private fun EmptyPermissionPrompt(
    title: String,
    subtitle: String,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.PermMedia, contentDescription = null, tint = NetflixRed, modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(10.dp))
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Text(subtitle, color = Color.LightGray, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = NetflixRed)
        ) {
            Text(buttonLabel, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
