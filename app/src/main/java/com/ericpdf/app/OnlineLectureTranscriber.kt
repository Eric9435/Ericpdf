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

/** OpenRouter STT is chunk-based, not continuous streaming. Audio remains locally recorded. */
class OnlineLectureTranscriber(
    private val endpoint:String, private val secret:String, private val language:String,
    private val partial:(String)->Unit, private val final:(String)->Unit,
    private val error:(String)->Unit
) {
    private val pending=AtomicInteger(0)
    private val closed=AtomicBoolean(false)
    private val queue=Executors.newSingleThreadExecutor()
    private val pcm=ByteArrayOutputStream(CHUNK_BYTES)
    private var sequence=0L

    fun start() {
        val uri=URL(endpoint)
        require(uri.protocol=="https" && uri.userInfo==null && uri.host.isNotEmpty() &&
            uri.path.endsWith("/transcribe")) { "Enter the HTTPS OpenRouter /transcribe proxy URL" }
        require(secret.length>=24) { "Enter your server's long access code" }
        require(language=="en" || language=="de")
    }

    // Called from microphone thread. Does not perform network requests.
    fun send(buffer:ByteArray,count:Int) {
        if(closed.get() || count<=0)return
        pcm.write(buffer,0,count)
        if(pcm.size()>=CHUNK_BYTES) {
            val chunk=pcm.toByteArray()
            pcm.reset()
            submit(chunk)
        }
    }

    private fun submit(chunk:ByteArray) {
        if(chunk.size<MIN_BYTES)return
        if(pending.get()>=4) {
            error("Captions are delayed by network. WAV recording continues.")
            return
        }
        pending.incrementAndGet()
        val number=sequence++
        queue.execute {
            try {
                partial("Transcribing lecture chunk "+(number+1)+"…")
                val audio=Base64.encodeToString(wavBytes(chunk),Base64.NO_WRAP)
                val request=JSONObject().put("language",language)
                    .put("sequence",number).put("audio",audio)
                    .toString().toByteArray(Charsets.UTF_8)
                val conn=(URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod="POST";connectTimeout=10000;readTimeout=35000
                    instanceFollowRedirects=false;doOutput=true
                    setRequestProperty("Authorization","Bearer "+secret)
                    setRequestProperty("Content-Type","application/json")
                    setFixedLengthStreamingMode(request.size)
                }
                try {
                    conn.outputStream.use{it.write(request)}
                    if(conn.responseCode==200) {
                        val text=JSONObject(conn.inputStream.bufferedReader().use{it.readText()})
                            .optString("text").trim()
                        if(text.isNotEmpty())final(text)
                    } else error("OpenRouter captions HTTP "+conn.responseCode+
                        "; WAV recording continues locally.")
                } finally { conn.disconnect() }
            }catch(ex:Exception){
                error("Online transcription problem: "+(ex.message?:"network error"))
            }finally{
                partial("")
                pending.decrementAndGet()
            }
        }
    }

    fun finish() {
        if(!closed.compareAndSet(false,true))return
        val last=pcm.toByteArray()
        pcm.reset()
        if(last.size>=MIN_BYTES)submit(last)
        queue.shutdown()
        try{
            if(!queue.awaitTermination(55,TimeUnit.SECONDS)){
                error("Final transcription timed out; WAV saved locally.")
                queue.shutdownNow()
            }
        }catch(_:InterruptedException){
            Thread.currentThread().interrupt()
            queue.shutdownNow()
        }
    }

    companion object {
        const val CHUNK_BYTES=6*16000*2
        const val MIN_BYTES=16000*2
        fun wavBytes(pcm:ByteArray):ByteArray {
            require(pcm.isNotEmpty() && pcm.size%2==0)
            val out=ByteBuffer.allocate(44+pcm.size).order(ByteOrder.LITTLE_ENDIAN)
            fun word(s:String){out.put(s.toByteArray(Charsets.US_ASCII))}
            word("RIFF");out.putInt(36+pcm.size)
            word("WAVE");word("fmt ");out.putInt(16)
            out.putShort(1);out.putShort(1)
            out.putInt(16000);out.putInt(32000)
            out.putShort(2);out.putShort(16)
            word("data");out.putInt(pcm.size);out.put(pcm)
            return out.array()
        }
    }
}
