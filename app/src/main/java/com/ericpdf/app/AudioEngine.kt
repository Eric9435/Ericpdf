package com.ericpdf.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.max

object AudioSignals {
    const val EVENT = "com.ericpdf.app.AUDIO_EVENT"
    const val START = "com.ericpdf.app.START_AUDIO"
    const val STOP = "com.ericpdf.app.STOP_AUDIO"
    const val STATUS = "com.ericpdf.app.STATUS_AUDIO"
    const val PAGE = "com.ericpdf.app.PAGE_AUDIO"
    const val FIELD_EVENT = "event"
    const val FIELD_DOC = "docId"
    const val FIELD_SESSION = "sessionId"
    const val FIELD_ELAPSED = "elapsedStart"
    const val FIELD_PARTIAL = "partial"
}

/** Single mic capture pipeline: the same 16 kHz PCM is written to WAV and passed to Vosk. */
class LectureRecorderService : Service() {
    @Volatile private var running = false
    private var capture: AudioRecord? = null
    private var worker: Thread? = null
    private var docId: String? = null
    private var sessionId: String? = null
    private var elapsedStart: Long = 0
    @Volatile private var page = 0
    private val library by lazy { LocalLibrary(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel("lecture_audio", "Lecture recordings", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AudioSignals.START -> {
                if (!running) {
                    val id = intent.getStringExtra(AudioSignals.FIELD_DOC) ?: return START_NOT_STICKY
                    val lang = intent.getStringExtra("language") ?: "en"
                    startCapture(id,lang,intent.getIntExtra("page",0),
                        intent.getBooleanExtra("cloudMode",false),
                        intent.getStringExtra("cloudEndpoint").orEmpty(),
                        intent.getStringExtra("cloudCode").orEmpty())
                } else sendStatus("recording")
            }
            AudioSignals.STOP -> { running = false; capture?.stopSafely() }
            AudioSignals.STATUS -> { sendStatus(if (running) "recording" else "idle"); if(!running) stopSelf() }
            AudioSignals.PAGE -> { if (intent.getStringExtra(AudioSignals.FIELD_DOC) == docId) {
                page = max(0, intent.getIntExtra("page",0))
            } }
        }
        return START_NOT_STICKY
    }
    private fun startCapture(id:String,lang:String,startingPage:Int,
        online:Boolean,endpoint:String,code:String) {
        if (runCatching { library.get(id) }.isFailure) { sendStatus("error", "Document not found"); stopSelf(); return }
        docId=id; sessionId=UUID.randomUUID().toString(); page=startingPage.coerceAtLeast(0)
        val session = LectureSession(sessionId!!, System.currentTimeMillis(), 0, lang, emptyList())
        library.putSession(id, session)
        library.addCue(id, session.id, AudioCue(0, page))
        running=true
        val stop = Intent(this, LectureRecorderService::class.java).apply { action=AudioSignals.STOP }
        val pending = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification=Notification.Builder(this,"lecture_audio")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("EricPDF lecture recording")
            .setContentText(if(online) "Online transcription • local WAV saved" else "Microphone is active • stored on this device")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null,"Stop",pending).build()).build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(310, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else startForeground(310,notification)
        } catch (e: Exception) { running=false; sendStatus("error",e.message ?: "Microphone permission needed"); stopSelf(); return }
        worker=thread(name="ericpdf-record",isDaemon=false) { recordLoop(id,session,online,endpoint,code) }
    }
    private fun recordLoop(id: String, session: LectureSession,online:Boolean,endpoint:String,code:String) {
        val wav = library.recordingFile(id, session.id).apply { parentFile?.mkdirs() }
        var audio: AudioRecord? = null
        var raf: RandomAccessFile? = null
        var model: Model? = null
        var recognizer: Recognizer? = null
        var cloud:OnlineLectureTranscriber?=null
        var totalBytes = 0L
        val segments=mutableListOf<TranscriptSegment>()
        var lastSegmentEnd=0L
        var recordingError: String? = null
        var previousPartial=""
        var lastPartialBroadcastAt=0L
        try {
            val min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
            require(min > 0) { "16 kHz microphone capture not available" }
            val speechRecorder=runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,16000,
                    AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,
                    max(8192,min * 2))
            }.getOrNull()
            audio=if(speechRecorder?.state==AudioRecord.STATE_INITIALIZED) speechRecorder
                else {
                    speechRecorder?.release()
                    AudioRecord(MediaRecorder.AudioSource.MIC,16000,
                        AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,
                        max(8192,min * 2))
                }
            require(audio.state == AudioRecord.STATE_INITIALIZED) { "Unable to initialize microphone" }
            capture=audio
            raf=RandomAccessFile(wav,"rw").also { it.setLength(0); WavFile.writeHeader(it,0L) }
            val modelPath = library.modelDirectory(session.language)
            if (online) {
                try {
                    cloud=OnlineLectureTranscriber(endpoint,code,session.language,
                        partial={sendStatus("partial",partial=it)},
                        final={phrase, startMs, endMs ->
                            synchronized(segments) {
                                val begin=maxOf(lastSegmentEnd,startMs)
                                val end=maxOf(begin,endMs)
                                val recordedPage=library.cues(id,session.id)
                                    .lastOrNull { it.timeMs<=begin }?.page ?: page
                                segments.add(TranscriptSegment(begin,end,phrase.take(20000),recordedPage))
                                lastSegmentEnd=end
                                persistTranscriptAndNotes(id,session,end,segments)
                            }
                            sendStatus("transcript")
                        },
                        error={sendStatus("cloud_error",it)})
                    cloud.start()
                } catch(e:Exception) {
                    runCatching { cloud?.finish() }
                    cloud=null
                    sendStatus("cloud_error","Online captions unavailable; WAV saved locally. "+(e.message ?: ""))
                }
            } else if (modelPath.isDirectory && File(modelPath,"am").isDirectory) {
                try {
                    model=Model(modelPath.absolutePath)
                    recognizer=Recognizer(model,16000.0f).apply { setWords(true) }
                } catch (_:Exception) { sendStatus("model_error","Recording works, but speech model failed to load") }
            } else if(!online) sendStatus("model_missing", "Recording works; import a Vosk model for transcription")
            audio.startRecording()
            require(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone busy" }
            elapsedStart=SystemClock.elapsedRealtime()
            AutoBackup.recording(applicationContext,true)
            sendStatus("recording")
            val buffer=ByteArray(4096)
            var lastHeaderAt=0L
            while (running) {
                val read=audio.read(buffer,0,buffer.size)
                if (read <= 0) { if (!running) break; if (read < 0) error("Microphone read error: $read"); continue }
                raf.write(buffer,0,read)
                totalBytes+=read
                cloud?.send(buffer,read)
                val elapsedMs=totalBytes / 32L
                if (elapsedMs-lastHeaderAt>=3000) {
                    WavFile.writeHeader(raf,totalBytes)
                    raf.seek(44L+totalBytes)
                    lastHeaderAt=elapsedMs
                    AutoBackup.heartbeat(applicationContext)
                    sendStatus("recording")
                }
                val rec=recognizer
                if (rec != null) {
                    if (rec.acceptWaveForm(buffer,read)) {
                        previousPartial=""
                        val seg=SpeechSegments.fromJson(rec.result,elapsedMs,lastSegmentEnd,page)
                        if (seg != null) {
                            lastSegmentEnd=seg.endMs; segments.add(seg)
                            persistTranscriptAndNotes(id,session,elapsedMs,segments)
                            sendStatus("transcript")
                        } else sendStatus("partial",partial="")
                    } else {
                        val candidate=runCatching {
                            JSONObject(rec.partialResult).optString("partial").trim()
                        }.getOrDefault("")
                        val now=SystemClock.elapsedRealtime()
                        if (candidate!=previousPartial && now-lastPartialBroadcastAt>=350L) {
                            previousPartial=candidate
                            lastPartialBroadcastAt=now
                            sendStatus("partial",partial=candidate.take(1000))
                        }
                    }
                }
            }
            recognizer?.let {
                val last=SpeechSegments.fromJson(it.finalResult,totalBytes/32L,lastSegmentEnd,page)
                if (last != null) {
                    segments.add(last)
                    persistTranscriptAndNotes(id,session,totalBytes/32L,segments)
                }
            }
            cloud?.finish()
        } catch (e: Exception) {
            recordingError=e.message ?: "Recording failed"
        } finally {
            running=false
            runCatching { audio?.stopSafely(); audio?.release() }; capture=null
            runCatching { cloud?.finish() }
            runCatching { recognizer?.close(); model?.close() }
            runCatching { raf?.let { WavFile.writeHeader(it,totalBytes); it.close() } }
            val finalized = synchronized(segments) {
                val snapshot = segments.toList()
                runCatching { persistTranscriptAndNotes(id,session,totalBytes/32L,snapshot) }
                session.copy(durationMs=totalBytes/32L,segments=snapshot)
            }
            if (FinalLectureDocument.hasWords(finalized)) {
                runCatching { FinalLectureDocument.save(library,id,finalized) }
                    .onSuccess { sendStatus("final_ready", "Final transcript saved as TXT and PDF inside EricPDF") }
                    .onFailure {
                        sendStatus("final_export_error",
                            "The WAV/transcript remain saved, but the document could not be generated: ${it.message}")
                    }
            } else if (totalBytes > 0) {
                sendStatus("transcript_empty",
                    "Lecture WAV saved, but no speech was recognized. Install a language model and choose Transcribe offline.")
            }
            // Write the finalized WAV header before scheduling backup.
            // Any pending WorkManager retry can now include the full recording.
            AutoBackup.recording(applicationContext,false)
            if (recordingError != null) sendStatus("error",recordingError!!)
            else sendStatus("stopped")
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
    }
    private fun persistTranscriptAndNotes(
        docId:String,session:LectureSession,durationMs:Long,segments:List<TranscriptSegment>
    ) {
        library.putSession(docId,session.copy(durationMs=durationMs,segments=segments.toList()))
        library.saveGeneratedNotes(docId,session.id,LiveLectureNotes.fromSegments(segments))
    }
    private fun AudioRecord.stopSafely() { runCatching { if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop() } }
    private fun sendStatus(event: String, message: String="", partial:String="") {
        val broadcast=Intent(AudioSignals.EVENT).setPackage(packageName)
            .putExtra(AudioSignals.FIELD_EVENT,event)
            .putExtra(AudioSignals.FIELD_DOC,docId)
            .putExtra(AudioSignals.FIELD_SESSION,sessionId)
            .putExtra(AudioSignals.FIELD_ELAPSED,elapsedStart)
            .putExtra(AudioSignals.FIELD_PARTIAL,partial)
            .putExtra("message",message)
        sendBroadcast(broadcast)
    }
    override fun onDestroy() { running=false; capture?.stopSafely(); super.onDestroy() }
}

object WavFile {
    // 44-byte canonical PCM WAV header, mono, 16-bit, 16000 Hz.
    fun writeHeader(raf: RandomAccessFile, pcmBytes: Long) {
        val end=raf.filePointer; raf.seek(0L)
        fun ascii(s:String) { raf.write(s.toByteArray(Charsets.US_ASCII)) }
        fun le32(v: Long) { repeat(4) { raf.write(((v ushr (8*it)) and 255).toInt()) } }
        fun le16(v:Int) { repeat(2) { raf.write((v ushr (8*it)) and 255) } }
        ascii("RIFF"); le32(36 + pcmBytes); ascii("WAVEfmt "); le32(16)
        le16(1); le16(1); le32(16000); le32(32000); le16(2); le16(16)
        ascii("data"); le32(pcmBytes)
        raf.seek(maxOf(44L,end))
    }
}

object SpeechSegments {
    fun fromJson(json:String, fallbackEndMs:Long, previousEndMs:Long, page:Int):TranscriptSegment? {
        val obj=runCatching { JSONObject(json) }.getOrNull() ?: return null
        val text=obj.optString("text").trim()
        if (text.isBlank()) return null
        val words=obj.optJSONArray("result")
        val start=if (words != null && words.length()>0) {
            (words.optJSONObject(0)?.optDouble("start",previousEndMs/1000.0) ?: previousEndMs/1000.0).times(1000).toLong()
        } else previousEndMs
        val end=if (words != null && words.length()>0) {
            (words.optJSONObject(words.length()-1)?.optDouble("end",fallbackEndMs/1000.0) ?: fallbackEndMs/1000.0).times(1000).toLong()
        } else fallbackEndMs
        return TranscriptSegment(max(0,start),max(start,end),text,page)
    }
}
