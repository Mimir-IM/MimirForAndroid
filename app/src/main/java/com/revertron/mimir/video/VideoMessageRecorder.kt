package com.revertron.mimir.video

import android.annotation.SuppressLint
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Thin CameraX wrapper that records a front-camera video-message take into a
 * file while showing a live preview. The raw take is later square-cropped and
 * compressed by [VideoMessageTranscoder] before sending.
 *
 * Lifecycle: create once per recording attempt, call [start], then [stop] to
 * finalize (or [release] to tear down without keeping the result). All CameraX
 * callbacks arrive on the main thread.
 */
class VideoMessageRecorder(
    private val activity: AppCompatActivity,
    private val previewView: PreviewView,
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var released = false

    /** Fired once recording has actually started (camera is rolling). */
    var onStarted: (() -> Unit)? = null
    /** Fired when the recording is finalized. `hasError` is true when CameraX
     *  reported an error (the file should be discarded). `durationMs` is the
     *  actual encoded length (not wall-clock hold time). */
    var onFinalized: ((file: File, hasError: Boolean, durationMs: Long) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null

    /**
     * Binds the front camera + preview and starts recording audio+video into
     * [outputFile]. Requires CAMERA and RECORD_AUDIO to already be granted.
     */
    @SuppressLint("MissingPermission")
    fun start(outputFile: File) {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (released) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                // Record at SD — we downscale/crop to 480x480 afterwards, so a
                // small capture keeps CPU and the intermediate file low.
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.SD,
                            FallbackStrategy.higherQualityOrLowerThan(Quality.SD)
                        )
                    )
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                videoCapture = capture

                provider.unbindAll()
                provider.bindToLifecycle(
                    activity,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    capture
                )

                val options = FileOutputOptions.Builder(outputFile).build()
                recording = capture.output
                    .prepareRecording(activity, options)
                    .withAudioEnabled()
                    .start(ContextCompat.getMainExecutor(activity)) { event ->
                        when (event) {
                            is VideoRecordEvent.Start -> onStarted?.invoke()
                            is VideoRecordEvent.Finalize -> {
                                val hasError = event.hasError()
                                if (hasError) {
                                    Log.e(TAG, "Recording finalized with error ${event.error}")
                                }
                                val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000
                                onFinalized?.invoke(outputFile, hasError, durationMs)
                            }
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start video recording", e)
                onError?.invoke(e.message ?: "camera error")
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    /** Stops the active recording; the finalize callback fires shortly after. */
    fun stop() {
        try {
            recording?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "stop() failed: ${e.message}")
        }
        recording = null
    }

    /** Tears everything down. Call after [onFinalized] or to abort. */
    fun release() {
        released = true
        try { recording?.stop() } catch (_: Exception) {}
        recording = null
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraProvider = null
        videoCapture = null
    }

    companion object {
        private const val TAG = "VideoMessageRecorder"
    }
}
