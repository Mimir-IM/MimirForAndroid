package com.revertron.mimir.calls

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import uniffi.mimir.MimirApp

/**
 * Captures microphone audio, encodes it as AAC-LC, and pushes each frame
 * through [MimirApp.sendGroupCallAudio]. Mirrors the 1:1 [AudioSender] —
 * same sample rate, codec, and buffer shapes — but broadcasts to the
 * current group-call session instead of a specific peer, and optionally
 * drops silent frames (DTX) via a simple RMS-based voice detector.
 *
 * Not a refactor of [AudioSender]; kept separate so 1:1 calls and group
 * calls can evolve independently.
 */
class GroupAudioSender(
    private val mimirApp: MimirApp,
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 1,
    private val bitrate: Int = 48000,
    /** Below this 16-bit short RMS, frames are considered silent. ~ -40 dBFS. */
    private val vadRmsThreshold: Int = 400,
    /** Keep sending for this many frames after the last voiced one. */
    private val vadHangoverFrames: Int = 10,
) : Thread("GroupAudioSender") {

    @Volatile private var running = false
    @Volatile private var muted = false

    private lateinit var audioRecord: AudioRecord
    private lateinit var codec: MediaCodec

    /** Exposed so [GroupCallActivity] can light a "I'm speaking" indicator. */
    @Volatile var sendingVoice: Boolean = false
        private set

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun run() {
        running = true

        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        Log.i(name, "starting sampleRate=$sampleRate channels=$channelCount bitrate=$bitrate minBufSize=$minBufSize")

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBufSize)
                .build()
        } catch (e: UnsupportedOperationException) {
            Log.w(name, "RECORD_AUDIO not granted? ${e.message}")
            return
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }

        audioRecord.startRecording()

        val bufferInfo = MediaCodec.BufferInfo()
        var pts: Long = 0
        val frameDurationUs = 1024L * 1_000_000L / sampleRate

        // VAD tracking — the encoder produces one AAC frame per ~1024 PCM
        // samples, so a per-input-frame hangover counter is in sync with
        // outgoing packets.
        var hangover = 0

        // Reusable PCM buffer for RMS computation. MediaCodec input buffers
        // don't support bulk reads, so we pull into a staging ShortArray.
        val stagingPcm = ShortArray(2048)

        var framesSent = 0
        var bytesSent = 0L
        var lastLogMs = System.currentTimeMillis()
        var firstFrameLogged = false

        while (running) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                codec.getInputBuffer(inIndex)?.let { inputBuffer ->
                    inputBuffer.clear()
                    // Read into staging first so we can compute RMS without
                    // an extra decode trip.
                    val cap = minOf(inputBuffer.capacity() / 2, stagingPcm.size)
                    val readShorts = if (muted) 0 else audioRecord.read(stagingPcm, 0, cap)
                    if (readShorts > 0) {
                        val voiced = rmsShort(stagingPcm, readShorts) >= vadRmsThreshold
                        if (voiced) {
                            hangover = vadHangoverFrames
                        } else if (hangover > 0) {
                            hangover--
                        }
                        sendingVoice = hangover > 0

                        if (sendingVoice) {
                            // Copy PCM into the codec input buffer (byte-wise).
                            for (i in 0 until readShorts) {
                                val s = stagingPcm[i].toInt()
                                inputBuffer.put((s and 0xFF).toByte())
                                inputBuffer.put(((s shr 8) and 0xFF).toByte())
                            }
                            codec.queueInputBuffer(inIndex, 0, readShorts * 2, pts, 0)
                        } else {
                            // Release the buffer without queuing audio so the
                            // encoder stays primed but emits no packet.
                            codec.queueInputBuffer(inIndex, 0, 0, pts, 0)
                        }
                    } else {
                        sendingVoice = false
                        codec.queueInputBuffer(inIndex, 0, 0, pts, 0)
                    }
                    pts += frameDurationUs
                }
            }

            var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            while (outIndex >= 0) {
                codec.getOutputBuffer(outIndex)?.let { outBuffer ->
                    if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val encoded = ByteArray(bufferInfo.size)
                        outBuffer.get(encoded)
                        outBuffer.clear()
                        try {
                            mimirApp.sendGroupCallAudio(encoded)
                            framesSent++
                            bytesSent += encoded.size
                            if (!firstFrameLogged) {
                                firstFrameLogged = true
                                Log.i(name, "first AAC frame sent: ${encoded.size} bytes")
                            }
                        } catch (e: Exception) {
                            Log.w(name, "sendGroupCallAudio failed: ${e.message}")
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }
                outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }

            val now = System.currentTimeMillis()
            if (now - lastLogMs >= 2000) {
                Log.d(name, "tick: sent=$framesSent frames, $bytesSent bytes, muted=$muted voiced=$sendingVoice")
                lastLogMs = now
            }

            try { sleep(1) } catch (_: InterruptedException) {}
        }

        Log.i(name, "stopping, total frames=$framesSent bytes=$bytesSent")
        try { audioRecord.stop() } catch (_: Exception) {}
        audioRecord.release()
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
    }

    fun stopSender() {
        running = false
        interrupt()
    }

    /** Mute the mic without tearing down the encoder — resume is instant. */
    fun setMuted(m: Boolean) { muted = m }

    private fun rmsShort(buf: ShortArray, count: Int): Int {
        if (count == 0) return 0
        var sumSq = 0L
        for (i in 0 until count) {
            val v = buf[i].toInt()
            sumSq += (v * v).toLong()
        }
        return kotlin.math.sqrt(sumSq.toDouble() / count).toInt()
    }
}
