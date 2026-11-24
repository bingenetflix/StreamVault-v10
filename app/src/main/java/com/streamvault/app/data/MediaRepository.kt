package com.streamvault.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.streamvault.app.model.EpisodeItem
import com.streamvault.app.model.GalleryPhoto
import com.streamvault.app.model.GalleryVideo
import com.streamvault.app.model.LastWatchedState
import com.streamvault.app.model.WebSeries
import com.streamvault.app.viewmodel.StreamVaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class MediaRepository(private val context: Context) {

    private val seriesDir: File by lazy {
        File(context.filesDir, "web_series_vault").apply { if (!exists()) mkdirs() }
    }

    private val dbFile: File by lazy {
        File(seriesDir, "series_catalog.json")
    }

    private val watchHistoryFile: File by lazy {
        File(seriesDir, "last_watched.json")
    }

    suspend fun loadLastWatched(): LastWatchedState? = withContext(Dispatchers.IO) {
        if (!watchHistoryFile.exists()) return@withContext null
        runCatching {
            val obj = JSONObject(watchHistoryFile.readText())
            LastWatchedState(
                seriesId = obj.getString("seriesId"),
                seriesTitle = obj.getString("seriesTitle"),
                episodeId = obj.getString("episodeId"),
                episodeNumber = obj.getInt("episodeNumber"),
                episodeTitle = obj.getString("episodeTitle"),
                positionMs = obj.optLong("positionMs", 0L),
                durationMs = obj.optLong("durationMs", 0L),
                posterUriString = obj.optString("posterUriString").takeIf { it.isNotBlank() && it != "null" }
            )
        }.getOrNull()
    }

    suspend fun saveLastWatched(state: LastWatchedState?) = withContext(Dispatchers.IO) {
        if (state == null) {
            if (watchHistoryFile.exists()) watchHistoryFile.delete()
            return@withContext
        }
        val obj = JSONObject().apply {
            put("seriesId", state.seriesId)
            put("seriesTitle", state.seriesTitle)
            put("episodeId", state.episodeId)
            put("episodeNumber", state.episodeNumber)
            put("episodeTitle", state.episodeTitle)
            put("positionMs", state.positionMs)
            put("durationMs", state.durationMs)
            put("posterUriString", state.posterUriString ?: "")
        }
        watchHistoryFile.writeText(obj.toString())
    }

    suspend fun loadGalleryVideos(): List<GalleryVideo> = withContext(Dispatchers.IO) {
        val list = mutableListOf<GalleryVideo>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val rawName = cursor.getString(nameCol) ?: "Video_$id"
                    val cleanTitle = rawName.substringBeforeLast('.')
                    val durMs = cursor.getLong(durCol)
                    list.add(
                        GalleryVideo(
                            id = id,
                            title = cleanTitle,
                            uri = uri,
                            durationMs = durMs,
                            durationLabel = StreamVaultViewModel.formatDuration(durMs),
                            sizeBytes = cursor.getLong(sizeCol),
                            dateAddedSec = cursor.getLong(dateCol),
                            width = cursor.getInt(wCol),
                            height = cursor.getInt(hCol)
                        )
                    )
                }
            }
        }
        list
    }

    suspend fun loadGalleryPhotos(): List<GalleryPhoto> = withContext(Dispatchers.IO) {
        val list = mutableListOf<GalleryPhoto>()
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        runCatching {
            context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val wCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val rawName = cursor.getString(nameCol) ?: "Photo_$id"
                    list.add(
                        GalleryPhoto(
                            id = id,
                            title = rawName.substringBeforeLast('.'),
                            uri = uri,
                            dateAddedSec = cursor.getLong(dateCol),
                            width = cursor.getInt(wCol),
                            height = cursor.getInt(hCol)
                        )
                    )
                }
            }
        }
        list
    }

    suspend fun loadSavedSeries(): List<WebSeries> = withContext(Dispatchers.IO) {
        if (!dbFile.exists()) return@withContext emptyList()
        runCatching {
            val root = JSONArray(dbFile.readText())
            val result = mutableListOf<WebSeries>()
            for (i in 0 until root.length()) {
                val obj = root.getJSONObject(i)
                val epsJson = obj.optJSONArray("episodes") ?: JSONArray()
                val episodes = mutableListOf<EpisodeItem>()
                for (j in 0 until epsJson.length()) {
                    val epObj = epsJson.getJSONObject(j)
                    episodes.add(
                        EpisodeItem(
                            id = epObj.getString("id"),
                            episodeNumber = epObj.getInt("episodeNumber"),
                            title = epObj.getString("title"),
                            description = epObj.optString("description", ""),
                            localFilePath = epObj.optString("localFilePath", ""),
                            webStreamUrl = epObj.optString("webStreamUrl", ""),
                            durationLabel = epObj.optString("durationLabel", "HD"),
                            isDownloaded = epObj.optBoolean("isDownloaded", true)
                        )
                    )
                }
                result.add(
                    WebSeries(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        genre = obj.optString("genre", "Drama"),
                        synopsis = obj.optString("synopsis", ""),
                        posterUriString = obj.optString("posterUriString").takeIf { it.isNotBlank() && it != "null" },
                        accentColorHex = obj.optLong("accentColorHex", 0xFFE50914),
                        episodes = episodes.sortedBy { it.episodeNumber }
                    )
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    suspend fun saveAllSeries(seriesList: List<WebSeries>) = withContext(Dispatchers.IO) {
        val root = JSONArray()
        seriesList.forEach { series ->
            val obj = JSONObject().apply {
                put("id", series.id)
                put("title", series.title)
                put("genre", series.genre)
                put("synopsis", series.synopsis)
                put("posterUriString", series.posterUriString ?: "")
                put("accentColorHex", series.accentColorHex)
                val epsArr = JSONArray()
                series.episodes.forEach { ep ->
                    epsArr.put(JSONObject().apply {
                        put("id", ep.id)
                        put("episodeNumber", ep.episodeNumber)
                        put("title", ep.title)
                        put("description", ep.description)
                        put("localFilePath", ep.localFilePath)
                        put("webStreamUrl", ep.webStreamUrl)
                        put("durationLabel", ep.durationLabel)
                        put("isDownloaded", ep.isDownloaded)
                    })
                }
                put("episodes", epsArr)
            }
            root.put(obj)
        }
        dbFile.writeText(root.toString())
    }

    suspend fun copyPosterToPrivateStorage(sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val destFile = File(seriesDir, "poster_${UUID.randomUUID()}.jpg")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            Uri.fromFile(destFile).toString()
        }.getOrNull()
    }

    suspend fun copyLocalVideoToSeries(sourceUri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val destFile = File(seriesDir, "ep_${UUID.randomUUID()}.mp4")
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 128 * 1024)
                }
            }
            destFile.absolutePath
        }.getOrNull()
    }

    suspend fun downloadDirectVideoToSeries(
        videoUrl: String,
        onProgress: (Float) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val destFile = File(seriesDir, "ep_${UUID.randomUUID()}.mp4")
            val url = URL(videoUrl.trim())
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
            }
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP Error $responseCode")
            }
            val contentType = connection.contentType?.lowercase() ?: ""
            if (contentType.contains("text/html")) {
                throw IllegalStateException("WEBPAGE_URL")
            }
            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L

            connection.inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            onProgress((downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
                        } else {
                            onProgress(-1f)
                        }
                    }
                }
            }
            destFile.absolutePath
        }
    }

    suspend fun deleteEpisodeFile(filePath: String) = withContext(Dispatchers.IO) {
        if (filePath.isBlank()) return@withContext
        runCatching {
            val file = File(filePath)
            if (file.exists() && file.absolutePath.startsWith(seriesDir.absolutePath)) {
                file.delete()
            }
        }
    }
}
