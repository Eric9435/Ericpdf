package com.ericpdf.app

import android.util.Base64
import okhttp3.*
import org.json.*
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Realtime captions over TLS. The Android APK never contains a permanent OpenAI key. */
class OnlineLectureTranscriber(
    private val endpoint:String,private val secret:String,private val language:String,
    private val partial:(String)->Unit,private val final:(String)->Unit,private val error:(String)->Unit
) {
    private val client=OkHttpClient.Builder().pingInterval(20,TimeUnit.SECONDS).build()
    private val latch=CountDownLatch(1)
    private val fragments=mutableMapOf<String,StringBuilder>()
    private var socket:WebSocket?=null
    @Volatile private var connected=false
    private var pending=false
    private var samples=0L
    fun start() {
        require(endpoint.startsWith("https://")) { "HTTPS token-server endpoint required" }
        val url=URL(endpoint)
        require(url.userInfo==null && url.host.isNotBlank()) { "Invalid cloud endpoint" }
        val conn=(url.openConnection() as HttpURLConnection).apply {
            requestMethod="POST";connectTimeout=8000;readTimeout=8000
            instanceFollowRedirects=false;doOutput=true
            setRequestProperty("Authorization","Bearer "+secret)
            setRequestProperty("Content-Type","application/json")
        }
        val token=try {
            conn.outputStream.use {it.write(JSONObject().put("language",language).toString().toByteArray())}
            require(conn.responseCode==200) { "Token server HTTP "+conn.responseCode }
            JSONObject(conn.inputStream.bufferedReader().use{it.readText()}).getString("value")
        } finally {conn.disconnect()}
        require(token.startsWith("ek_")) { "Server returned invalid token" }
        socket=client.newWebSocket(Request.Builder()
            .url("wss://api.openai.com/v1/realtime?intent=transcription")
            .header("Authorization","Bearer "+token).build(),object:WebSocketListener() {
            override fun onOpen(ws:WebSocket,response:Response) {
                val config=JSONObject().put("type","transcription")
                    .put("audio",JSONObject().put("input",JSONObject()
                        .put("format",JSONObject().put("type","audio/pcm").put("rate",24000))
                        .put("transcription",JSONObject().put("model","gpt-live-transcribe")
                            .put("languages",JSONArray().put(language)))
                        .put("turn_detection",JSONObject.NULL)))
                ws.send(JSONObject().put("type","session.update").put("session",config).toString())
            }
            override fun onMessage(ws:WebSocket,message:String) {
                runCatching {
                    val obj=JSONObject(message)
                    when(obj.optString("type")) {
                        "session.updated"->{connected=true;latch.countDown()}
                        "conversation.item.input_audio_transcription.delta"->{
                            val result=synchronized(fragments){
                                fragments.getOrPut(obj.optString("item_id")){StringBuilder()}
                                    .append(obj.optString("delta")).toString()
                            }
                            partial(result.takeLast(1200))
                        }
                        "conversation.item.input_audio_transcription.completed"->{
                            synchronized(fragments){fragments.remove(obj.optString("item_id"))}
                            partial("")
                            obj.optString("transcript").trim().takeIf{it.isNotBlank()}?.let(final)
                        }
                        "error"->{error(obj.optJSONObject("error")?.optString("message")
                            ?: "Cloud rejected transcription");latch.countDown()}
                    }
                }.onFailure {error("Unable to process cloud text")}
            }
            override fun onFailure(ws:WebSocket,t:Throwable,response:Response?) {
                connected=false;error("Cloud disconnected; local WAV recording continues");latch.countDown()
            }
            override fun onClosed(ws:WebSocket,code:Int,reason:String){connected=false}
        })
        if(!latch.await(12,TimeUnit.SECONDS) || !connected) {
            socket?.cancel();error("Cloud session unavailable");throw IllegalStateException("Cloud session unavailable")
        }
    }
    fun send(input:ByteArray,count:Int) {
        if(!connected)return
        val ws=socket?:return
        if(ws.queueSize()>2_000_000L){error("Cloud too slow; local WAV continues");return}
        val frame=upsample16To24(input,count)
        val ok=ws.send(JSONObject().put("type","input_audio_buffer.append")
            .put("audio",Base64.encodeToString(frame,Base64.NO_WRAP)).toString())
        if(!ok){connected=false;error("Cloud stream stopped");return}
        pending=true;samples+=count/2L
        if(samples>=128000L){
            ws.send("{\"type\":\"input_audio_buffer.commit\"}")
            samples=0L;pending=false
        }
    }
    fun finish() {
        if(pending&&connected)socket?.send("{\"type\":\"input_audio_buffer.commit\"}")
        if(connected)Thread.sleep(1800)
        socket?.close(1000,"Finished")
        client.dispatcher.executorService.shutdown()
    }
    companion object {
        fun upsample16To24(input:ByteArray,count:Int):ByteArray {
            val n=count/2
            if(n<=0)return ByteArray(0)
            val output=ByteArray(n*3)
            fun sample(i:Int):Int {
                val k=i.coerceIn(0,n-1)*2
                return ((input[k].toInt() and 255) or (input[k+1].toInt() shl 8)).toShort().toInt()
            }
            for(i in 0 until output.size/2){
                val a=i*2/3;val frac=i*2%3
                val value=((3-frac)*sample(a)+frac*sample(a+1))/3
                output[i*2]=value.toByte()
                output[i*2+1]=(value shr 8).toByte()
            }
            return output
        }
    }
}
