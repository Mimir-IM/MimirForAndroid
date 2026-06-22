package com.revertron.mimir.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Effects
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File

/**
 * Square-crops and recompresses a recorded video-message take.
 *
 * Center-crops the (rectangular) capture to a square and scales it to
 * [SIZE]x[SIZE], re-encoding video at [VIDEO_BITRATE] (H.264) so the on-the-wire
 * size stays small (~0.19 MB/s) for the P2P network. Must be called on the main
 * thread — media3 Transformer requires a Looper.
 */
object VideoMessageTranscoder {

    private const val TAG = "VideoMsgTranscoder"
    const val SIZE = 480
    const val VIDEO_BITRATE = 1_500_000

    // Black-intro probe tuning. Front-camera takes open with a few dark frames
    // while the sensor warms up; we scan from the start in STEP_MS steps up to
    // MAX_PROBE_MS and treat a frame as black when its mean luma (0..255) is
    // below BLACK_LUMA_THRESHOLD.
    private const val STEP_MS = 66L
    private const val MAX_PROBE_MS = 1200L
    private const val BLACK_LUMA_THRESHOLD = 18

    /**
     * @return the running [Transformer] so the caller can [Transformer.cancel]
     *   it if the screen goes away mid-transcode.
     */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun transcode(
        context: Context,
        input: File,
        output: File,
        startMs: Long,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit,
    ): Transformer {
        // When startMs > 0, clip off the leading black frames. The re-encode
        // below produces a fresh keyframe at the clip start, so the output (and
        // its frame-0 poster) begins on real content; audio is clipped in sync.
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(input))
            .apply {
                if (startMs > 0) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(startMs)
                            .build()
                    )
                }
            }
            .build()
        val presentation = Presentation.createForWidthAndHeight(
            SIZE, SIZE, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )
        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(/* audioProcessors = */ emptyList(), /* videoEffects = */ listOf<Effect>(presentation)))
            .build()

        // Pin a widely-decodable H.264 profile/level. Left unconstrained, some
        // hardware encoders tag the 480² clip as High@L6.0, which weaker decoders
        // (e.g. the emulator's MediaMetadataRetriever) refuse to extract a poster
        // frame from — the receiver then shows a black tile. Baseline@L4 matches
        // what every decoder we've seen can read; DefaultEncoderFactory falls back
        // automatically if an encoder doesn't support the request.
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(VIDEO_BITRATE)
                    .setEncodingProfileLevel(
                        MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                        MediaCodecInfo.CodecProfileLevel.AVCLevel4
                    )
                    .build()
            )
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    onSuccess(output)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    Log.e(TAG, "Transcode failed", exportException)
                    onError(exportException.message ?: "transcode failed")
                }
            })
            .build()

        transformer.start(edited, output.absolutePath)
        return transformer
    }

    /**
     * Probes [file] for the timestamp (ms) of the first non-black frame, scanning
     * from the start in [STEP_MS] steps up to [MAX_PROBE_MS]. Lets the transcoder
     * clip off the dark sensor warm-up frames so the clip starts on real content.
     *
     * Returns 0 when no non-black frame is found within the probe window (e.g. a
     * genuinely dark clip) or on any failure — i.e. "don't trim". May be slow
     * (decodes a handful of frames), so call it off the main thread.
     */
    fun firstNonBlackFrameMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            var t = 0L
            while (t <= MAX_PROBE_MS) {
                // OPTION_CLOSEST (not _SYNC): decode the actual frame near t. Sync
                // would keep returning the same first keyframe, which is the black one.
                val frame = retriever.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    val black = isMostlyBlack(frame)
                    frame.recycle()
                    if (!black) return t
                }
                t += STEP_MS
            }
        } catch (e: Exception) {
            Log.w(TAG, "Black-frame probe failed: ${e.message}")
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return 0L
    }

    private fun isMostlyBlack(frame: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(frame, 16, 16, true)
        val pixels = IntArray(16 * 16)
        small.getPixels(pixels, 0, 16, 0, 0, 16, 16)
        if (small != frame) small.recycle()
        var sum = 0L
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += (r * 30 + g * 59 + b * 11) / 100
        }
        return sum / pixels.size < BLACK_LUMA_THRESHOLD
    }
}
