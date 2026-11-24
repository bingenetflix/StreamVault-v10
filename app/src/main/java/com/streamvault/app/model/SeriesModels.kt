package com.streamvault.app.model

import android.net.Uri
import androidx.compose.runtime.Immutable

@Immutable
data class GalleryVideo(
    val id: Long,
    val title: String,
    val uri: Uri,
    val durationMs: Long,
    val durationLabel: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val width: Int,
    val height: Int
)

@Immutable
data class GalleryPhoto(
    val id: Long,
    val title: String,
    val uri: Uri,
    val dateAddedSec: Long,
    val width: Int,
    val height: Int
)

@Immutable
data class EpisodeItem(
    val id: String,
    val episodeNumber: Int,
    val title: String,
    val description: String,
    val localFilePath: String,
    val webStreamUrl: String = "",
    val durationLabel: String = "HD",
    val isDownloaded: Boolean = true
)

@Immutable
data class WebSeries(
    val id: String,
    val title: String,
    val genre: String,
    val synopsis: String,
    val posterUriString: String?,
    val accentColorHex: Long,
    val episodes: List<EpisodeItem>
)

@Immutable
data class LastWatchedState(
    val seriesId: String,
    val seriesTitle: String,
    val episodeId: String,
    val episodeNumber: Int,
    val episodeTitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val posterUriString: String?
)
