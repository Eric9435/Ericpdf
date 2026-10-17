package com.ericpdf.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Navy=Color(0xFF13233C)
private val Accent=Color(0xFF386CF4)
private val Mist=Color(0xFFF5F7FC)

class MainActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme=lightColorScheme(
                primary=Accent,onPrimary=Color.White,background=Mist,surface=Color.White,
                onSurface=Navy,secondary=Color(0xFF3C5C80)
            )) { MainApp() }
        }
    }
}

data class RecordingUiState(val event:String="idle",val docId:String?=null,
    val sessionId:String?=null,val elapsedStart:Long=0L,val error:String="",
    val partial:String="")

@Composable
private fun MainApp() {
    val context=LocalContext.current
    val lib=remember { LocalLibrary(context.applicationContext) }
    val scope=rememberCoroutineScope()
    var docs by remember { mutableStateOf(lib.list()) }
    var folders by remember { mutableStateOf(lib.folders()) }
    var libraryFolderId by rememberSaveable {mutableStateOf<String?>(null)}
    var librarySettings by rememberSaveable {mutableStateOf(false)}
    var pendingImportFolder by remember {mutableStateOf<String?>(null)}
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var backupEnabled by remember { mutableStateOf(AutoBackup.hasFolder(context)) }
    var backupRevision by remember { mutableIntStateOf(0) }
    var pendingAutoRestore by remember { mutableStateOf<Uri?>(null) }
    var speechLanguage by rememberSaveable { mutableStateOf("en") }
    var cloudMode by rememberSaveable { mutableStateOf(false) }
    var cloudEndpoint by rememberSaveable { mutableStateOf("") }
    var cloudCode by remember { mutableStateOf("") }
    var modelStatus by remember { mutableStateOf(0) }
    var modelDownloading by remember { mutableStateOf<String?>(null) }
    val hasOfflineModel=modelStatus.let { lib.modelDirectory(speechLanguage).isDirectory }

    var recording by remember { mutableStateOf(RecordingUiState()) }
    var importCourse by rememberSaveable { mutableStateOf("Unsorted") }
    var pendingDelete by remember { mutableStateOf<LibraryDoc?>(null) }
    var exportId by remember { mutableStateOf<String?>(null) }
    var transcriptExport by remember { mutableStateOf<Pair<String,String>?>(null) }
    var liveNotesExport by remember { mutableStateOf<Pair<String,String>?>(null) }
    var audioExport by remember { mutableStateOf<Pair<String,String>?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var confirmRecording by remember { mutableStateOf(false) }
    var pendingMicPage by remember { mutableIntStateOf(0) }
    fun refresh() { docs=lib.list();folders=lib.folders();refreshKey++ }
    fun performLibraryChange(action:()->Unit) {
        try {action();refresh()}catch(e:Exception){toast=e.message ?: "Could not update library"}
    }

    suspend fun acquireOfflineModel(lang:String) {
        if(modelDownloading!=null || lib.modelDirectory(lang).isDirectory) return
        modelDownloading="Downloading ${lang.uppercase()} speech model…"
        try {
            val message=withContext(Dispatchers.IO) {
                OfflineModelDownloader.download(context,lib,lang) { percent ->
                    modelDownloading="Downloading ${lang.uppercase()} speech model: $percent%"
                }
            }
            modelStatus++
            toast=message
        } catch(e:Exception) {
            toast="Speech model download failed: ${e.message ?: "Check connection"}. Retry or import a ZIP."
        } finally {modelDownloading=null}
    }
    LaunchedEffect(speechLanguage) {
        if(!hasOfflineModel && OfflineModelDownloader.mayAutoDownload(context))
            acquireOfflineModel(speechLanguage)
    }


    DisposableEffect(context) {
        val receiver=object:BroadcastReceiver() {
            override fun onReceive(c:Context?,intent:Intent?) {
                if (intent?.action != AudioSignals.EVENT) return
                val event=intent.getStringExtra(AudioSignals.FIELD_EVENT) ?: "idle"
                val isFinished=event=="stopped" || event=="error" || event=="idle"
                val prior=recording
                recording=RecordingUiState(event,
                    if(isFinished) null else intent.getStringExtra(AudioSignals.FIELD_DOC),
                    if(isFinished) null else intent.getStringExtra(AudioSignals.FIELD_SESSION),
                    intent.getLongExtra(AudioSignals.FIELD_ELAPSED,0L),intent.getStringExtra("message") ?: "",
                    intent.getStringExtra(AudioSignals.FIELD_PARTIAL) ?: "")
                if (event=="model_error" || event=="model_missing" || event=="cloud_error" || event=="error") {
                    toast=recording.error
                }
                if (isFinished || event=="transcript") refreshKey++
                if(event=="stopped" && prior.docId != null) refresh()
            }
        }
        ContextCompat.registerReceiver(context,receiver,IntentFilter(AudioSignals.EVENT),ContextCompat.RECEIVER_NOT_EXPORTED)
        context.startService(Intent(context,LectureRecorderService::class.java).apply { action=AudioSignals.STATUS })
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    val pdfPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri:Uri? ->
        if(uri != null) scope.launch {
            busy=true
            try {
                val title=getDocumentName(context,uri)
                val importParent=pendingImportFolder
                val item=withContext(Dispatchers.IO) { lib.importPdf(uri,title,importCourse,importParent) }
                refresh();activeId=item.id;toast="PDF imported to private device storage"
            } catch (e:Exception) { toast=e.message ?: "Import failed" }
            finally { busy=false }
        }
    }
    val modelPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri:Uri? ->
        if(uri != null) scope.launch {
            busy=true;toast="Extracting $speechLanguage Vosk model…"
            try {
                toast=withContext(Dispatchers.IO) { OfflineModelImporter.import(context,lib,uri,speechLanguage) }
                modelStatus++
            } catch(e:Exception) {toast=e.message ?: "Model import failed"}
            finally {busy=false}
        }
    }
    val exportPicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id=exportId; exportId=null
        if(uri!=null && id!=null) scope.launch {
            busy=true; toast="Generating PDF…"
            try { withContext(Dispatchers.IO) { PdfTools.exportAnnotated(context,lib,lib.get(id),uri) };toast="Annotated PDF saved" }
            catch(e:Exception) { toast=e.message ?: "Export failed" }
            finally { busy=false }
        }
    }
    val textPicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val request=transcriptExport; transcriptExport=null
        if(uri!=null && request!=null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val s=lib.getSession(request.first,request.second) ?: error("Session missing")
                    val text=s.segments.joinToString("\n") { "[${humanTime(it.startMs)}] ${it.text}" }
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } ?: error("Export destination unavailable")
                }
                toast="Transcript saved"
            } catch(e:Exception){toast=e.message ?: "Transcript export failed"}
        }
    }
    val notesPicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val request=liveNotesExport; liveNotesExport=null
        if(uri!=null && request!=null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val notes=lib.readGeneratedNotes(request.first,request.second)
                    require(notes.isNotBlank()) { "No finalized speech notes to export" }
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(notes) }
                        ?: error("Export destination unavailable")
                }
                toast="Lecture notes exported"
            } catch(e:Exception) { toast=e.message ?: "Notes export failed" }
        }
    }
    val wavPicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val request=audioExport; audioExport=null
        if(uri!=null && request!=null) scope.launch {
            busy=true
            try {
                withContext(Dispatchers.IO) {
                    val source=lib.recordingFile(request.first,request.second)
                    require(source.isFile) { "Audio is missing" }
                    context.contentResolver.openOutputStream(uri)?.use { target -> source.inputStream().use { it.copyTo(target) } }
                        ?: error("Export destination unavailable")
                }
                toast="WAV lecture recording saved"
            } catch(e:Exception) {toast=e.message ?: "Audio export failed"}
            finally {busy=false}
        }
    }
    val backupPicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if(uri != null) scope.launch {
            busy=true
            try {
                withContext(Dispatchers.IO) { LocalBackup.export(context,uri) }
                toast="Local backup saved. Keep your ZIP somewhere safe."
            } catch (e:Exception) { toast=e.message ?: "Backup failed" }
            finally { busy=false }
        }
    }
    val restorePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) scope.launch {
            busy=true
            try {
                val result=withContext(Dispatchers.IO) { LocalBackup.restore(context,uri) }
                refresh()
                toast="Restored ${result.imported} document(s); skipped ${result.skipped} existing document(s)"
            } catch (e:Exception) { toast=e.message ?: "Restore failed" }
            finally { busy=false }
        }
    }
    val autoFolderPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if(uri!=null) scope.launch {
            busy=true
            try {
                withContext(Dispatchers.IO) { AutoBackup.chooseFolder(context,uri) }
                backupEnabled=true
                backupRevision++
                val previous=withContext(Dispatchers.IO) { AutoBackup.newest(context) }
                if(docs.isEmpty() && previous!=null) pendingAutoRestore=previous.uri
                toast="Automatic local backup enabled. The selected Documents folder survives app uninstall."
            } catch(e:Exception) { toast=e.message ?: "Could not grant backup folder access" }
            finally {busy=false}
        }
    }
    val micLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if(allowed && activeId!=null) {
            context.startForegroundService(Intent(context,LectureRecorderService::class.java).apply {
                action=AudioSignals.START;putExtra(AudioSignals.FIELD_DOC,activeId);putExtra("language",speechLanguage);putExtra("page",pendingMicPage)
                putExtra("cloudMode",cloudMode);putExtra("cloudEndpoint",cloudEndpoint.trim())
                putExtra("cloudCode",cloudCode)
            })
            recording=RecordingUiState(event="starting",docId=activeId)
        } else toast="Microphone permission is required for recordings"
    }

    Column(Modifier.fillMaxSize().background(Mist)) {
        if (activeId==null) {
            if(!librarySettings) {
                FolderLibraryScreen(
                    lib=lib,docs=docs,folders=folders,folderId=libraryFolderId,
                    onFolderChange={libraryFolderId=it},notice=modelDownloading ?: toast,busy=busy,
                    onOpen={activeId=it},onSettings={librarySettings=true},
                    onImport={folder ->
                        pendingImportFolder=folder
                        pdfPicker.launch(arrayOf("application/pdf"))
                    },
                    onCreateFolder={name,parent ->performLibraryChange {lib.createFolder(name,parent)}},
                    onRenameFolder={id,name ->performLibraryChange {lib.renameFolder(id,name)}},
                    onMoveFolder={id,parent ->performLibraryChange {lib.moveFolder(id,parent)}},
                    onDeleteFolder={id ->performLibraryChange {lib.deleteFolder(id)}},
                    onMoveDoc={id,parent ->performLibraryChange {lib.moveDocument(id,parent)}},
                    onRenameDoc={id,name ->performLibraryChange {lib.renameDocument(id,name)}},
                    onDeleteDoc={pendingDelete=it},onDismissNotice={toast=""}
                )
            } else HomeScreen(docs=docs,busy=busy,language=speechLanguage,course=importCourse,
                onLibrary={librarySettings=false},
                cloudMode=cloudMode,cloudEndpoint=cloudEndpoint,cloudCode=cloudCode,
                onCloudMode={cloudMode=it;toast=""},onCloudEndpoint={cloudEndpoint=it},onCloudCode={cloudCode=it},
                hasModel=hasOfflineModel,notice=modelDownloading ?: toast,
                onDownloadModel={
                    val lang=speechLanguage
                    scope.launch { acquireOfflineModel(lang) }
                },
                onLanguage={speechLanguage=it},onCourse={importCourse=it},
                onOpen={activeId=it},onImport={
                    pendingImportFolder=libraryFolderId
                    pdfPicker.launch(arrayOf("application/pdf"))
                },
                onModel={modelPicker.launch(arrayOf("application/zip","application/octet-stream","*/*"))},
                onBackup={backupPicker.launch("EricPDF-backup-${System.currentTimeMillis()}.zip")},
                onRestore={restorePicker.launch(arrayOf("application/zip","application/octet-stream","*/*"))},
                autoEnabled=backupEnabled,autoLastSuccess=backupRevision.let {AutoBackup.lastSuccess(context)},
                autoError=AutoBackup.lastError(context),
                onSelectAutoFolder={autoFolderPicker.launch(null)},
                onAutoBackupNow={
                    scope.launch {
                        busy=true
                        try {
                            withContext(Dispatchers.IO){AutoBackup.snapshot(context)}
                            backupRevision++
                            toast="Automatic backup ZIP saved in your chosen Documents folder"
                        } catch(e:Exception) {toast=e.message ?: "Automatic backup failed"}
                        finally {busy=false}
                    }
                },
                onRestoreAutoLatest={
                    scope.launch {
                        try {
                            pendingAutoRestore=withContext(Dispatchers.IO){
                                AutoBackup.newest(context)?.uri
                            }
                            if(pendingAutoRestore==null)toast="No EricPDF auto-backup ZIP in this folder"
                        } catch(e:Exception) {toast=e.message ?: "Cannot list backup folder"}
                    }
                },
                onDisableAuto={
                    AutoBackup.disconnect(context);backupEnabled=false;backupRevision++
                    toast="Auto backup disabled. Existing backup ZIP files were not deleted."
                },
                onDelete={pendingDelete=it},onClearNotice={toast=""})
        } else {
            val item=docs.firstOrNull { it.id==activeId }
            if(item==null) { LaunchedEffect(activeId) { activeId=null } }
            else ReaderScreen(doc=item,lib=lib,language=speechLanguage,cloudMode=cloudMode,
                hasModel=hasOfflineModel,recording=recording,
                notice=toast,refreshKey=refreshKey,
                onBack={activeId=null;refresh()},
                onLanguage={speechLanguage=it},onMessage={toast=it},
                onExport={exportId=item.id;exportPicker.launch(item.title.substringBeforeLast('.')+"-annotated.pdf")},
                onExportText={sessionId -> transcriptExport=item.id to sessionId; textPicker.launch("${item.title}-${sessionId.take(8)}.txt")},
                onExportLiveNotes={sessionId ->
                    liveNotesExport=item.id to sessionId
                    notesPicker.launch("${item.title}-lecture-notes-${sessionId.take(8)}.txt")
                },
                onExportAudio={sessionId -> audioExport=item.id to sessionId; wavPicker.launch("${item.title}-${sessionId.take(8)}.wav")},
                onDeleteSession={id->lib.deleteSession(item.id,id);refreshKey++},
                onPage={ page ->
                    lib.update(item.copy(lastPage=page))
                    if(recording.docId == item.id) {
                        recording.sessionId?.let { currentSessionId ->
                            val time=(SystemClock.elapsedRealtime()-recording.elapsedStart).coerceAtLeast(0)
                            lib.addCue(item.id,currentSessionId,AudioCue(time,page))
                            context.startService(Intent(context,LectureRecorderService::class.java).apply {
                                action=AudioSignals.PAGE;putExtra(AudioSignals.FIELD_DOC,item.id);putExtra("page",page)
                            })
                        }
                    }
                },
                onStroke={ page ->
                    if(recording.docId==item.id && recording.elapsedStart>0) {
                        recording.sessionId?.let { currentSessionId ->
                            lib.addCue(item.id,currentSessionId,
                                AudioCue((SystemClock.elapsedRealtime()-recording.elapsedStart).coerceAtLeast(0),page))
                        }
                    }
                },
                onRecordToggle={ currentPage ->
                    if (recording.docId==item.id && recording.event !in setOf("idle","stopped","error")) {
                        context.startService(Intent(context,LectureRecorderService::class.java).apply {action=AudioSignals.STOP})
                        recording=recording.copy(event="stopping")
                    } else if(recording.docId!=null && recording.docId!=item.id &&
                        recording.event !in setOf("idle","stopped","error")) {
                        toast="Finish the recording in the other PDF before starting a new lecture"
                    } else if(cloudMode && (!cloudEndpoint.startsWith("https://") || !cloudEndpoint.trim().endsWith("/transcribe") || cloudCode.length<24)) {
                        toast="Enter your OpenRouter /transcribe proxy URL and access code in Library first"
                    } else {
                        pendingMicPage=currentPage
                        confirmRecording=true
                    }
                }
            )
        }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if(pendingAutoRestore!=null) AlertDialog(
        onDismissRequest={pendingAutoRestore=null},
        title={Text("Restore latest local backup?")},
        text={Text("Import PDFs, handwritten notes, transcripts and lecture WAV recordings from your chosen folder. Existing document IDs are skipped, not overwritten. The ZIP stays in Documents even after uninstall.")},
        confirmButton={TextButton(onClick={
            val uri=pendingAutoRestore
            pendingAutoRestore=null
            if(uri!=null) scope.launch {
                busy=true
                try {
                    val result=withContext(Dispatchers.IO){LocalBackup.restore(context,uri)}
                    refresh()
                    AutoBackup.changed(context,now=true)
                    toast="Restored ${result.imported} document(s), skipped ${result.skipped} already present"
                } catch(e:Exception){toast=e.message ?: "Restore failed"}
                finally {busy=false}
            }
        }){Text("Restore latest")}},
        dismissButton={TextButton(onClick={pendingAutoRestore=null}){Text("Cancel")}}
    )
    if(confirmRecording) AlertDialog(onDismissRequest={confirmRecording=false},
        title={Text("Recording permission")},
        text={Text(if(cloudMode)
            "Recording requires professor permission. Online mode sends short audio chunks via your server to OpenRouter. PDFs and WAV remain locally stored; OpenRouter may charge for speech-to-text."
            else "Record your professor only after obtaining permission. Audio and transcripts remain private to this device unless you export them.")},
        confirmButton={TextButton(onClick={
            confirmRecording=false
            if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) {
                context.startForegroundService(Intent(context,LectureRecorderService::class.java).apply {
                    action=AudioSignals.START;putExtra(AudioSignals.FIELD_DOC,activeId)
                    putExtra("language",speechLanguage);putExtra("page",pendingMicPage)
                putExtra("cloudMode",cloudMode);putExtra("cloudEndpoint",cloudEndpoint.trim())
                putExtra("cloudCode",cloudCode)
                })
                recording=RecordingUiState(event="starting",docId=activeId)
            } else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }){Text("I have permission")}},
        dismissButton={TextButton(onClick={confirmRecording=false}){Text("Cancel")}})
    if(pendingDelete != null) {
        val item=pendingDelete!!
        AlertDialog(onDismissRequest={pendingDelete=null},title={Text("Delete ${item.title}?")},
            text={Text("Removes the local PDF, ink, notes, and all lecture recordings permanently.")},
            confirmButton={TextButton(onClick={lib.delete(item.id);refresh();pendingDelete=null}) {Text("Delete")}},
            dismissButton={TextButton(onClick={pendingDelete=null}) {Text("Cancel")}})
    }
}

private fun getDocumentName(context:Context,uri:Uri):String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
        if(cursor.moveToFirst()) cursor.getString(0) else "Lecture.pdf"
    } ?: "Lecture.pdf"
}.getOrDefault("Lecture.pdf")

fun humanTime(ms:Long):String {
    val seconds=(ms/1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(seconds/3600,(seconds/60)%60,seconds%60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    docs:List<LibraryDoc>,busy:Boolean,language:String,course:String,hasModel:Boolean,notice:String,
    cloudMode:Boolean,cloudEndpoint:String,cloudCode:String,
    onCloudMode:(Boolean)->Unit,onCloudEndpoint:(String)->Unit,onCloudCode:(String)->Unit,
    onLanguage:(String)->Unit,onCourse:(String)->Unit,onOpen:(String)->Unit,
    onImport:()->Unit,onModel:()->Unit,onDownloadModel:()->Unit,
    onBackup:()->Unit,onRestore:()->Unit,
    autoEnabled:Boolean,autoLastSuccess:Long,autoError:String,
    onSelectAutoFolder:()->Unit,onAutoBackupNow:()->Unit,onRestoreAutoLatest:()->Unit,
    onDisableAuto:()->Unit,
    onDelete:(LibraryDoc)->Unit,onClearNotice:()->Unit,onLibrary:()->Unit
) {
    val contactContext=LocalContext.current
    val onlineConfigured=cloudEndpoint.trim().startsWith("https://") && cloudEndpoint.trim().endsWith("/transcribe") && cloudCode.length>=24
    Column(Modifier.fillMaxSize()) {
        Surface(color=Navy) {
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp, vertical=16.dp)) {
                TextButton(onClick=onLibrary) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,null,tint=Color.White)
                    Text(" Back to documents",color=Color.White)
                }
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.MenuBook,null,tint=Color.White,modifier=Modifier.size(32.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("EricPDF",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=Color.White)
                        Text("Settings and backup · ${ericPdfVersion(LocalContext.current)}",
                            color=Color(0xFFBED0EE),style=MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(onClick=onImport,enabled=!busy) {
                        Icon(Icons.Default.UploadFile,null)
                        Spacer(Modifier.width(8.dp));Text("Import PDF")
                    }
                    if(!cloudMode) {
                        if(!hasModel) OutlinedButton(onClick=onDownloadModel,enabled=!busy,
                            colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)) {
                            Icon(Icons.Default.Download,null)
                            Spacer(Modifier.width(6.dp));Text("Download ${language.uppercase()} speech")
                        }
                        OutlinedButton(onClick=onModel,enabled=!busy,
                            colors=ButtonDefaults.outlinedButtonColors(contentColor=Color.White)) {
                            Icon(Icons.Default.FolderOpen,null)
                            Spacer(Modifier.width(6.dp));Text("Import model ZIP")
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal=16.dp,vertical=12.dp)) {
            if(notice.isNotBlank()) {
                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFE5EEFF))) {
                    Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(notice,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                        IconButton(onClick=onClearNotice) {Icon(Icons.Default.Close,null)}
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("LECTURE LIBRARY",style=MaterialTheme.typography.labelMedium,color=Accent,fontWeight=FontWeight.Bold,
                    modifier=Modifier.weight(1f))
                Text("${docs.size} PDF${if(docs.size==1) "" else "s"}",style=MaterialTheme.typography.labelSmall,color=Color.Gray)
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick=onBackup,enabled=!busy) {
                    Icon(Icons.Default.SaveAlt,null,Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp));Text("Back up")
                }
                OutlinedButton(onClick=onRestore,enabled=!busy) {
                    Icon(Icons.Default.Unarchive,null,Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp));Text("Restore")
                }
            }
            Spacer(Modifier.height(10.dp))
            Card(colors=CardDefaults.cardColors(containerColor=Color.White),
                shape=RoundedCornerShape(14.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderCopy,null,tint=Accent,modifier=Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Automatic local backup",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                        Text(if(autoEnabled) "ON" else "OFF",
                            color=if(autoEnabled) Color(0xFF18784C) else Color.Gray,
                            style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Bold)
                    }
                    Text(if(autoEnabled) "Backups remain in your selected Documents folder when EricPDF is uninstalled."
                        else "Choose a shared Documents folder once. EricPDF saves restorable ZIPs there automatically, without Firebase.",
                        style=MaterialTheme.typography.bodySmall)
                    if(autoEnabled) {
                        Text(if(autoLastSuccess>0L)
                            "Last successful backup: ${DateFormat.getDateTimeInstance().format(Date(autoLastSuccess))}"
                            else "No successful automatic backup yet.",
                            color=Color.Gray,style=MaterialTheme.typography.labelSmall)
                        if(autoError.isNotBlank()) Text("Backup warning: $autoError",
                            color=Color(0xFFAF4A21),style=MaterialTheme.typography.labelSmall)
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick=onSelectAutoFolder,enabled=!busy) {
                            Text(if(autoEnabled) "Change folder" else "Choose backup folder")
                        }
                        if(autoEnabled) {
                            OutlinedButton(onClick=onAutoBackupNow,enabled=!busy && docs.isNotEmpty()) {
                                Text("Backup now")
                            }
                            OutlinedButton(onClick=onRestoreAutoLatest,enabled=!busy) {
                                Text("Restore latest")
                            }
                            TextButton(onClick=onDisableAuto,enabled=!busy) {Text("Turn off")}
                        }
                    }
                    Text("After reinstalling, choose the SAME folder again to restore. Local files are not protected from phone loss or factory reset. ZIPs are unencrypted.",
                        color=Color.Gray,style=MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("SPEECH & TRANSCRIPTION",color=Accent,fontWeight=FontWeight.Bold,
                style=MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                FilterChip(selected=!cloudMode,onClick={onCloudMode(false)},label={Text("Offline")})
                FilterChip(selected=cloudMode,onClick={onCloudMode(true)},label={Text("Online")})
                FilterChip(selected=language=="en",onClick={onLanguage("en")},label={Text("English ASR")})
                FilterChip(selected=language=="de",onClick={onLanguage("de")},label={Text("Deutsch ASR")})
                Text(
                    if(cloudMode) (if(onlineConfigured) "URL entered • not tested" else "Setup required")
                    else if(hasModel) "Offline model ready" else "Offline model needed",
                    style=MaterialTheme.typography.labelSmall,
                    color=if((cloudMode && onlineConfigured)||(!cloudMode && hasModel))
                        Color(0xFF15794C) else Color(0xFFB26B00)
                )
            }
            if(cloudMode) {
                Surface(
                    modifier=Modifier.fillMaxWidth(),
                    color=if(onlineConfigured) Color(0xFFE7F5EB) else Color(0xFFFFF3E2),
                    shape=RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(if(onlineConfigured) "Online settings entered — not connected yet"
                            else "Online transcription needs an OpenRouter proxy",
                            style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                        Text(if(onlineConfigured)
                            "Captions use overlapping 8-second audio windows (then every ~6 seconds), plus network processing."
                            else "1. Deploy the OpenRouter proxy on Render.  2. Add OPENROUTER_API_KEY and a private access code.  3. Enter its HTTPS /transcribe URL and code below.",
                            style=MaterialTheme.typography.bodySmall)
                        Text("Online mode uploads overlapping audio windows (~33% extra audio for better word continuity). Usage may incur charges.",
                            style=MaterialTheme.typography.labelSmall,color=Color(0xFF81542C))
                    }
                }
                OutlinedTextField(value=cloudEndpoint,onValueChange=onCloudEndpoint,
                    modifier=Modifier.fillMaxWidth(),singleLine=true,
                    label={Text("HTTPS OpenRouter proxy URL ending in /transcribe")})
                OutlinedTextField(value=cloudCode,onValueChange=onCloudCode,
                    modifier=Modifier.fillMaxWidth(),singleLine=true,
                    visualTransformation=PasswordVisualTransformation(),
                    label={Text("Server access code • not saved")})
            }
            OutlinedTextField(value=course,onValueChange=onCourse,modifier=Modifier.fillMaxWidth(),singleLine=true,
                label={Text("Course label for next import (e.g. Control Systems)")})
            Spacer(Modifier.height(12.dp))
            Card(colors=CardDefaults.cardColors(containerColor=Color.White),
                shape=RoundedCornerShape(15.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("About EricPDF",fontWeight=FontWeight.Bold,
                        style=MaterialTheme.typography.titleMedium)
                    Text("Version ${ericPdfVersion(LocalContext.current)}")
                    Text("Developed by innovateX",fontWeight=FontWeight.SemiBold)
                    Text("Developer: Eric Scott @ Aung Phone Myat")
                    TextButton(onClick={
                        val mail=android.content.Intent(android.content.Intent.ACTION_SENDTO,
                            android.net.Uri.parse("mailto:ericscott.de@gmail.com"))
                        runCatching { contactContext.startActivity(mail) }
                    }) { Text("ericscott.de@gmail.com") }
                }
            }
        }
    }
}
