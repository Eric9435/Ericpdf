package com.ericpdf.app

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import android.graphics.pdf.PdfRenderer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class LibraryDoc(
    val id: String, val title: String, val course: String, val createdAt: Long,
    val pageCount: Int, val lastPage: Int = 0, val folderId: String? = null
)
data class LibraryFolder(
    val id: String, val name: String, val parentId: String?, val createdAt: Long
)
data class InkPoint(val x: Float, val y: Float)
data class InkStroke(
    val points: List<InkPoint>, val color: Int, val width: Float,
    val highlighter: Boolean = false, val recordedAtMs: Long = -1L
)
data class TranscriptSegment(
    val startMs: Long, val endMs: Long, val text: String, val page: Int
)
data class LectureSession(
    val id: String, val createdAt: Long, val durationMs: Long, val language: String,
    val segments: List<TranscriptSegment>
)
data class AudioCue(val timeMs: Long, val page: Int)

class LocalLibrary(private val context: Context) {
    private val root = File(context.filesDir, "ericpdf/library").apply { mkdirs() }
    private val models = File(context.filesDir, "ericpdf/models").apply { mkdirs() }
    fun modelDirectory(language: String) = File(models, if (language == "de") "de" else "en")
    fun documentDirectory(id: String): File = File(root, id.also { require(it.matches(Regex("[a-f0-9-]{36}"))) })
    fun sourceFile(id: String) = File(documentDirectory(id), "document.pdf")
    fun recordingFile(docId: String, sessionId: String): File {
        require(sessionId.matches(Regex("[a-f0-9-]{36}")))
        return File(documentDirectory(docId), "audio/$sessionId.wav")
    }
    private fun manifest(docId: String) = File(documentDirectory(docId), "document.json")
    private fun inkFile(docId: String, page: Int) = File(documentDirectory(docId), "ink/$page.json")
    private fun sessionFile(docId: String, sessionId: String): File {
        require(sessionId.matches(Regex("[a-f0-9-]{36}")))
        return File(documentDirectory(docId), "audio/$sessionId.json")
    }
    private fun generatedNotesFile(docId:String,sessionId:String):File {
        require(sessionId.matches(Regex("[a-f0-9-]{36}")))
        return File(documentDirectory(docId),"audio/$sessionId-live-notes.txt")
    }
    fun readGeneratedNotes(docId:String,sessionId:String):String =
        runCatching { generatedNotesFile(docId,sessionId).readText() }.getOrDefault("")
    fun saveGeneratedNotes(docId:String,sessionId:String,content:String) =
        writeAtomic(generatedNotesFile(docId,sessionId),content)
    private fun cuesFile(docId: String, sessionId: String): File {
        require(sessionId.matches(Regex("[a-f0-9-]{36}")))
        return File(documentDirectory(docId), "audio/$sessionId-cues.json")
    }

    fun importPdf(uri: Uri, title: String, course: String = "Unsorted", folderId: String? = null): LibraryDoc {
        require(folderId==null || folders().any {it.id==folderId}) { "Choose a valid folder" }
        val id = UUID.randomUUID().toString()
        val dir = documentDirectory(id).apply { mkdirs() }
        try {
            val target = File(dir, "document.pdf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: error("Cannot read selected document")
            require(target.length() in 5..(400L * 1024 * 1024)) { "PDF must be 1–400 MB" }
            val magic = target.inputStream().use { ByteArray(5).also { bytes -> it.read(bytes) } }
            require(String(magic, Charsets.US_ASCII) == "%PDF-") { "This is not a PDF" }
            val pages = ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { it.pageCount }
            }
            val doc = LibraryDoc(id, title.ifBlank { "Untitled PDF" }.take(150), course.ifBlank { "Unsorted" }.take(90), System.currentTimeMillis(), pages, folderId=folderId)
            writeAtomic(manifest(id), encodeDoc(doc).toString())
            return doc
        } catch (e: Exception) { dir.deleteRecursively(); throw e }
    }
    fun list(): List<LibraryDoc> = root.listFiles().orEmpty().mapNotNull { dir ->
        runCatching { decodeDoc(JSONObject(File(dir, "document.json").readText())) }.getOrNull()
    }.sortedByDescending { it.createdAt }
    fun get(id: String): LibraryDoc = decodeDoc(JSONObject(manifest(id).readText()))
    fun update(doc: LibraryDoc) = writeAtomic(manifest(doc.id), encodeDoc(doc).toString())
    fun delete(id: String) {
        documentDirectory(id).deleteRecursively()
        AutoBackup.changed(context,now=true)
    }

    private val folderFile = File(root,"folders.json")
    private val uuidPattern=Regex("[a-f0-9-]{36}")

    @Synchronized fun folders():List<LibraryFolder> {
        val arr=readArray(folderFile)
        return (0 until arr.length()).mapNotNull {i->runCatching {
            val obj=arr.getJSONObject(i)
            val id=obj.getString("id")
            val name=obj.getString("name")
            val parent=obj.optString("parentId","").takeUnless {it.isBlank() || it=="null"}
            if(!uuidPattern.matches(id) || name.isBlank() || name.length>90 ||
                (parent!=null && !uuidPattern.matches(parent))) null
            else LibraryFolder(id,name,parent,obj.optLong("createdAt",0L))
        }.getOrNull() }.filterNotNull()
    }
    private fun storeFolders(items:List<LibraryFolder>) {
        val array=JSONArray()
        items.forEach { f -> array.put(JSONObject().put("id",f.id).put("name",f.name)
            .put("parentId",f.parentId ?: JSONObject.NULL).put("createdAt",f.createdAt)) }
        writeAtomic(folderFile,array.toString())
    }
    private fun validateName(name:String):String {
        val clean=name.trim()
        require(clean.isNotEmpty() && clean.length<=90 && clean.none {it=='/' || it=='\\' || it=='\u0000'}) {
            "Folder name must be 1–90 characters with no slashes"
        }
        return clean
    }
    @Synchronized fun createFolder(name:String,parentId:String?):LibraryFolder {
        val items=folders()
        require(parentId==null || items.any {it.id==parentId}) { "Parent folder missing" }
        val clean=validateName(name)
        require(items.none {it.parentId==parentId && it.name.equals(clean,true)}) { "Folder already exists" }
        return LibraryFolder(UUID.randomUUID().toString(),clean,parentId,System.currentTimeMillis()).also {
            storeFolders(items+it)
        }
    }
    @Synchronized fun renameFolder(id:String,name:String) {
        val items=folders()
        require(items.any {it.id==id}) { "Folder missing" }
        val clean=validateName(name)
        val current=items.first {it.id==id}
        require(items.none {it.id!=id && it.parentId==current.parentId && it.name.equals(clean,true)}) {
            "Folder already exists"
        }
        storeFolders(items.map {if(it.id==id) it.copy(name=clean) else it})
    }
    @Synchronized fun moveFolder(id:String,newParentId:String?) {
        val items=folders()
        require(items.any {it.id==id}) { "Folder missing" }
        require(newParentId==null || items.any {it.id==newParentId}) { "Destination missing" }
        var ancestor=newParentId
        val visited=mutableSetOf<String>()
        while(ancestor!=null) {
            require(ancestor!=id && visited.add(ancestor)) { "Cannot move a folder inside itself" }
            ancestor=items.firstOrNull {it.id==ancestor}?.parentId
        }
        val selected=items.first {it.id==id}
        require(items.none {it.id!=id && it.parentId==newParentId && it.name.equals(selected.name,true)}) {
            "Destination already contains a folder with this name"
        }
        storeFolders(items.map {if(it.id==id) it.copy(parentId=newParentId) else it})
    }
    @Synchronized fun deleteFolder(id:String) {
        val items=folders()
        require(items.any {it.id==id}) { "Folder missing" }
        require(items.none {it.parentId==id}) { "Move subfolders out first" }
        require(list().none {it.folderId==id}) { "Move documents out first" }
        storeFolders(items.filterNot {it.id==id})
    }
    fun moveDocument(id:String,folderId:String?) {
        require(folderId==null || folders().any {it.id==folderId}) { "Destination folder missing" }
        update(get(id).copy(folderId=folderId))
    }
    fun renameDocument(id:String,newName:String) {
        val name=newName.trim()
        require(name.isNotBlank() && name.length<=150) { "Name must be 1–150 characters" }
        update(get(id).copy(title=name))
    }
    fun folderMetadata():String = if(folderFile.isFile) folderFile.readText() else "[]"
    @Synchronized fun mergeFolderMetadata(raw:String) {
        require(raw.length<=2_000_000) { "Too much folder metadata" }
        val array=JSONArray(raw)
        require(array.length()<=10000) { "Too many folders" }
        val incoming=(0 until array.length()).map {i->
            val obj=array.getJSONObject(i)
            val id=obj.getString("id")
            require(uuidPattern.matches(id)) { "Invalid folder identifier" }
            val name=validateName(obj.getString("name"))
            val parent=obj.optString("parentId","").takeUnless {it.isBlank() || it=="null"}
            require(parent==null || uuidPattern.matches(parent)) { "Invalid parent folder" }
            LibraryFolder(id,name,parent,obj.optLong("createdAt"))
        }
        require(incoming.distinctBy {it.id}.size==incoming.size) { "Duplicate folder identifier" }
        val merged=(folders()+incoming.filterNot {folder->folders().any {it.id==folder.id}} )
        val index=merged.associateBy {it.id}
        for(folder in incoming) {
            var cursor:String?=folder.id
            val checked=mutableSetOf<String>()
            while(cursor!=null) {
                require(checked.add(cursor)) { "Folder cycle" }
                cursor=index[cursor]?.parentId
            }
        }
        storeFolders(merged)
    }

    fun readInk(id: String, page: Int): List<InkStroke> = readArray(inkFile(id, page)).let { arr ->
        (0 until arr.length()).mapNotNull { i -> runCatching {
            val entry = arr.getJSONObject(i); val points = entry.getJSONArray("points")
            InkStroke((0 until points.length()).map { k ->
                val xy = points.getJSONArray(k); InkPoint(xy.getDouble(0).toFloat(), xy.getDouble(1).toFloat())
            }, entry.getInt("color"), entry.getDouble("width").toFloat(),
                entry.optBoolean("highlighter"), entry.optLong("recordedAtMs", -1))
        }.getOrNull() }
    }
    fun saveInk(id: String, page: Int, strokes: List<InkStroke>) {
        val arr = JSONArray()
        strokes.forEach { stroke ->
            val pts = JSONArray()
            stroke.points.forEach { pts.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) }
            arr.put(JSONObject().put("points", pts).put("color", stroke.color)
                .put("width", stroke.width.toDouble()).put("highlighter", stroke.highlighter)
                .put("recordedAtMs", stroke.recordedAtMs))
        }
        writeAtomic(inkFile(id, page), arr.toString())
    }
    fun readNote(id: String, page: Int): String = runCatching {
        JSONObject(File(documentDirectory(id), "notes.json").readText()).optString(page.toString())
    }.getOrDefault("")
    fun saveNote(id: String, page: Int, text: String) {
        val file = File(documentDirectory(id), "notes.json")
        val obj = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        obj.put(page.toString(), text.take(100_000)); writeAtomic(file, obj.toString())
    }
    fun bookmarks(id: String): Set<Int> = readArray(File(documentDirectory(id), "bookmarks.json")).let { arr ->
        (0 until arr.length()).mapNotNull { runCatching { arr.getInt(it) }.getOrNull() }.toSet()
    }
    fun setBookmark(id: String, page: Int, set: Boolean) {
        val pages = bookmarks(id).toMutableSet(); if (set) pages.add(page) else pages.remove(page)
        writeAtomic(File(documentDirectory(id), "bookmarks.json"), JSONArray(pages.sorted()).toString())
    }
    fun sessions(id: String): List<LectureSession> = File(documentDirectory(id), "audio").listFiles().orEmpty()
        .filter { it.extension == "json" && !it.name.endsWith("-cues.json") }
        .mapNotNull { runCatching { decodeSession(JSONObject(it.readText())) }.getOrNull() }
        .sortedByDescending { it.createdAt }
    fun getSession(docId: String, id: String): LectureSession? = runCatching {
        decodeSession(JSONObject(sessionFile(docId, id).readText()))
    }.getOrNull()
    fun putSession(docId: String, session: LectureSession) = writeAtomic(
        sessionFile(docId, session.id), encodeSession(session).toString()
    )
    fun cues(docId: String, sessionId: String): List<AudioCue> = readArray(cuesFile(docId, sessionId)).let { arr ->
        (0 until arr.length()).mapNotNull { i -> runCatching {
            val item=arr.getJSONObject(i); AudioCue(item.getLong("timeMs"), item.getInt("page"))
        }.getOrNull() }
    }
    fun addCue(docId: String, sessionId: String, cue: AudioCue) {
        val items = cues(docId, sessionId) + cue
        writeAtomic(cuesFile(docId, sessionId), JSONArray().also { arr -> items.forEach {
            arr.put(JSONObject().put("timeMs",it.timeMs).put("page",it.page))
        } }.toString())
    }
    fun deleteSession(docId: String, sessionId: String) {
        recordingFile(docId, sessionId).delete()
        sessionFile(docId, sessionId).delete()
        cuesFile(docId, sessionId).delete()
        generatedNotesFile(docId, sessionId).delete()
        AutoBackup.changed(context,now=true)
    }
    private fun readArray(file: File) = runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
    @Synchronized private fun writeAtomic(dest: File, contents: String) {
        dest.parentFile?.mkdirs()
        val atomic=AtomicFile(dest)
        val stream=atomic.startWrite()
        try {
            stream.write(contents.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e:Exception) {
            atomic.failWrite(stream)
            throw e
        }
        // The local write is now successful. Background backup scheduling must
        // never cause a saved note to be falsely reported as a failed write.
        runCatching { AutoBackup.changed(context) }
    }
    private fun encodeDoc(d: LibraryDoc) = JSONObject().put("id",d.id).put("title",d.title)
        .put("course",d.course).put("createdAt",d.createdAt).put("pageCount",d.pageCount)
        .put("lastPage",d.lastPage).put("folderId",d.folderId ?: JSONObject.NULL)
    private fun decodeDoc(o: JSONObject) = LibraryDoc(o.getString("id"),o.getString("title"),
        o.optString("course","Unsorted"),o.getLong("createdAt"),o.getInt("pageCount"),o.optInt("lastPage"),
        o.optString("folderId","").takeIf {it.isNotBlank() && it!="null"})
    private fun encodeSession(s: LectureSession) = JSONObject().put("id",s.id).put("createdAt",s.createdAt)
        .put("durationMs",s.durationMs).put("language",s.language).put("segments",JSONArray().also { arr ->
            s.segments.forEach { arr.put(JSONObject().put("startMs",it.startMs).put("endMs",it.endMs)
                .put("text",it.text).put("page",it.page)) }
        })
    private fun decodeSession(o:JSONObject): LectureSession {
        val arr=o.optJSONArray("segments") ?: JSONArray()
        return LectureSession(o.getString("id"),o.getLong("createdAt"),o.optLong("durationMs"),
            o.optString("language","en"),(0 until arr.length()).map { i ->
                arr.getJSONObject(i).let { TranscriptSegment(it.getLong("startMs"),it.getLong("endMs"),
                    it.getString("text"),it.optInt("page",0)) }
            })
    }
}
