package com.ericpdf.app

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow

private val InkBlue=0xFF2167CE.toInt()
private val InkRed=0xFFE35049.toInt()
private val InkYellow=0xFFF8D355.toInt()
private enum class PenTool { READ, PEN, HIGHLIGHT, ERASE }

@Composable
fun ReaderScreen(
    doc:LibraryDoc,lib:LocalLibrary,language:String,hasModel:Boolean,recording:RecordingUiState,
    notice:String,refreshKey:Int,onBack:()->Unit,onLanguage:(String)->Unit,onMessage:(String)->Unit,
    onExport:()->Unit,onExportText:(String)->Unit,onExportLiveNotes:(String)->Unit,
    onExportAudio:(String)->Unit,onDeleteSession:(String)->Unit,
    onPage:(Int)->Unit,onStroke:(Int)->Unit,onRecordToggle:(Int)->Unit
) {
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    var page by remember(doc.id) { mutableIntStateOf(doc.lastPage.coerceIn(0,doc.pageCount-1)) }
    var penTool by remember { mutableStateOf(PenTool.READ) }
    var color by remember { mutableIntStateOf(InkBlue) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var showSearch by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var foundPages by remember { mutableStateOf<List<Int>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var bookmarks by remember(doc.id) { mutableStateOf(lib.bookmarks(doc.id)) }
    var strokes by remember(doc.id,page) { mutableStateOf(lib.readInk(doc.id,page)) }
    var showJump by remember { mutableStateOf(false) }
    var targetPage by remember { mutableStateOf("") }
    var session by remember(doc.id) { mutableStateOf<LectureSession?>(null) }
    var sessions by remember(refreshKey, doc.id) { mutableStateOf(lib.sessions(doc.id)) }
    var processing by remember { mutableStateOf(false) }
        var tab by remember { mutableIntStateOf(0) }
    val hasRecording=recording.docId==doc.id && recording.event !in setOf("idle","stopped","error")
    LaunchedEffect(hasRecording) { if(hasRecording) tab=2 }

    LaunchedEffect(refreshKey,doc.id) {
        sessions=lib.sessions(doc.id)
        session=if(session==null) sessions.firstOrNull() else sessions.firstOrNull { it.id==session?.id } ?: sessions.firstOrNull()
    }
    LaunchedEffect(page) {
        strokes=lib.readInk(doc.id,page)
        zoom=1f
        onPage(page)
    }
    Column(Modifier.fillMaxSize()) {
        Surface(color=Color.White,tonalElevation=2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal=6.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=onBack) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"Library")}
                Column(Modifier.weight(1f)) {
                    Text(doc.title,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Bold)
                    Text("${doc.course}  ·  ${page+1} / ${doc.pageCount}",style=MaterialTheme.typography.labelSmall,color=Color.Gray)
                }
                IconButton(onClick={
                    lib.setBookmark(doc.id,page,page !in bookmarks)
                    bookmarks=lib.bookmarks(doc.id)
                }) {Icon(if(page in bookmarks) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,"Bookmark", tint=if(page in bookmarks) Color(0xFFEB9A31) else Color.Gray)}
                IconButton(onClick={showSearch=!showSearch}) {Icon(Icons.Default.Search,"Search PDF")}
                IconButton(onClick=onExport) {Icon(Icons.Default.IosShare,"Export marked PDF")}
            }
        }
        if(showSearch) {
            Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(search,{search=it},Modifier.weight(1f),singleLine=true,placeholder={Text("Search PDF text")})
                TextButton(onClick={
                    scope.launch {
                        searching=true
                        try { foundPages=withContext(Dispatchers.IO){PdfTools.search(context,lib.sourceFile(doc.id),search)} }
                        catch(e:Exception){onMessage(e.message ?: "PDF search failed")}
                        finally { searching=false }
                    }
                },enabled=search.isNotBlank() && !searching) {Text("Find")}
            }
            if(searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(foundPages.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    foundPages.forEach { n-> SuggestionChip(onClick={page=n},label={Text("Page ${n+1}")}) }
                }
            } else if (search.isNotBlank() && !searching) Text("Search matches appear here (text-layer PDFs only)",Modifier.padding(start=12.dp),style=MaterialTheme.typography.labelSmall)
        }
        if(notice.isNotBlank()) {
            Text(notice,Modifier.fillMaxWidth().background(Color(0xFFE6EEFF)).padding(horizontal=14.dp,vertical=5.dp),
                color=Color(0xFF224C93),style=MaterialTheme.typography.labelSmall,maxLines=2)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=9.dp,vertical=4.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            ToolChip("Read",Icons.Default.PanToolAlt,penTool==PenTool.READ){penTool=PenTool.READ}
            ToolChip("Pen",Icons.Default.Edit,penTool==PenTool.PEN){penTool=PenTool.PEN}
            ToolChip("Highlight",Icons.Default.BorderColor,penTool==PenTool.HIGHLIGHT){penTool=PenTool.HIGHLIGHT}
            ToolChip("Erase",Icons.Default.AutoFixOff,penTool==PenTool.ERASE){penTool=PenTool.ERASE}
            if(penTool==PenTool.PEN) {
                ColorChoice(color==InkBlue,Color(InkBlue)){color=InkBlue}
                ColorChoice(color==InkRed,Color(InkRed)){color=InkRed}
            }
            IconButton(onClick={ if(strokes.isNotEmpty()) {strokes=strokes.dropLast(1);lib.saveInk(doc.id,page,strokes)} },enabled=strokes.isNotEmpty()) {
                Icon(Icons.Default.Undo,"Undo last stroke")
            }
            Text("${(zoom*100).toInt()}%",style=MaterialTheme.typography.labelSmall)
            IconButton(onClick={zoom=(zoom-0.25f).coerceIn(1f,3f)}) {Icon(Icons.Default.Remove,"Zoom out")}
            IconButton(onClick={zoom=(zoom+0.25f).coerceIn(1f,3f)}) {Icon(Icons.Default.Add,"Zoom in")}
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide=maxWidth>=800.dp
            if(wide) Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f)) {
                    ReaderPage(doc,lib,page,penTool,color,zoom,strokes,onZoom={zoom=it},onInk={
                        strokes=it;lib.saveInk(doc.id,page,it);onStroke(page)
                    },modifier=Modifier.weight(1f))
                    PageBar(page,doc.pageCount,onPrev={page--},onNext={page++},onJump={showJump=true})
                }
                VerticalDivider()
                SidePanel(doc,lib,page,bookmarks,sessions,session,{session=it},recording,hasRecording,hasModel,
                    language,onLanguage,tab,{tab=it},processing,
                    onRecordToggle={onRecordToggle(page)},
                    onRetranscribe={ selected ->
                        scope.launch {
                            processing=true
                            try {
                                val updated=withContext(Dispatchers.IO){AudioTranscriber.transcribe(lib,doc.id,selected)}
                                session=updated;sessions=lib.sessions(doc.id);onMessage("Offline transcription completed")
                            } catch(e:Exception){onMessage(e.message ?: "Transcription failed")}
                            finally {processing=false}
                        }
                    },onExportText=onExportText,onExportLiveNotes=onExportLiveNotes,
                    onExportAudio=onExportAudio,onDeleteSession={session= null; onDeleteSession(it)},
                    onSeekPage={page=it.coerceIn(0,doc.pageCount-1)},
                    onSessionUpdate={ updated ->lib.putSession(doc.id,updated);session=updated },
                    modifier=Modifier.width(340.dp))
            } else Column(Modifier.fillMaxSize()) {
                ReaderPage(doc,lib,page,penTool,color,zoom,strokes,onZoom={zoom=it},onInk={
                    strokes=it;lib.saveInk(doc.id,page,it);onStroke(page)
                },modifier=Modifier.weight(0.62f))
                PageBar(page,doc.pageCount,onPrev={page--},onNext={page++},onJump={showJump=true})
                HorizontalDivider()
                SidePanel(doc,lib,page,bookmarks,sessions,session,{session=it},recording,hasRecording,hasModel,
                    language,onLanguage,tab,{tab=it},processing,
                    onRecordToggle={onRecordToggle(page)},
                    onRetranscribe={selected ->
                        scope.launch {
                            processing=true
                            try {
                                val updated=withContext(Dispatchers.IO){AudioTranscriber.transcribe(lib,doc.id,selected)}
                                session=updated;sessions=lib.sessions(doc.id);onMessage("Offline transcription completed")
                            } catch(e:Exception){onMessage(e.message ?: "Transcription failed")}
                            finally {processing=false}
                        }
                    },onExportText=onExportText,onExportLiveNotes=onExportLiveNotes,
                    onExportAudio=onExportAudio,onDeleteSession={session=null;onDeleteSession(it)},
                    onSeekPage={page=it.coerceIn(0,doc.pageCount-1)},
                    onSessionUpdate={updated->lib.putSession(doc.id,updated);session=updated},
                    modifier=Modifier.weight(0.38f))
            }
        }
    }
    if(showJump) AlertDialog(onDismissRequest={showJump=false},title={Text("Go to page")},
        text={OutlinedTextField(targetPage,{targetPage=it.filter(Char::isDigit).take(6)},
            label={Text("Page 1–${doc.pageCount}")},singleLine=true)},
        confirmButton={TextButton(onClick={targetPage.toIntOrNull()?.let{page=it.coerceIn(1,doc.pageCount)-1};showJump=false}){Text("Go")}},
        dismissButton={TextButton(onClick={showJump=false}){Text("Cancel")}})
}

@Composable
private fun ToolChip(name:String,icon:androidx.compose.ui.graphics.vector.ImageVector,selected:Boolean,onClick:()->Unit) {
    FilterChip(selected=selected,onClick=onClick,label={Text(name)},leadingIcon={Icon(icon,null,Modifier.size(16.dp))})
}
@Composable
private fun ColorChoice(selected:Boolean,color:Color,onClick:()->Unit) {
    Surface(onClick=onClick,shape=RoundedCornerShape(50),border=if(selected) BorderStroke(2.dp,Color.Black) else null) {
        Box(Modifier.padding(3.dp).size(20.dp).clip(RoundedCornerShape(50)).background(color))
    }
}
@Composable
private fun PageBar(page:Int,count:Int,onPrev:()->Unit,onNext:()->Unit,onJump:()->Unit) {
    Surface(color=Color.White) {
        Row(Modifier.fillMaxWidth().height(49.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onPrev,enabled=page>0) {Icon(Icons.AutoMirrored.Filled.ArrowBack,"Previous page")}
            TextButton(onClick=onJump) {Text("${page+1} / $count",fontWeight=FontWeight.Bold)}
            IconButton(onClick=onNext,enabled=page<count-1) {Icon(Icons.AutoMirrored.Filled.ArrowForward,"Next page")}
        }
    }
}

@Composable
private fun ReaderPage(doc:LibraryDoc,lib:LocalLibrary,page:Int,tool:PenTool,color:Int,zoom:Float,
    strokes:List<InkStroke>,onZoom:(Float)->Unit,onInk:(List<InkStroke>)->Unit,modifier:Modifier=Modifier) {
    val currentZoom by rememberUpdatedState(zoom)
    var bitmap by remember(doc.id,page) { mutableStateOf<Bitmap?>(null) }
    var error by remember(doc.id,page) { mutableStateOf<String?>(null) }
    LaunchedEffect(doc.id,page) {
        bitmap=null;error=null
        try {bitmap=withContext(Dispatchers.IO){PdfTools.render(lib.sourceFile(doc.id),page)} }
        catch(e:Exception){error=e.message ?: "Page could not be rendered"}
    }
    DisposableEffect(bitmap) { val toDispose=bitmap; onDispose {toDispose?.recycle()} }
    Box(modifier.background(Color(0xFFE8EBF1)).verticalScroll(rememberScrollState()).padding(12.dp),contentAlignment=Alignment.TopCenter) {
        val b=bitmap
        if(b==null) {
            Box(Modifier.fillMaxWidth().height(320.dp),contentAlignment=Alignment.Center) {
                if(error==null) CircularProgressIndicator() else Text(error ?: "Unknown error")
            }
        } else {
            var pageSize by remember(page) { mutableStateOf(IntSize.Zero) }
            var draft by remember(page) { mutableStateOf<InkStroke?>(null) }
            val inkModifier=if(tool==PenTool.READ) Modifier.pointerInput(page) {
                detectTransformGestures { _, _, pinch, _ ->onZoom((currentZoom*pinch).coerceIn(1f,3f)) }
            } else Modifier.pointerInput(tool,color,page,pageSize,strokes) {
                fun toNorm(p:Offset):InkPoint = InkPoint(
                    (p.x/pageSize.width.coerceAtLeast(1)).coerceIn(0f,1f),
                    (p.y/pageSize.height.coerceAtLeast(1)).coerceIn(0f,1f))
                detectDragGestures(
                    onDragStart={pt ->
                        if(tool==PenTool.ERASE) {
                            val where=toNorm(pt)
                            val next=eraseClosest(strokes,where)
                            if(next.size!=strokes.size) onInk(next)
                        } else draft=InkStroke(listOf(toNorm(pt)),if(tool==PenTool.HIGHLIGHT) InkYellow else color,
                            if(tool==PenTool.HIGHLIGHT) 0.030f else 0.0037f,tool==PenTool.HIGHLIGHT,
                            SystemClock.elapsedRealtime())
                    },
                    onDragEnd={
                        val s=draft
                        if(s!=null && s.points.size>=2) onInk(strokes+s)
                        draft=null
                    },
                    onDragCancel={draft=null}
                ) { change,_ ->
                    change.consume()
                    val pt=toNorm(change.position)
                    if(tool==PenTool.ERASE) {
                        val next=eraseClosest(strokes,pt)
                        if(next.size!=strokes.size) onInk(next)
                    } else draft=draft?.copy(points=draft!!.points+pt)
                }
            }
            Box(Modifier.fillMaxWidth().graphicsLayer(scaleX=zoom,scaleY=zoom,transformOrigin=androidx.compose.ui.graphics.TransformOrigin(0.5f,0f))
                .aspectRatio(b.width.toFloat()/b.height.toFloat()).background(Color.White)
                .then(inkModifier).onSizeChanged { pageSize=it }) {
                Image(bitmap=b.asImageBitmap(),contentDescription="PDF page ${page+1}",modifier=Modifier.fillMaxSize(),contentScale=ContentScale.FillBounds)
                Canvas(Modifier.fillMaxSize()) {
                    fun drawInk(s:InkStroke) {
                        if(s.points.isEmpty()) return
                        val path=Path().apply {
                            s.points.forEachIndexed { i,p ->
                                if(i==0) moveTo(p.x*size.width,p.y*size.height)
                                else lineTo(p.x*size.width,p.y*size.height)
                            }
                        }
                        drawPath(path,Color(s.color).copy(alpha=if(s.highlighter) 0.38f else 1f),
                            style=Stroke(width=s.width*size.width,cap=StrokeCap.Round,join=StrokeJoin.Round))
                    }
                    strokes.forEach(::drawInk);draft?.let(::drawInk)
                }
            }
        }
    }
}

private fun eraseClosest(strokes:List<InkStroke>,point:InkPoint):List<InkStroke> {
    val index=strokes.indexOfLast { s -> s.points.any { pt ->
        (pt.x-point.x).pow(2)+(pt.y-point.y).pow(2)<0.0010f
    } }
    return if(index<0) strokes else strokes.filterIndexed { i,_->i!=index }
}

@Composable
private fun SidePanel(
    doc:LibraryDoc,lib:LocalLibrary,page:Int,bookmarks:Set<Int>,sessions:List<LectureSession>,session:LectureSession?,
    selectSession:(LectureSession)->Unit,recording:RecordingUiState,hasRecording:Boolean,
    hasModel:Boolean,language:String,onLanguage:(String)->Unit,
    tab:Int,onTab:(Int)->Unit,processing:Boolean,
    onRecordToggle:()->Unit,onRetranscribe:(LectureSession)->Unit,onExportText:(String)->Unit,
    onExportLiveNotes:(String)->Unit,
    onExportAudio:(String)->Unit,onDeleteSession:(String)->Unit,onSeekPage:(Int)->Unit,
    onSessionUpdate:(LectureSession)->Unit,modifier:Modifier=Modifier
) {
    var notes by remember(doc.id,page) {mutableStateOf(lib.readNote(doc.id,page))}
    var filter by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf<LectureSession?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {onDispose {player?.release()} }
    Column(modifier.background(Color.White)) {
        Row(Modifier.fillMaxWidth().padding(start=12.dp,end=8.dp,top=8.dp,bottom=3.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("LECTURE DESK",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.labelMedium,
                color=Color(0xFF4F6FA4),modifier=Modifier.weight(1f))
            val recColor=if(hasRecording) Color(0xFFE44343) else Color(0xFF244E89)
            FilledTonalButton(onClick=onRecordToggle,contentPadding=PaddingValues(horizontal=10.dp,vertical=5.dp),
                colors=ButtonDefaults.filledTonalButtonColors(containerColor=if(hasRecording) Color(0xFFFDE7E7) else Color(0xFFE5EDFF))) {
                Icon(if(hasRecording) Icons.Default.Stop else Icons.Default.Mic,null,Modifier.size(17.dp),tint=recColor)
                Spacer(Modifier.width(4.dp))
                Text(if(hasRecording) "Stop" else "Record",color=recColor)
            }
        }
        if(hasRecording) Text("● Recording · ${if(recording.elapsedStart>0) humanTime(SystemClock.elapsedRealtime()-recording.elapsedStart) else "Starting…"}",
            Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.labelSmall,color=Color(0xFFC73939))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=10.dp),
            horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            FilterChip(selected=language=="en",onClick={onLanguage("en")},label={Text("EN")})
            FilterChip(selected=language=="de",onClick={onLanguage("de")},label={Text("DE")})
            Text(if(hasModel) "Offline ASR ready" else "Import model for ASR",style=MaterialTheme.typography.labelSmall,
                color=if(hasModel) Color(0xFF2B8A52) else Color(0xFFAF6917),
                modifier=Modifier.align(Alignment.CenterVertically))
        }
        TabRow(selectedTabIndex=tab) {
            listOf("Page notes","Transcript","Live notes","Bookmarks").forEachIndexed { index,label ->
                Tab(selected=tab==index,onClick={onTab(index)},text={Text(label,maxLines=1,style=MaterialTheme.typography.labelSmall)})
            }
        }
        when(tab) {
            0 -> Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text("Page ${page+1} notes",fontWeight=FontWeight.SemiBold)
                Text("Autosaved privately on this device",style=MaterialTheme.typography.labelSmall,color=Color.Gray)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value=notes,onValueChange={notes=it;lib.saveNote(doc.id,page,it)},
                    modifier=Modifier.fillMaxSize(),placeholder={Text("Write definitions, formula explanations, exam questions…")})
            }
            1 -> {
                Column(Modifier.fillMaxSize()) {
                    if(sessions.isNotEmpty()) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start=8.dp,top=4.dp),
                            horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            sessions.forEachIndexed { index,s ->
                                FilterChip(selected=session?.id==s.id,onClick={
                                    player?.release();player=null;playingId=null
                                    selectSession(s)
                                },label={Text("Lecture ${sessions.size-index}")})
                            }
                        }
                    }
                    if(session==null) {
                        Box(Modifier.fillMaxSize().padding(12.dp),contentAlignment=Alignment.Center) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                                Icon(Icons.Default.GraphicEq,null,Modifier.size(34.dp),tint=Color(0xFF5782CA))
                                Text("No lecture sessions yet",fontWeight=FontWeight.SemiBold)
                                Text("Tap Record while reading this PDF",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Text(humanTime(session.durationMs),fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
                            IconButton(onClick={
                                try {
                                    val existing=player
                                    if(playingId==session.id && existing!=null) {
                                        if(existing.isPlaying) existing.pause() else existing.start()
                                    } else {
                                        existing?.release()
                                        player=MediaPlayer().apply {
                                            setDataSource(lib.recordingFile(doc.id,session.id).absolutePath)
                                            prepare()
                                            setOnCompletionListener { playingId=null }
                                            start()
                                        }
                                        playingId=session.id
                                    }
                                } catch(e:Exception) {playingId=null;player?.release();player=null}
                            }) {Icon(if(playingId==session.id && player?.isPlaying==true) Icons.Default.Pause else Icons.Default.PlayArrow,"Play / Pause")}
                            IconButton(onClick={onExportText(session.id)}) {Icon(Icons.Default.Description,"Save transcript TXT")}
                            IconButton(onClick={onExportAudio(session.id)}) {Icon(Icons.Default.FileDownload,"Save lecture WAV")}
                            IconButton(onClick={confirmDelete=session}) {Icon(Icons.Default.DeleteOutline,"Delete recording")}
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=2.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick={onRetranscribe(session)},enabled=hasModel && !processing && !hasRecording,
                                modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=6.dp)) {
                                Icon(Icons.Default.AutoAwesome,null,Modifier.size(15.dp));Spacer(Modifier.width(3.dp));Text("Transcribe offline",fontSize=12.sp)
                            }
                            if(processing) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                        }
                        OutlinedTextField(filter,{filter=it},Modifier.fillMaxWidth().padding(horizontal=9.dp),
                            singleLine=true,label={Text("Search transcript")})
                        val hits=session.segments.withIndex().filter { it.value.text.contains(filter,ignoreCase=true) }
                        if(hits.isEmpty()) {
                            Box(Modifier.fillMaxSize().padding(12.dp),contentAlignment=Alignment.Center) {
                                Text(if(session.segments.isEmpty()) "No transcript yet. Install a Vosk model and tap Transcribe offline."
                                    else "No matching words",style=MaterialTheme.typography.bodySmall)
                            }
                        } else LazyColumn(contentPadding=PaddingValues(9.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            items(hits,key={it.index}) { indexed ->
                                val seg=indexed.value
                                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFF6F8FC))) {
                                    Column(Modifier.fillMaxWidth().padding(9.dp)) {
                                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                            TextButton(onClick={
                                                onSeekPage(seg.page)
                                                try {
                                                    if(playingId!=session.id || player==null) {
                                                        player?.release()
                                                        player=MediaPlayer().apply {
                                                            setDataSource(lib.recordingFile(doc.id,session.id).absolutePath);prepare()
                                                        }
                                                    }
                                                    player?.seekTo(seg.startMs.toInt());player?.start();playingId=session.id
                                                } catch(_:Exception) {playingId=null}
                                            },contentPadding=PaddingValues(0.dp)) {
                                                Icon(Icons.Default.PlayCircle,null,Modifier.size(17.dp))
                                                Text(" ${humanTime(seg.startMs)}",fontSize=12.sp)
                                            }
                                            Spacer(Modifier.weight(1f))
                                            Text("Page ${seg.page+1}",style=MaterialTheme.typography.labelSmall,color=Color.Gray)
                                        }
                                        OutlinedTextField(seg.text,onValueChange={ text ->
                                            val newer=session.segments.toMutableList()
                                            newer[indexed.index]=seg.copy(text=text)
                                            onSessionUpdate(session.copy(segments=newer))
                                        },modifier=Modifier.fillMaxWidth(),textStyle=MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                // Show interim speech and persisted, automatically drafted notes.
                val liveSession=if(hasRecording && recording.sessionId!=null)
                    sessions.firstOrNull { it.id==recording.sessionId } ?: session
                    else session
                val written=remember(doc.id,liveSession?.id,liveSession?.segments?.size) {
                    liveSession?.let { lib.readGeneratedNotes(doc.id,it.id) }.orEmpty()
                }
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.AutoAwesome,null,tint=Color(0xFF2167CE))
                        Spacer(Modifier.width(8.dp))
                        Text("Live lecture notes",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                        if(hasRecording) Text("● LIVE",color=Color(0xFFD13C3C),
                            style=MaterialTheme.typography.labelSmall)
                        if(liveSession!=null) IconButton(onClick={onExportLiveNotes(liveSession.id)},
                            enabled=written.isNotBlank()) {
                            Icon(Icons.Default.SaveAlt,"Export live notes to TXT")
                        }
                    }
                    Text("Offline speech notes • timestamped and linked to PDF pages",
                        style=MaterialTheme.typography.labelSmall,color=Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    if(hasRecording) {
                        Surface(color=Color(0xFFEAF1FF),shape=RoundedCornerShape(12.dp)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                Text("LIVE RECOGNITION",fontWeight=FontWeight.Bold,
                                    style=MaterialTheme.typography.labelSmall,color=Color(0xFF275798))
                                Text(recording.partial.ifBlank {
                                    if(hasModel) "Listening… live words will appear here."
                                    else "Import an offline EN/DE Vosk model to enable live words."
                                },style=MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    if(written.isBlank()) {
                        Text("Recognized phrases become notes automatically while recording. Use Page notes to add your own explanations.",
                            style=MaterialTheme.typography.bodySmall,color=Color.Gray)
                    } else {
                        SelectionContainer {
                            Text(written,modifier=Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                style=MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            else -> {
                val pages=bookmarks.sorted()
                if(pages.isEmpty()) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                    Text("Bookmark pages using the icon above",style=MaterialTheme.typography.bodySmall)
                } else LazyColumn {
                    items(pages) { n ->
                        ListItem(headlineContent={Text("Page ${n+1}")},leadingContent={Icon(Icons.Default.Bookmark,null,tint=Color(0xFFE9A03D))},
                            modifier=Modifier.clickable{onSeekPage(n)})
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    if(confirmDelete!=null) AlertDialog(onDismissRequest={confirmDelete=null},title={Text("Delete lecture recording?")},
        text={Text("The local WAV recording and transcript will be permanently deleted.")},
        confirmButton={TextButton(onClick={
            val id=confirmDelete!!.id
            player?.release();player=null;playingId=null
            onDeleteSession(id);confirmDelete=null
        }){Text("Delete")}},dismissButton={TextButton(onClick={confirmDelete=null}){Text("Cancel")}})
}
