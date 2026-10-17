package com.ericpdf.app

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Near-live STT: 8-second windows with a 2-second overlap preserve words
 * at chunk boundaries. All timestamps refer to the microphone audio clock,
 * never to variable cloud response times. The complete WAV is saved locally.
 */
class OnlineLectureTranscriber(
    private val endpoint: String,
    private val secret: String,
    private val language: String,
    private val partial: (String) -> Unit,
    private val final: (String, Long, Long) -> Unit,
    private val error: (String) -> Unit
) {
    private val pending = AtomicInteger(0)
    private val closed = AtomicBoolean(false)
    private val queue = Executors.newSingleThreadExecutor()
    private val pcm = ByteArrayOutputStream(CHUNK_BYTES + 4096)
    private val lock = Any()

    // Absolute offsets of the PCM window and its last queued unique audio.
    private var windowStartBytes = 0L
    private var lastQueuedEndBytes = 0L
    private var sequence = 0L
    // Only read/written on the single transcription worker.
    private var emittedText = ""
    private var lastEmittedEndMs = 0L

    fun start() {
        val uri = URL(endpoint)
        require(uri.protocol == "https" && uri.userInfo == null && uri.host.isNotEmpty() &&
            uri.path.endsWith("/transcribe")) {
            "Enter the HTTPS /transcribe proxy URL"
        }
        require(secret.length >= 24) { "Enter your server's long access code" }
        require(language == "en" || language == "de") { "Select English or German" }
    }

    /** Called on the microphone thread. Never makes a network request. */
    fun send(buffer: ByteArray, count: Int) {
        if (closed.get() || count <= 0) return
        synchronized(lock) {
            if (closed.get()) return
            pcm.write(buffer, 0, count)
            while (pcm.size() >= CHUNK_BYTES) {
                val available = pcm.toByteArray()
                val chunk = available.copyOfRange(0, CHUNK_BYTES)
                submit(chunk, windowStartBytes, windowStartBytes + CHUNK_BYTES)
                val advance = CHUNK_BYTES - OVERLAP_BYTES
                pcm.reset()
                pcm.write(available, advance, available.size - advance)
                windowStartBytes += advance
            }
        }
    }

    private fun submit(chunk: ByteArray, beginBytes: Long, endBytes: Long) {
        if (chunk.size < MIN_BYTES) return
        if (pending.get() >= MAX_PENDING_CHUNKS) {
            error("Cloud captions are too far behind; some text may be missing. " +
                "The complete WAV recording is still saved. Re-transcribe it later.")
            return
        }
        pending.incrementAndGet()
        val number = sequence++
        lastQueuedEndBytes = maxOf(lastQueuedEndBytes, endBytes)
        queue.execute {
            try {
                partial("Transcribing audio ${number + 1}…")
                // Near-digital silence often causes speech models to invent text.
                if (isNearSilence(chunk)) return@execute
                val transcript = requestTranscript(chunk)
                val novel = TranscriptStitcher.removeOverlap(emittedText, transcript)
                if (novel.isNotBlank()) {
                    emittedText = (emittedText + " " + novel).takeLast(2000)
                    // The start excludes previously confirmed overlapping audio.
                    val startMs = maxOf(beginBytes / BYTES_PER_MILLISECOND, lastEmittedEndMs)
                    val endMs = maxOf(startMs, endBytes / BYTES_PER_MILLISECOND)
                    final(novel, startMs, endMs)
                    lastEmittedEndMs = endMs
                }
            } catch (ex: Exception) {
                error("Cloud transcription failed: ${ex.message ?: "network error"}. " +
                    "The WAV recording is saved locally.")
            } finally {
                partial("")
                pending.decrementAndGet()
            }
        }
    }

    private fun requestTranscript(chunk: ByteArray): String {
        val audio = Base64.encodeToString(wavBytes(chunk), Base64.NO_WRAP)
        val request = JSONObject().put("language", language)
            .put("audio", audio).toString().toByteArray(Charsets.UTF_8)
        var lastError = "network error"
        repeat(2) { attempt ->
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 45000
                    instanceFollowRedirects = false
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $secret")
                    setRequestProperty("Content-Type", "application/json")
                    setFixedLengthStreamingMode(request.size)
                }
                conn.outputStream.use { it.write(request) }
                if (conn.responseCode == 200) {
                    return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        .optString("text").trim()
                }
                lastError = "HTTP ${conn.responseCode}"
                // Do not retry authentication, invalid payload, or quota errors.
                if (conn.responseCode !in listOf(502, 503, 504)) {
                    throw IllegalStateException(lastError)
                }
            } catch (ex: Exception) {
                lastError = ex.message ?: "network error"
                if (attempt == 1 || ex is IllegalStateException) throw ex
            } finally {
                conn?.disconnect()
            }
            if (attempt == 0) Thread.sleep(500)
        }
        throw IllegalStateException(lastError)
    }

    fun finish() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            val tail = pcm.toByteArray()
            pcm.reset()
            // Don't send only the overlapping context again.
            if (tail.size >= MIN_BYTES && windowStartBytes + tail.size - lastQueuedEndBytes >= FINAL_NEW_BYTES) {
                submit(tail, windowStartBytes, windowStartBytes + tail.size)
            }
        }
        queue.shutdown()
        try {
            if (!queue.awaitTermination(90, TimeUnit.SECONDS)) {
                error("Some online captions are still pending; the full WAV is saved.")
                queue.shutdownNow()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            queue.shutdownNow()
        }
    }

    companion object {
        private const val BYTES_PER_MILLISECOND = 32L // 16 kHz mono PCM16
        const val CHUNK_BYTES = 8 * 16000 * 2
        const val OVERLAP_BYTES = 2 * 16000 * 2
        const val MIN_BYTES = 16000 * 2
        private const val FINAL_NEW_BYTES = 16000 / 2 // 250 ms of genuinely new audio
        private const val MAX_PENDING_CHUNKS = 16

        fun isNearSilence(pcm: ByteArray): Boolean {
            if (pcm.size < 2) return true
            var energy = 0.0
            var peak = 0
            var count = 0
            var i = 0
            while (i + 1 < pcm.size) {
                val sample = ((pcm[i + 1].toInt() shl 8) or
                    (pcm[i].toInt() and 0xff)).toShort().toInt()
                val absolute = kotlin.math.abs(sample)
                peak = maxOf(peak, absolute)
                energy += sample.toDouble() * sample
                count++
                i += 2
            }
            return peak < 350 && kotlin.math.sqrt(energy / count) < 75
        }

        fun wavBytes(pcm: ByteArray): ByteArray {
            require(pcm.isNotEmpty() && pcm.size % 2 == 0)
            val out = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            fun word(s: String) { out.put(s.toByteArray(Charsets.US_ASCII)) }
            word("RIFF"); out.putInt(36 + pcm.size)
            word("WAVE"); word("fmt "); out.putInt(16)
            out.putShort(1); out.putShort(1)
            out.putInt(16000); out.putInt(32000)
            out.putShort(2); out.putShort(16)
            word("data"); out.putInt(pcm.size); out.put(pcm)
            return out.array()
        }
    }
}
