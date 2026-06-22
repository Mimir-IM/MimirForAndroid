package com.revertron.mimir.calls

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer
import java.util.TreeMap

/**
 * Per-remote-participant AAC decoder + PCM jitter buffer.
 *
 * Owns one [MediaCodec] AAC-LC decoder and a seq-indexed buffer of
 * decoded PCM frames. The mixer ([GroupAudioMixer]) pulls ~10 ms PCM
 * chunks from here and sums across all participants.
 *
 * The jitter buffer is a small reorder queue (not a bulk-size queue):
 * packets keyed by their sender-monotonic seq, out-of-order inserts
 * fill gaps, and the render cursor advances by one seq slot per pull.
 * If the buffer fills beyond [maxDepthFrames] (≈120 ms), we drop the
 * oldest entries — that's a sign the sender is way ahead of us and
 * we'd rather skip than accumulate latency.
 *
 * Thread-model: network-side calls to [onFrame] come from a Tokio
 * worker thread; the render thread owns [pullPcm]. Synchronization is
 * on the `buffer` monitor — short critical sections only.
 */
class GroupParticipantAudio(
    val pubkey: ByteArray,
    private val sampleRate: Int = 44100,
    private val channelCount: Int = 1,
    /** Target depth before we start pulling. ~60-80 ms at 23ms/frame. */
    private val initialDepthFrames: Int = 3,
    /** Hard cap; beyond this we drop oldest to bound latency. */
    private val maxDepthFrames: Int = 6,
) {
    private val tag = "GrpPart-${shortHex(pubkey)}"

    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
        // csd-0 = [0x12, 0x10] encodes AAC-LC, 44.1 kHz, mono — matches the
        // encoder config in GroupAudioSender. When we later propagate the
        // sender's real AudioSpecificConfig via CALL_JOIN, swap this in.
        val csd0 = byteArrayOf(0x12, 0x10)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        configure(format, null, null, 0)
        start()
        Log.i(this@GroupParticipantAudio.tag, "decoder started sampleRate=$sampleRate channels=$channelCount")
    }

    private var framesIn: Long = 0
    private var framesOut: Long = 0
    private var framesDropped: Long = 0
    private var firstFrameLogged: Boolean = false
    private var firstDecodeLogged: Boolean = false

    /** seq → decoded PCM (16-bit signed shorts, mono at `sampleRate`). */
    private val buffer = TreeMap<Int, ShortArray>()

    /** Sequence we'll return on the next pull. -1 means "not primed yet". */
    private var playCursor: Int = -1

    /** Running peak RMS across recently-played frames; drives the speaking indicator. */
    @Volatile private var lastRms: Int = 0

    /** Accept a decrypted AAC frame. Safe to call from any thread. */
    fun onFrame(seq: Int, aacFrame: ByteArray) {
        framesIn++
        if (!firstFrameLogged) {
            firstFrameLogged = true
            Log.i(tag, "first AAC frame: seq=$seq bytes=${aacFrame.size}")
        }

        val pcm = decode(aacFrame)
        if (pcm == null) {
            framesDropped++
            if (framesDropped == 1L || framesDropped % 50 == 0L) {
                Log.w(tag, "decode() returned null (dropped=$framesDropped, framesIn=$framesIn)")
            }
            return
        }
        if (!firstDecodeLogged) {
            firstDecodeLogged = true
            Log.i(tag, "first decoded PCM: ${pcm.size} samples")
        }
        synchronized(buffer) {
            // Sender's seq only increments when VAD is "voiced"; during silence
            // it pauses. The mixer's playCursor advances every ~23 ms tick
            // regardless, so after even a short silence, incoming seqs look
            // "behind" the cursor. Re-anchor on such a burst so we don't
            // discard the new speech. An empty buffer + seq < playCursor is the
            // telltale signal that we're in a post-silence re-entry.
            if (playCursor >= 0 && seq < playCursor && buffer.isEmpty()) {
                Log.d(tag, "re-prime after silence: seq=$seq < playCursor=$playCursor, resetting")
                playCursor = -1
            }
            buffer[seq] = pcm
            // Trim if too deep — drop oldest until we fit.
            while (buffer.size > maxDepthFrames) {
                val removed = buffer.pollFirstEntry() ?: break
                framesDropped++
                if (playCursor < 0 || removed.key >= playCursor) {
                    // We just dropped something useful — advance cursor so we
                    // don't wait for a gap that will never be filled.
                    playCursor = removed.key + 1
                }
            }
        }
    }

    /**
     * Return the next ready PCM frame, or null if we're still priming /
     * under-filled. The mixer should treat a null as "silence for this
     * participant in this slot".
     */
    fun pullPcm(): ShortArray? {
        synchronized(buffer) {
            if (playCursor < 0) {
                // Waiting for the jitter buffer to reach initial depth.
                if (buffer.size < initialDepthFrames) return null
                // Start playing from the oldest seq we have.
                playCursor = buffer.firstKey()
                Log.i(tag, "primed: starting playback at seq=$playCursor (bufferSize=${buffer.size})")
            }
            val entry = buffer.remove(playCursor)
            playCursor++
            return if (entry != null) {
                framesOut++
                lastRms = rms(entry)
                entry
            } else {
                // Gap — emit silence this slot but don't stall the cursor.
                lastRms = 0
                null
            }
        }
    }

    fun recentRms(): Int = lastRms

    fun close() {
        Log.i(tag, "close: framesIn=$framesIn framesOut=$framesOut dropped=$framesDropped")
        try { codec.stop() } catch (_: Exception) {}
        codec.release()
        synchronized(buffer) { buffer.clear() }
    }

    // ── MediaCodec-backed decode: AAC frame → ShortArray of PCM samples ──

    private val bufferInfo = MediaCodec.BufferInfo()

    private fun decode(aacFrame: ByteArray): ShortArray? {
        val inIdx = codec.dequeueInputBuffer(5_000)
        if (inIdx < 0) {
            Log.w(tag, "no decoder input buffer; dropping frame")
            return null
        }
        codec.getInputBuffer(inIdx)?.let { inBuf ->
            inBuf.clear()
            inBuf.put(aacFrame)
            codec.queueInputBuffer(inIdx, 0, aacFrame.size, 0, 0)
        }

        // Drain until we get the one PCM chunk corresponding to this input.
        // In practice one-in-one-out for AAC-LC after the initial priming.
        while (true) {
            when (val outIdx = codec.dequeueOutputBuffer(bufferInfo, 5_000)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> continue
                MediaCodec.INFO_TRY_AGAIN_LATER -> return null
                else -> {
                    if (outIdx < 0) return null
                    val out = codec.getOutputBuffer(outIdx) ?: run {
                        codec.releaseOutputBuffer(outIdx, false)
                        return null
                    }
                    val pcmBytes = ByteArray(bufferInfo.size)
                    out.get(pcmBytes)
                    out.clear()
                    codec.releaseOutputBuffer(outIdx, false)
                    return bytesToShorts(pcmBytes)
                }
            }
        }
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        var bi = 0
        for (i in shorts.indices) {
            val lo = bytes[bi].toInt() and 0xFF
            val hi = bytes[bi + 1].toInt()
            shorts[i] = ((hi shl 8) or lo).toShort()
            bi += 2
        }
        return shorts
    }

    private fun rms(samples: ShortArray): Int {
        if (samples.isEmpty()) return 0
        var sumSq = 0L
        for (s in samples) {
            val v = s.toInt()
            sumSq += (v * v).toLong()
        }
        return kotlin.math.sqrt(sumSq.toDouble() / samples.size).toInt()
    }

    companion object {
        private fun shortHex(b: ByteArray): String {
            val sb = StringBuilder(8)
            for (i in 0 until minOf(4, b.size)) {
                sb.append("0123456789abcdef"[(b[i].toInt() ushr 4) and 0xF])
                sb.append("0123456789abcdef"[b[i].toInt() and 0xF])
            }
            return sb.toString()
        }
    }
}
