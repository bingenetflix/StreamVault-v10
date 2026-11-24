package com.streamvault.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.data.MediaRepository
import com.streamvault.app.model.EpisodeItem
import com.streamvault.app.model.GalleryPhoto
import com.streamvault.app.model.GalleryVideo
import com.streamvault.app.model.LastWatchedState
import com.streamvault.app.model.WebSeries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class PlayerSession(
    val title: String,
    val subtitle: String,
    val videoUri: Uri,
    val webPageUrl: String? = null,
    val seriesId: String? = null,
    val episodeId: String? = null,
    val episodeNumber: Int = 1,
    val episodeTitle: String = "",
    val episodeIndex: Int = 0,
    val startPositionMs: Long = 0L
)

data class StreamVaultUiState(
    val videos: List<GalleryVideo> = emptyList(),
    val photos: List<GalleryPhoto> = emptyList(),
    val seriesList: List<WebSeries> = emptyList(),
    val lastWatched: LastWatchedState? = null,
    val isLoadingMedia: Boolean = false,
    val activePlayer: PlayerSession? = null,
    val activePhotoIndex: Int? = null,
    val selectedSeriesForDetail: WebSeries? = null,
    val isDownloadingEpisode: Boolean = false,
    val downloadProgress: Float = 0f,
    val statusMessage: String? = null
)

class StreamVaultViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application.applicationContext)

    private val _uiState = MutableStateFlow(StreamVaultUiState())
    val uiState: StateFlow<StreamVaultUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val savedSeries = repository.loadSavedSeries()
            val savedHistory = repository.loadLastWatched()
            val validHistory = savedHistory?.takeIf { hist ->
                savedSeries.any { s -> s.id == hist.seriesId && s.episodes.any { it.id == hist.episodeId } }
            }
            _uiState.update {
                it.copy(
                    seriesList = savedSeries,
                    lastWatched = validHistory
                )
            }
        }
    }

    fun refreshGallery() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMedia = true) }
            val vids = repository.loadGalleryVideos()
            val pics = repository.loadGalleryPhotos()
            _uiState.update {
                it.copy(
                    videos = vids,
                    photos = pics,
                    isLoadingMedia = false
                )
            }
        }
    }

    fun playGalleryVideo(video: GalleryVideo) {
        _uiState.update {
            it.copy(
                activePlayer = PlayerSession(
                    title = video.title,
                    subtitle = "Phone Gallery • ${video.durationLabel}",
                    videoUri = video.uri
                )
            )
        }
    }

    fun playSeriesEpisode(series: WebSeries, episodeIndex: Int, resumePosMs: Long = 0L) {
        val episode = series.episodes.getOrNull(episodeIndex) ?: return
        val isWebPage = episode.webStreamUrl.isNotBlank() && !isDirectMediaStreamUrl(episode.webStreamUrl)
        val uri = when {
            episode.localFilePath.isNotBlank() -> Uri.fromFile(File(episode.localFilePath))
            episode.webStreamUrl.isNotBlank() -> Uri.parse(episode.webStreamUrl)
            else -> return
        }

        val newWatchState = LastWatchedState(
            seriesId = series.id,
            seriesTitle = series.title,
            episodeId = episode.id,
            episodeNumber = episode.episodeNumber,
            episodeTitle = episode.title,
            positionMs = resumePosMs,
            durationMs = _uiState.value.lastWatched?.takeIf { it.episodeId == episode.id }?.durationMs ?: 0L,
            posterUriString = series.posterUriString
        )

        viewModelScope.launch {
            repository.saveLastWatched(newWatchState)
        }

        _uiState.update {
            it.copy(
                lastWatched = newWatchState,
                activePlayer = PlayerSession(
                    title = "E${episode.episodeNumber}: ${episode.title}",
                    subtitle = series.title,
                    videoUri = uri,
                    webPageUrl = if (isWebPage) episode.webStreamUrl else null,
                    seriesId = series.id,
                    episodeId = episode.id,
                    episodeNumber = episode.episodeNumber,
                    episodeTitle = episode.title,
                    episodeIndex = episodeIndex,
                    startPositionMs = resumePosMs
                )
            )
        }
    }

    fun resumeLastWatched() {
        val history = _uiState.value.lastWatched ?: return
        val series = _uiState.value.seriesList.find { it.id == history.seriesId } ?: return
        val epIdx = series.episodes.indexOfFirst { it.id == history.episodeId }.coerceAtLeast(0)
        playSeriesEpisode(series, epIdx, resumePosMs = history.positionMs)
    }

    fun updatePlaybackProgress(positionMs: Long, durationMs: Long) {
        val currentSession = _uiState.value.activePlayer ?: return
        val sId = currentSession.seriesId ?: return
        val epId = currentSession.episodeId ?: return
        val series = _uiState.value.seriesList.find { it.id == sId } ?: return

        val updatedState = LastWatchedState(
            seriesId = series.id,
            seriesTitle = series.title,
            episodeId = epId,
            episodeNumber = currentSession.episodeNumber,
            episodeTitle = currentSession.episodeTitle,
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            posterUriString = series.posterUriString
        )
        _uiState.update { it.copy(lastWatched = updatedState) }
        viewModelScope.launch {
            repository.saveLastWatched(updatedState)
        }
    }

    fun playNextEpisode() {
        val current = _uiState.value.activePlayer ?: return
        val sId = current.seriesId ?: return
        val series = _uiState.value.seriesList.find { it.id == sId } ?: return
        val nextIndex = current.episodeIndex + 1
        if (nextIndex < series.episodes.size) {
            playSeriesEpisode(series, nextIndex, resumePosMs = 0L)
        }
    }

    fun hasNextEpisode(): Boolean {
        val current = _uiState.value.activePlayer ?: return false
        val sId = current.seriesId ?: return false
        val series = _uiState.value.seriesList.find { it.id == sId } ?: return false
        return (current.episodeIndex + 1) < series.episodes.size
    }

    fun closePlayer() {
        _uiState.update { it.copy(activePlayer = null) }
    }

    fun openPhotoViewer(index: Int) {
        _uiState.update { it.copy(activePhotoIndex = index) }
    }

    fun closePhotoViewer() {
        _uiState.update { it.copy(activePhotoIndex = null) }
    }

    fun openSeriesDetail(series: WebSeries) {
        _uiState.update { it.copy(selectedSeriesForDetail = series) }
    }

    fun closeSeriesDetail() {
        _uiState.update { it.copy(selectedSeriesForDetail = null) }
    }

    fun createNewWebSeries(
        title: String,
        genre: String,
        synopsis: String,
        posterUri: Uri?,
        accentHex: Long
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val savedPoster = posterUri?.let { repository.copyPosterToPrivateStorage(it) }
            val newSeries = WebSeries(
                id = UUID.randomUUID().toString(),
                title = title.trim(),
                genre = genre.ifBlank { "Original Series" }.trim(),
                synopsis = synopsis.ifBlank { "Custom OTT series stored on your Realme 9 Pro+." }.trim(),
                posterUriString = savedPoster,
                accentColorHex = accentHex,
                episodes = emptyList()
            )
            val updated = listOf(newSeries) + _uiState.value.seriesList
            repository.saveAllSeries(updated)
            _uiState.update {
                it.copy(
                    seriesList = updated,
                    selectedSeriesForDetail = newSeries,
                    statusMessage = "Created '${newSeries.title}'"
                )
            }
        }
    }

    fun addEpisodeFromUrl(
        seriesId: String,
        episodeNumber: Int,
        episodeTitle: String,
        description: String,
        rawUrl: String
    ) {
        val cleanUrl = normalizeUrl(rawUrl)
        if (cleanUrl.isBlank()) return

        viewModelScope.launch {
            if (!isDirectMediaStreamUrl(cleanUrl)) {
                attachEpisodeToSeries(
                    seriesId = seriesId,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    description = description.ifBlank { "Web Video • $cleanUrl" },
                    localPath = "",
                    webStreamUrl = cleanUrl,
                    isDownloaded = false
                )
                _uiState.update {
                    it.copy(statusMessage = "Added Web Video Episode $episodeNumber!")
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isDownloadingEpisode = true,
                    downloadProgress = 0f,
                    statusMessage = "Downloading episode offline..."
                )
            }
            val result = repository.downloadDirectVideoToSeries(cleanUrl) { progress ->
                _uiState.update { it.copy(downloadProgress = progress) }
            }
            result.onSuccess { localPath ->
                attachEpisodeToSeries(
                    seriesId = seriesId,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    description = description,
                    localPath = localPath,
                    webStreamUrl = "",
                    isDownloaded = true
                )
                _uiState.update {
                    it.copy(
                        isDownloadingEpisode = false,
                        downloadProgress = 1f,
                        statusMessage = "Episode $episodeNumber saved offline!"
                    )
                }
            }.onFailure {
                attachEpisodeToSeries(
                    seriesId = seriesId,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    description = description.ifBlank { "Online Stream • $cleanUrl" },
                    localPath = "",
                    webStreamUrl = cleanUrl,
                    isDownloaded = false
                )
                _uiState.update {
                    it.copy(
                        isDownloadingEpisode = false,
                        downloadProgress = 0f,
                        statusMessage = "Saved as online stream episode!"
                    )
                }
            }
        }
    }

    fun addEpisodeFromGalleryUri(
        seriesId: String,
        episodeNumber: Int,
        episodeTitle: String,
        description: String,
        sourceUri: Uri
    ) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDownloadingEpisode = true,
                    downloadProgress = -1f,
                    statusMessage = "Importing video into Web Series..."
                )
            }
            val localPath = repository.copyLocalVideoToSeries(sourceUri)
            if (localPath != null) {
                attachEpisodeToSeries(
                    seriesId = seriesId,
                    episodeNumber = episodeNumber,
                    episodeTitle = episodeTitle,
                    description = description,
                    localPath = localPath,
                    webStreamUrl = "",
                    isDownloaded = true
                )
                _uiState.update {
                    it.copy(
                        isDownloadingEpisode = false,
                        statusMessage = "Episode $episodeNumber added!"
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        isDownloadingEpisode = false,
                        statusMessage = "Could not import selected video"
                    )
                }
            }
        }
    }

    private suspend fun attachEpisodeToSeries(
        seriesId: String,
        episodeNumber: Int,
        episodeTitle: String,
        description: String,
        localPath: String,
        webStreamUrl: String,
        isDownloaded: Boolean
    ) {
        val newEp = EpisodeItem(
            id = UUID.randomUUID().toString(),
            episodeNumber = episodeNumber,
            title = episodeTitle.ifBlank { "Episode $episodeNumber" }.trim(),
            description = description.ifBlank { if (isDownloaded) "Offline HD Episode" else "Web Stream Episode" }.trim(),
            localFilePath = localPath,
            webStreamUrl = webStreamUrl,
            isDownloaded = isDownloaded
        )
        val updatedList = _uiState.value.seriesList.map { series ->
            if (series.id == seriesId) {
                series.copy(episodes = (series.episodes + newEp).sortedBy { it.episodeNumber })
            } else {
                series
            }
        }
        repository.saveAllSeries(updatedList)
        val updatedSelected = updatedList.find { it.id == seriesId }
        _uiState.update {
            it.copy(
                seriesList = updatedList,
                selectedSeriesForDetail = updatedSelected
            )
        }
    }

    fun deleteEpisode(seriesId: String, episode: EpisodeItem) {
        viewModelScope.launch {
            repository.deleteEpisodeFile(episode.localFilePath)
            val updatedList = _uiState.value.seriesList.map { series ->
                if (series.id == seriesId) {
                    series.copy(episodes = series.episodes.filterNot { it.id == episode.id })
                } else {
                    series
                }
            }
            repository.saveAllSeries(updatedList)
            val clearHistory = _uiState.value.lastWatched?.episodeId == episode.id
            if (clearHistory) {
                repository.saveLastWatched(null)
            }
            val updatedSelected = updatedList.find { it.id == seriesId }
            _uiState.update {
                it.copy(
                    seriesList = updatedList,
                    selectedSeriesForDetail = updatedSelected,
                    lastWatched = if (clearHistory) null else it.lastWatched
                )
            }
        }
    }

    fun deleteSeries(series: WebSeries) {
        viewModelScope.launch {
            series.episodes.forEach { repository.deleteEpisodeFile(it.localFilePath) }
            val updatedList = _uiState.value.seriesList.filterNot { it.id == series.id }
            repository.saveAllSeries(updatedList)
            val clearHistory = _uiState.value.lastWatched?.seriesId == series.id
            if (clearHistory) {
                repository.saveLastWatched(null)
            }
            _uiState.update {
                it.copy(
                    seriesList = updatedList,
                    selectedSeriesForDetail = null,
                    lastWatched = if (clearHistory) null else it.lastWatched,
                    statusMessage = "Deleted '${series.title}'"
                )
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    companion object {
        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return ""
            return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }

        fun isDirectMediaStreamUrl(url: String): Boolean {
            val lower = url.lowercase().substringBefore('?')
            return lower.endsWith(".mp4") ||
                lower.endsWith(".mkv") ||
                lower.endsWith(".webm") ||
                lower.endsWith(".m3u8") ||
                lower.endsWith(".mov") ||
                lower.endsWith(".avi")
        }

        fun formatDuration(ms: Long): String {
            if (ms <= 0L) return "00:00"
            val totalSeconds = ms / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
        }
    }
}
