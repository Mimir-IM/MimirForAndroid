package com.revertron.mimir.calls

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the output [AudioTrack] and the set of per-participant decoders.
 * Every ~20 ms it pulls one PCM frame from each participant, sums them,
 * and writes the mixed result to the track.
 *
 * The render loop runs on this thread; [onFrame] and [addParticipant] /
 * [removeParticipant] are safe to call from any thread.
 *
 * Lives for the duration of the group call and is wired into
 * `ConnectionService.groupAudioSink` so decrypted AAC frames from the
 * Rust layer land here without broadcast-marshalling overhead.
 */
class GroupAudioMixer(
    private val sampleRate: Int = 44100,
) : Thread("GroupAudioMixer") {

    @Volatile private var running = false

    /** Keyed by hex(pubkey) because ByteArray uses reference equality. */
    private val participants = ConcurrentHashMap<String, GroupParticipantAudio>()

    private lateinit var audioTrack: AudioTrack

    /** One AAC-LC frame at 44.1 kHz = 1024 samples. */
    private val frameSamples = 1024
    private val mixAccum = IntArray(frameSamples)
    private val outShorts = ShortArray(frameSamples)
    private val outBytes = ByteArray(frameSamples * 2)

    fun addParticipant(pubkey: ByteArray) {
        val key = hex(pubkey)
        participants.computeIfAbsent(key) {
            Log.i(TAG, "addParticipant ${shortKey(key)}")
            GroupParticipantAudio(pubkey, sampleRate)
        }
    }

    fun removeParticipant(pubkey: ByteArray) {
        val key = hex(pubkey)
        participants.remove(key)?.let {
            Log.i(TAG, "removeParticipant ${shortKey(key)}")
            it.close()
        }
    }

    /** Feed a decoded AAC frame to the right participant. */
    fun onFrame(fromPubkey: ByteArray, seq: Int, aacFrame: ByteArray) {
        val key = hex(fromPubkey)
        val p = participants.computeIfAbsent(key) {
            Log.i(TAG, "onFrame: auto-adding participant ${shortKey(key)}")
            GroupParticipantAudio(fromPubkey, sampleRate)
        }
        p.onFrame(seq, aacFrame)
    }

    /** 0..some-large-int RMS of recent audio from this sender; drives UI. */
    fun recentRms(pubkey: ByteArray): Int =
        participants[hex(pubkey)]?.recentRms() ?: 0

    override fun run() {
        running = true

        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Keep the track buffer around one AAC frame so write() blocks and
        // paces us naturally to the playback clock.
        val trackBufSize = maxOf(minBufSize, frameSamples * 2)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(trackBufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack.play()
        Log.i(TAG, "mixer started sampleRate=$sampleRate minBufSize=$minBufSize trackBufSize=$trackBufSize state=${audioTrack.state} playState=${audioTrack.playState}")

        var iter = 0L
        var mixedFrames = 0L
        var lastLogMs = System.currentTimeMillis()

        while (running) {
            // Zero the accumulator for this frame.
            for (i in mixAccum.indices) mixAccum[i] = 0

            // Sum each participant's next PCM frame into the accumulator.
            // Participants with empty buffers contribute silence.
            var activeCount = 0
            for (p in participants.values) {
                val pcm = p.pullPcm() ?: continue
                activeCount++
                val n = minOf(pcm.size, frameSamples)
                for (i in 0 until n) {
                    mixAccum[i] += pcm[i].toInt()
                }
            }

            // Clip to Short range (simple hard clip — fine for ≤16 speakers;
            // worst case is when everyone talks at once, which already sounds
            // bad for other reasons).
            for (i in outShorts.indices) {
                var v = mixAccum[i]
                if (v > 32767) v = 32767
                else if (v < -32768) v = -32768
                outShorts[i] = v.toShort()
            }

            // Pack shorts → bytes (little-endian) for AudioTrack.
            var bi = 0
            for (i in outShorts.indices) {
                val s = outShorts[i].toInt()
                outBytes[bi] = (s and 0xFF).toByte()
                outBytes[bi + 1] = ((s shr 8) and 0xFF).toByte()
                bi += 2
            }

            // Blocking write paces us. No participants → still write silence so
            // the audio clock keeps running and the first incoming frame lines
            // up immediately.
            val wrote = audioTrack.write(outBytes, 0, outBytes.size)
            if (wrote < 0 && (iter % 50L == 0L)) {
                Log.w(TAG, "audioTrack.write returned $wrote (iter=$iter)")
            }
            if (activeCount > 0) mixedFrames++

            iter++
            val now = System.currentTimeMillis()
            if (now - lastLogMs >= 2000) {
                Log.d(TAG, "tick: participants=${participants.size} activeMixed=$mixedFrames iter=$iter playState=${audioTrack.playState}")
                lastLogMs = now
                mixedFrames = 0
            }
        }

        Log.i(TAG, "Stopping mixer")
        for (p in participants.values) p.close()
        participants.clear()
        try { audioTrack.stop() } catch (_: Exception) {}
        audioTrack.release()
    }

    fun stopMixer() {
        running = false
        interrupt()
    }

    companion object {
        private const val TAG = "GroupAudioMixer"

        private fun hex(b: ByteArray): String {
            val sb = StringBuilder(b.size * 2)
            for (x in b) {
                sb.append("0123456789abcdef"[(x.toInt() ushr 4) and 0xF])
                sb.append("0123456789abcdef"[x.toInt() and 0xF])
            }
            return sb.toString()
        }

        private fun shortKey(fullHex: String): String =
            if (fullHex.length >= 8) fullHex.substring(0, 8) else fullHex
    }
}
