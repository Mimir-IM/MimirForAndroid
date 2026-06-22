package com.revertron.mimir.video

import android.content.Context
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

    /**
     * @return the running [Transformer] so the caller can [Transformer.cancel]
     *   it if the screen goes away mid-transcode.
     */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    fun transcode(
        context: Context,
        input: File,
        output: File,
        onSuccess: (File) -> Unit,
        onError: (String) -> Unit,
    ): Transformer {
        val mediaItem = MediaItem.fromUri(Uri.fromFile(input))
        val presentation = Presentation.createForWidthAndHeight(
            SIZE, SIZE, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
        )
        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(/* audioProcessors = */ emptyList(), /* videoEffects = */ listOf<Effect>(presentation)))
            .build()

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build()
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
}
