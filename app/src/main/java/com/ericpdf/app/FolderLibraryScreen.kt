package com.ericpdf.app

import android.graphics.Bitmap
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Paper=Color(0xFFF6F8FC)
private val FolderBlue=Color(0xFF528BD0)
private val InkNavy=Color(0xFF152A48)

@Composable
fun FolderLibraryScreen(
    lib:LocalLibrary,docs:List<LibraryDoc>,folders:List<LibraryFolder>,
    notice:String,busy:Boolean,onOpen:(String)->Unit,onImport:(String?)->Unit,
    onSettings:()->Unit,onCreateFolder:(String,String?)->Unit,
    onRenameFolder:(String,String)->Unit,onMoveFolder:(String,String?)->Unit,
    onDeleteFolder:(String)->Unit,onMoveDoc:(String,String?)->Unit,
    onRenameDoc:(String,String)->Unit,onDeleteDoc:(LibraryDoc)->Unit,
    onDismissNotice:()->Unit
) {
    var folderId by rememberSaveable {mutableStateOf<String?>(null)}
    var query by rememberSaveable {mutableStateOf("")}
    var sort by rememberSaveable {mutableStateOf("Recent")}
    var grid by rememberSaveable {mutableStateOf(true)}
    var newFolder by remember {mutableStateOf(false)}
    var editingFolder by remember {mutableStateOf<LibraryFolder?>(null)}
    var editingDoc by remember {mutableStateOf<LibraryDoc?>(null)}
    var renameValue by remember {mutableStateOf("")}
    var moveFolder by remember {mutableStateOf<LibraryFolder?>(null)}
    var moveDoc by remember {mutableStateOf<LibraryDoc?>(null)}
    var deleteFolder by remember {mutableStateOf<LibraryFolder?>(null)}
    var about by remember {mutableStateOf(false)}
    val context=LocalContext.current
    val index=remember(folders){folders.associateBy{it.id}}
    LaunchedEffect(folders,folderId) {
        if(folderId!=null && folderId !in index) folderId=null
    }
    BackHandler(enabled=folderId!=null) {folderId=index[folderId]?.parentId}
    val searching=query.isNotBlank()
    val currentFolders=if(searching) folders.filter {it.name.contains(query,true)}
        else folders.filter {it.parentId==folderId}
    val visibleDocs=(if(searching) docs.filter {d->
        d.title.contains(query,true) || d.course.contains(query,true) ||
            generateSequence(d.folderId) {index[it]?.parentId}.take(20)
                .any {index[it]?.name?.contains(query,true)==true}
    } else docs.filter {it.folderId==folderId || (folderId==null && it.folderId!=null && it.folderId !in index)})
        .let {found->when(sort) {
            "Name" -> found.sortedBy {it.title.lowercase()}
            "Course" -> found.sortedWith(compareBy<LibraryDoc>{it.course}.thenBy{it.title})
            else -> found.sortedByDescending{it.createdAt}
        }}
    val sortedFolders=currentFolders.sortedBy{it.name.lowercase()}
    val breadcrumbs=remember(folderId,folders) {
        val parts=mutableListOf<LibraryFolder>()
        val visited=mutableSetOf<String>()
        var at=folderId
        while(at!=null && visited.add(at)) {
            val entry=index[at] ?: break
            parts.add(entry)
            at=entry.parentId
        }
        parts.reversed()
    }

    Column(Modifier.fillMaxSize().background(Paper)) {
        Surface(color=Color.White,shadowElevation=2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal=15.dp,vertical=9.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.FolderCopy,null,tint=FolderBlue,modifier=Modifier.size(27.dp))
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text("EricPDF",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=InkNavy)
                        Text("My documents",style=MaterialTheme.typography.labelSmall,color=Color.Gray)
                    }
                    IconButton(onClick={about=true}) {Icon(Icons.Default.InfoOutline,"About EricPDF")}
                    IconButton(onClick=onSettings) {Icon(Icons.Default.Settings,"Settings and backup")}
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment=Alignment.CenterVertically) {
                    TextButton(onClick={folderId=null}) {
                        Icon(Icons.Default.Home,null,Modifier.size(17.dp))
                        Text(" Documents")
                    }
                    breadcrumbs.forEach {entry->
                        Icon(Icons.Default.ChevronRight,null,Modifier.size(18.dp),tint=Color.Gray)
                        TextButton(onClick={folderId=entry.id}) {
                            Text(entry.name,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if(notice.isNotBlank()) Surface(color=Color(0xFFE6EEFF)) {
            Row(Modifier.fillMaxWidth().padding(start=12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(notice,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                IconButton(onClick=onDismissNotice){Icon(Icons.Default.Close,"Dismiss")}
            }
        }
        OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=7.dp),
            singleLine=true,placeholder={Text("Search all PDFs and folders")},
            leadingIcon={Icon(Icons.Default.Search,null)},
            trailingIcon=if(query.isNotEmpty()) {{IconButton(onClick={query=""}){Icon(Icons.Default.Close,"Clear search")}}} else null,
            shape=RoundedCornerShape(15.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal=14.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically) {
            FilledTonalButton(onClick={newFolder=true}) {
                Icon(Icons.Default.CreateNewFolder,null,Modifier.size(19.dp))
                Spacer(Modifier.width(5.dp));Text("New folder")
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick={onImport(folderId)},enabled=!busy) {
                Icon(Icons.Default.Add,null,Modifier.size(19.dp))
                Text(" PDF")
            }
            Spacer(Modifier.width(8.dp))
            Box {
                var sortMenu by remember {mutableStateOf(false)}
                TextButton(onClick={sortMenu=true}) {
                    Icon(Icons.Default.Sort,null,Modifier.size(18.dp));Text(" $sort")
                }
                DropdownMenu(expanded=sortMenu,onDismissRequest={sortMenu=false}) {
                    listOf("Recent","Name","Course").forEach {selection->
                        DropdownMenuItem(text={Text(selection)},onClick={sort=selection;sortMenu=false})
                    }
                }
            }
            IconButton(onClick={grid=!grid}) {Icon(if(grid) Icons.Default.ViewList else Icons.Default.GridView,"Toggle grid / list")}
        }
        val nCols=if(grid) 3 else 1
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cols=if(!grid) 1 else if(maxWidth>=600.dp) 3 else if(maxWidth>=380.dp) 2 else 1
            LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=12.dp,end=12.dp,bottom=90.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)) {
                if(sortedFolders.isNotEmpty()) {
                    item {SectionLabel(if(searching) "MATCHING FOLDERS" else "FOLDERS",sortedFolders.size)}
                    items(sortedFolders.chunked(cols)) {rowFolders->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            rowFolders.forEach {f->
                                FolderTile(f,Modifier.weight(1f),onOpen={folderId=f.id},
                                    onRename={editingFolder=f;renameValue=f.name},
                                    onMove={moveFolder=f},onDelete={deleteFolder=f})
                            }
                            repeat(cols-rowFolders.size){Spacer(Modifier.weight(1f))}
                        }
                    }
                }
                if(visibleDocs.isNotEmpty()) {
                    item {SectionLabel(if(searching) "MATCHING PDF DOCUMENTS" else "PDF DOCUMENTS",visibleDocs.size)}
                    items(visibleDocs.chunked(cols)) {rowDocs->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            rowDocs.forEach {d->
                                DocumentTile(d,lib,grid,Modifier.weight(1f),onOpen={onOpen(d.id)},
                                    onMove={moveDoc=d},onRename={editingDoc=d;renameValue=d.title},
                                    onDelete={onDeleteDoc(d)})
                            }
                            repeat(cols-rowDocs.size){Spacer(Modifier.weight(1f))}
                        }
                    }
                }
                if(sortedFolders.isEmpty() && visibleDocs.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top=65.dp),
                        horizontalAlignment=Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOpen,null,Modifier.size(64.dp),tint=FolderBlue)
                        Text(if(searching) "No matching files" else "This folder is empty",
                            fontWeight=FontWeight.Bold)
                        Text(if(searching) "Search by title, course or folder name"
                             else "Add a PDF or create a subfolder",color=Color.Gray)
                    }
                }
                item {
                    Text("Developed by innovateX · EricPDF ${ericPdfVersion(context)}",
                        modifier=Modifier.fillMaxWidth().padding(top=30.dp),
                        style=MaterialTheme.typography.labelSmall,color=Color.Gray,
                        textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
    if(newFolder) {
        var name by remember {mutableStateOf("")}
        AlertDialog(onDismissRequest={newFolder=false},
            title={Text("New folder")},text={
                OutlinedTextField(name,{name=it},singleLine=true,label={Text("Folder name")},
                    placeholder={Text("e.g. TUHH / Microsystem")})
            },confirmButton={TextButton(onClick={
                onCreateFolder(name,folderId);newFolder=false
            },enabled=name.isNotBlank()){Text("Create")}},
            dismissButton={TextButton(onClick={newFolder=false}){Text("Cancel")}})
    }
    if(editingFolder!=null || editingDoc!=null) AlertDialog(
        onDismissRequest={editingFolder=null;editingDoc=null},
        title={Text("Rename")},text={OutlinedTextField(renameValue,{renameValue=it},
            label={Text("Name")},singleLine=true)},
        confirmButton={TextButton(onClick={
            editingFolder?.let{onRenameFolder(it.id,renameValue)}
            editingDoc?.let{onRenameDoc(it.id,renameValue)}
            editingFolder=null;editingDoc=null
        },enabled=renameValue.isNotBlank()){Text("Save")}},
        dismissButton={TextButton(onClick={editingFolder=null;editingDoc=null}){Text("Cancel")}})
    if(moveFolder!=null || moveDoc!=null) AlertDialog(
        onDismissRequest={moveFolder=null;moveDoc=null},
        title={Text(if(moveDoc!=null) "Move PDF to…" else "Move folder to…")},
        text={
            Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
                TextButton(onClick={
                    moveDoc?.let{onMoveDoc(it.id,null)}
                    moveFolder?.let{onMoveFolder(it.id,null)}
                    moveDoc=null;moveFolder=null
                }){Icon(Icons.Default.Home,null);Text("  Documents (root)")}
                val ordered=mutableListOf<Pair<LibraryFolder,Int>>()
                fun traverse(parent:String?,depth:Int,seen:MutableSet<String>) {
                    folders.filter{it.parentId==parent}.sortedBy{it.name.lowercase()}.forEach {item->
                        if(seen.add(item.id)) {
                            ordered.add(item to depth)
                            traverse(item.id,depth+1,seen)
                        }
                    }
                }
                traverse(null,0,mutableSetOf())
                ordered.forEach {(target,depth)->
                    val selectedFolder=moveFolder
                    if(target.id!=selectedFolder?.id) TextButton(onClick={
                        moveDoc?.let{onMoveDoc(it.id,target.id)}
                        moveFolder?.let{onMoveFolder(it.id,target.id)}
                        moveDoc=null;moveFolder=null
                    },modifier=Modifier.fillMaxWidth()) {
                        Spacer(Modifier.width((depth.coerceAtMost(8)*14).dp))
                        Icon(Icons.Default.Folder,null,Modifier.size(19.dp))
                        Text(" ${target.name}",maxLines=1,overflow=TextOverflow.Ellipsis)
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        },confirmButton={TextButton(onClick={moveFolder=null;moveDoc=null}){Text("Cancel")}})
    if(deleteFolder!=null) AlertDialog(onDismissRequest={deleteFolder=null},
        title={Text("Delete empty folder?")},
        text={Text("Only empty folders can be deleted. Move PDFs and subfolders first.")},
        confirmButton={TextButton(onClick={deleteFolder?.let{onDeleteFolder(it.id)};deleteFolder=null}){Text("Delete")}},
        dismissButton={TextButton(onClick={deleteFolder=null}){Text("Cancel")}})
    if(about) EricPdfAbout(onDismiss={about=false})
}

@Composable
private fun SectionLabel(name:String,count:Int) {
    Row(Modifier.fillMaxWidth().padding(top=10.dp,bottom=3.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(name,color=Color(0xFF4B6998),fontWeight=FontWeight.Bold,
            style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
        Text("$count",color=Color.Gray,style=MaterialTheme.typography.labelSmall)
    }
}
@Composable
private fun FolderTile(folder:LibraryFolder,modifier:Modifier,onOpen:()->Unit,
    onRename:()->Unit,onMove:()->Unit,onDelete:()->Unit) {
    var menu by remember {mutableStateOf(false)}
    Card(modifier=modifier,shape=RoundedCornerShape(13.dp),
        colors=CardDefaults.cardColors(containerColor=Color.White)) {
        Box {
            Column(Modifier.fillMaxWidth().clickable(onClick=onOpen)
                .padding(horizontal=11.dp,vertical=13.dp),
                horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Icons.Default.Folder,null,tint=FolderBlue,modifier=Modifier.size(56.dp))
                Text(folder.name,fontWeight=FontWeight.SemiBold,maxLines=2,
                    overflow=TextOverflow.Ellipsis,minLines=2)
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick={menu=true},modifier=Modifier.size(34.dp)) {
                    Icon(Icons.Default.MoreVert,"Folder actions",modifier=Modifier.size(18.dp))
                }
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    DropdownMenuItem(text={Text("Rename")},onClick={menu=false;onRename()})
                    DropdownMenuItem(text={Text("Move")},onClick={menu=false;onMove()})
                    DropdownMenuItem(text={Text("Delete empty folder")},onClick={menu=false;onDelete()})
                }
            }
        }
    }
}
@Composable
private fun DocumentTile(doc:LibraryDoc,lib:LocalLibrary,grid:Boolean,modifier:Modifier,
    onOpen:()->Unit,onMove:()->Unit,onRename:()->Unit,onDelete:()->Unit) {
    var menu by remember {mutableStateOf(false)}
    Card(modifier=modifier,shape=RoundedCornerShape(13.dp),
        colors=CardDefaults.cardColors(containerColor=Color.White)) {
        Box {
            Column(Modifier.fillMaxWidth().clickable(onClick=onOpen).padding(10.dp)) {
                if(grid) {
                    val preview by produceState<Bitmap?>(null,doc.id) {
                        value=runCatching {withContext(Dispatchers.IO) {
                            PdfTools.render(lib.sourceFile(doc.id),0,maxWidth=300)
                        }}.getOrNull()
                    }
                    Box(Modifier.fillMaxWidth().height(145.dp).background(Color(0xFFF0F2F6),RoundedCornerShape(8.dp)),
                        contentAlignment=Alignment.Center) {
                        if(preview!=null) Image(preview!!.asImageBitmap(),"First page preview",
                            modifier=Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                        else Icon(Icons.Default.PictureAsPdf,null,tint=FolderBlue,modifier=Modifier.size(36.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(doc.title,fontWeight=FontWeight.SemiBold,maxLines=2,
                    overflow=TextOverflow.Ellipsis)
                Text("${doc.course} · ${doc.pageCount} pages",style=MaterialTheme.typography.labelSmall,
                    color=Color.Gray,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(DateFormat.getDateInstance().format(Date(doc.createdAt)),
                    style=MaterialTheme.typography.labelSmall,color=Color.Gray)
            }
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick={menu=true},modifier=Modifier.size(34.dp)) {
                    Icon(Icons.Default.MoreVert,"PDF actions",modifier=Modifier.size(18.dp))
                }
                DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                    DropdownMenuItem(text={Text("Move to folder")},onClick={menu=false;onMove()})
                    DropdownMenuItem(text={Text("Rename")},onClick={menu=false;onRename()})
                    DropdownMenuItem(text={Text("Delete")},onClick={menu=false;onDelete()})
                }
            }
        }
    }
}

fun ericPdfVersion(context:android.content.Context):String =
    runCatching {context.packageManager.getPackageInfo(context.packageName,0).versionName}.getOrNull() ?: "Unknown"

@Composable
fun EricPdfAbout(onDismiss:()->Unit) {
    val context=LocalContext.current
    AlertDialog(onDismissRequest=onDismiss,title={Text("About EricPDF")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
                Text("EricPDF · Version ${ericPdfVersion(context)}",
                    style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
                Text("Developed by innovateX",fontWeight=FontWeight.SemiBold)
                Text("Developer: Eric Scott @ Aung Phone Myat")
                TextButton(onClick={
                    context.startActivity(Intent(Intent.ACTION_SENDTO,
                        Uri.parse("mailto:ericscott.de@gmail.com")))
                }) {
                    Icon(Icons.Default.Email,null,Modifier.size(18.dp))
                    Text(" ericscott.de@gmail.com")
                }
                Text("Local-first PDF annotation, lecture recording and transcription.",
                    style=MaterialTheme.typography.bodySmall)
            }
        },confirmButton={TextButton(onClick=onDismiss){Text("Close")}})
}
