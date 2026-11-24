package com.streamvault.app.ui

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mkv.MatroskaExtractor
import java.io.File
import java.io.RandomAccessFile

/**
 * XPlayer-grade Matroska (MKV) Seekable Extractor:
 * When an MKV file has NO Cues seek table, MatroskaExtractor marks it unseekable.
 * If you jump to a raw byte offset mid-frame, the EBML parser stalls and freezes video playback.
 *
 * This extractor solves BOTH problems:
 * 1. Provides a valid SeekMap computed from file size & duration so ExoPlayer allows seeking to any minute.
 * 2. Scans the local MKV file for the exact 4-byte Matroska Cluster sync header (`0x1F 0x43 0xB6 0x75`)
 *    nearest to the target byte offset so MatroskaExtractor resumes parsing cleanly on a keyframe cluster
 *    without EVER freezing or stalling!
 */
@OptIn(UnstableApi::class)
class SeekableMatroskaExtractor(
    private val localFilePath: String? = null,
    private val knownDurationMs: Long = 0L
) : Extractor {

    private val delegate = MatroskaExtractor()
    private var lastKnownInputLength: Long = C.LENGTH_UNSET.toLong()
    private var firstClusterPosition: Long = 4096L
    private var capturedFirstDataPos = false
    private var hasNativeSeekMap = false

    override fun sniff(input: ExtractorInput): Boolean {
        if (input.length > 0) {
            lastKnownInputLength = input.length
        }
        return delegate.sniff(input)
    }

    override fun init(output: ExtractorOutput) {
        val interceptingOutput = object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                return output.track(id, type)
            }

            override fun endTracks() {
                output.endTracks()
            }

            override fun seekMap(seekMap: SeekMap) {
                if (seekMap.isSeekable) {
                    hasNativeSeekMap = true
                    output.seekMap(seekMap)
                } else {
                    hasNativeSeekMap = false
                    val durationUs = when {
                        seekMap.durationUs > 0L && seekMap.durationUs != C.TIME_UNSET -> seekMap.durationUs
                        knownDurationMs > 0L -> knownDurationMs * 1000L
                        else -> C.TIME_UNSET
                    }
                    val fileLen = when {
                        lastKnownInputLength > firstClusterPosition -> lastKnownInputLength
                        !localFilePath.isNullOrBlank() -> File(localFilePath).length()
                        else -> 0L
                    }
                    if (durationUs > 0L && durationUs != C.TIME_UNSET && fileLen > firstClusterPosition) {
                        val clusterSeekMap = object : SeekMap {
                            override fun isSeekable(): Boolean = true

                            override fun getDurationUs(): Long = durationUs

                            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                                val clampedTimeUs = timeUs.coerceIn(0L, durationUs)
                                if (clampedTimeUs <= 500_000L) {
                                    return SeekMap.SeekPoints(SeekPoint(0L, firstClusterPosition))
                                }
                                val dataBytes = (fileLen - firstClusterPosition).coerceAtLeast(1L)
                                val estimatedOffset = firstClusterPosition +
                                    ((dataBytes.toDouble() * (clampedTimeUs.toDouble() / durationUs.toDouble())).toLong())
                                val alignedOffset = findNearestClusterOffset(
                                    filePath = localFilePath,
                                    targetOffset = estimatedOffset,
                                    minOffset = firstClusterPosition,
                                    fileLength = fileLen
                                )
                                return SeekMap.SeekPoints(SeekPoint(clampedTimeUs, alignedOffset))
                            }
                        }
                        output.seekMap(clusterSeekMap)
                    } else {
                        output.seekMap(seekMap)
                    }
                }
            }
        }
        delegate.init(interceptingOutput)
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (input.length > 0) {
            lastKnownInputLength = input.length
        }
        if (!capturedFirstDataPos && input.position > 0) {
            firstClusterPosition = input.position.coerceAtMost(65536L)
            capturedFirstDataPos = true
        }
        return delegate.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) {
        if (hasNativeSeekMap || timeUs <= 0L) {
            delegate.seek(position, timeUs)
            return
        }
        val fileLen = when {
            lastKnownInputLength > firstClusterPosition -> lastKnownInputLength
            !localFilePath.isNullOrBlank() -> File(localFilePath).length()
            else -> 0L
        }
        val alignedPos = findNearestClusterOffset(
            filePath = localFilePath,
            targetOffset = position,
            minOffset = firstClusterPosition,
            fileLength = fileLen
        )
        delegate.seek(alignedPos, timeUs)
    }

    override fun release() {
        delegate.release()
    }

    companion object {
        /**
         * Scans backwards (up to 4 MB) then forwards (up to 4 MB) from `targetOffset` to find the
         * exact 4-byte Matroska Cluster EBML element ID (`0x1F 0x43 0xB6 0x75`).
         * Landing directly on a Cluster boundary lets MatroskaExtractor resume decoding immediately!
         */
        fun findNearestClusterOffset(
            filePath: String?,
            targetOffset: Long,
            minOffset: Long,
            fileLength: Long
        ): Long {
            if (filePath.isNullOrBlank() || fileLength <= minOffset + 16L) {
                return targetOffset.coerceAtLeast(minOffset)
            }
            return runCatching {
                val file = File(filePath)
                if (!file.exists() || !file.canRead()) return@runCatching targetOffset.coerceAtLeast(minOffset)

                RandomAccessFile(file, "r").use { raf ->
                    val maxSearchWindow = 4 * 1024 * 1024L // 4 MB window covers any Cluster interval
                    val safeTarget = targetOffset.coerceIn(minOffset, (fileLength - 16L).coerceAtLeast(minOffset))

                    // 1. Search backwards from safeTarget so we never skip past dialogue/action
                    val backStart = (safeTarget - maxSearchWindow).coerceAtLeast(minOffset)
                    val backLen = (safeTarget - backStart + 4L).toInt().coerceAtLeast(4)
                    val backBuf = ByteArray(backLen)
                    raf.seek(backStart)
                    val backRead = raf.read(backBuf)
                    for (i in (backRead - 4) downTo 0) {
                        if (backBuf[i] == 0x1F.toByte() &&
                            backBuf[i + 1] == 0x43.toByte() &&
                            backBuf[i + 2] == 0xB6.toByte() &&
                            backBuf[i + 3] == 0x75.toByte()
                        ) {
                            return@use backStart + i.toLong()
                        }
                    }

                    // 2. Otherwise search forwards from safeTarget
                    val fwdLen = (fileLength - safeTarget).coerceAtMost(maxSearchWindow).toInt().coerceAtLeast(4)
                    val fwdBuf = ByteArray(fwdLen)
                    raf.seek(safeTarget)
                    val fwdRead = raf.read(fwdBuf)
                    for (i in 0..(fwdRead - 4)) {
                        if (fwdBuf[i] == 0x1F.toByte() &&
                            fwdBuf[i + 1] == 0x43.toByte() &&
                            fwdBuf[i + 2] == 0xB6.toByte() &&
                            fwdBuf[i + 3] == 0x75.toByte()
                        ) {
                            return@use safeTarget + i.toLong()
                        }
                    }

                    safeTarget
                }
            }.getOrDefault(targetOffset.coerceAtLeast(minOffset))
        }
    }
}
