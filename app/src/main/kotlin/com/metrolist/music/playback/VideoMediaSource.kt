/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import com.metrolist.innertube.YouTube
import com.metrolist.music.utils.InnerTubeXPlayer
import okhttp3.OkHttpClient

/**
 * Some YouTube clients only serve bounded byte ranges. This wrapper keeps requesting the next
 * range until the stream (or the requested window) ends, so ExoPlayer sees one continuous source.
 */
@UnstableApi
internal class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long,
    private val totalLength: Long,
) : DataSource {
    private var baseSpec: DataSpec? = null
    private var nextPosition = 0L
    private var requestedEnd = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = 0L
    private var chunkOpen = false
    private var finished = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        nextPosition = dataSpec.position
        finished = false
        requestedEnd =
            when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.position + dataSpec.length
                totalLength > 0L -> totalLength
                else -> C.LENGTH_UNSET.toLong()
            }
        openNextChunk()
        return if (requestedEnd != C.LENGTH_UNSET.toLong()) requestedEnd - dataSpec.position else C.LENGTH_UNSET.toLong()
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        while (!finished) {
            if (!chunkOpen) {
                if (requestedEnd != C.LENGTH_UNSET.toLong() && nextPosition >= requestedEnd) {
                    finished = true
                    break
                }
                if (!openNextChunk()) break
            }
            val toRead = minOf(length.toLong(), chunkRemaining).toInt()
            val read = if (toRead > 0) upstream.read(buffer, offset, toRead) else C.RESULT_END_OF_INPUT
            if (read > 0) {
                nextPosition += read
                chunkRemaining -= read
                if (chunkRemaining <= 0L) closeChunk()
                return read
            }
            // Chunk ended: either fully consumed or the server had fewer bytes than asked for.
            val ranShort = chunkRemaining > 0L
            closeChunk()
            if (ranShort) finished = true
        }
        return C.RESULT_END_OF_INPUT
    }

    private fun openNextChunk(): Boolean {
        val base = baseSpec ?: return false
        val untilEnd = if (requestedEnd != C.LENGTH_UNSET.toLong()) requestedEnd - nextPosition else Long.MAX_VALUE
        val wanted = minOf(chunkSize, untilEnd)
        if (wanted <= 0L) {
            finished = true
            return false
        }
        val spec = base.buildUpon().setPosition(nextPosition).setLength(wanted).build()
        val opened =
            try {
                upstream.open(spec)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                // 416 = we asked past the end of the file, i.e. the stream is finished.
                if (e.responseCode == 416) {
                    finished = true
                    return false
                }
                throw e
            }
        chunkRemaining = if (opened == C.LENGTH_UNSET.toLong()) wanted else opened
        chunkOpen = true
        return true
    }

    private fun closeChunk() {
        if (chunkOpen) {
            chunkOpen = false
            upstream.close()
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeChunk()
        baseSpec = null
    }
}

@UnstableApi
internal fun buildVideoMediaSource(stream: InnerTubeXPlayer.VideoStreamData): MediaSource {
    val client =
        OkHttpClient
            .Builder()
            .proxy(YouTube.proxy)
            .proxyAuthenticator { _, response ->
                YouTube.proxyAuth?.let { auth ->
                    response.request
                        .newBuilder()
                        .header("Proxy-Authorization", auth)
                        .build()
                } ?: response.request
            }.build()

    val httpFactory =
        OkHttpDataSource
            .Factory(client)
            .setDefaultRequestProperties(stream.headers)

    if (stream.isHls) {
        return HlsMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(stream.url))
    }

    val chunked = (stream.requireBoundedRange || stream.useRangeChunks) && stream.rangeChunkSizeBytes > 0L
    val dataSourceFactory =
        if (chunked) {
            DataSource.Factory {
                ChunkedDataSource(
                    upstream = httpFactory.createDataSource(),
                    chunkSize = stream.rangeChunkSizeBytes,
                    totalLength = stream.contentLengthBytes ?: C.LENGTH_UNSET.toLong(),
                )
            }
        } else {
            httpFactory
        }

    return ProgressiveMediaSource
        .Factory(
            dataSourceFactory,
            ExtractorsFactory { arrayOf(MatroskaExtractor(), FragmentedMp4Extractor(), Mp4Extractor()) },
        ).createMediaSource(MediaItem.fromUri(stream.url))
}
