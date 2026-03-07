package com.revertron.mimir.calls

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission

class AudioSender(
    private val peerNode: uniffi.mimir.PeerNode,
    private val calleePubkey: ByteArray,
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 1,
    private val bitrate: Int = 48000
) : Thread("AudioSender") {

    @Volatile private var running = false

    private lateinit var audioRecord: AudioRecord
    private lateinit var codec: MediaCodec

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun run() {
        running = true

        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            audioRecord = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufSize)
                    .build()
        } catch (e: UnsupportedOperationException) {
            e.printStackTrace()
            Log.w(name, "Likely, user didn't give us permission to record audio")
            return
        }

        Log.i(name, "AudioRecord state: ${audioRecord.state}")

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

        while (running) {
            val inIndex = codec.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                codec.getInputBuffer(inIndex)?.let { inputBuffer ->
                    inputBuffer.clear()
                    val read = audioRecord.read(inputBuffer, inputBuffer.capacity())
                    if (read > 0) {
                        codec.queueInputBuffer(inIndex, 0, read, pts, 0)
                    } else {
                        codec.queueInputBuffer(inIndex, 0, 0, pts, 0)
                    }
                    pts += frameDurationUs
                }
            }

            var outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            while (outIndex >= 0) {
                codec.getOutputBuffer(outIndex)?.let { outBuffer ->
                    val encoded = ByteArray(bufferInfo.size)
                    outBuffer.get(encoded)
                    outBuffer.clear()

                    // Send encoded audio packet via Rust PeerNode
                    try {
                        peerNode.sendCallPacket(calleePubkey, encoded)
                    } catch (e: Exception) {
                        Log.w(name, "Failed to send call packet: ${e.message}")
                    }

                    codec.releaseOutputBuffer(outIndex, false)
                }
                outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            }
            try {
                sleep(1)
            } catch (_: InterruptedException) {}
        }

        Log.i(name, "Stopping")
        audioRecord.stop()
        audioRecord.release()
        codec.stop()
        codec.release()
    }

    fun stopSender() {
        Log.i(name, "Stopping thread")
        running = false
        interrupt()
    }

    fun muteCall(mute: Boolean) {
        if (mute)
            audioRecord.stop()
        else
            audioRecord.startRecording()
    }
}
