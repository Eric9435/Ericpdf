package com.ericpdf.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.min

object PdfTools {
    fun pageCount(file: File): Int = ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { it.pageCount }
    }
    fun render(file: File, page: Int, maxWidth: Int=2000): Bitmap =
        ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(page.coerceIn(0,renderer.pageCount-1)).use { p ->
                    val scale=min(2.5f, min(maxWidth.toFloat()/p.width.toFloat(), 2800f/p.height.toFloat()))
                    val w=(p.width*scale).toInt().coerceAtLeast(1)
                    val h=(p.height*scale).toInt().coerceAtLeast(1)
                    val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    fun search(context:Context,file:File,term:String): List<Int> {
        if (term.isBlank()) return emptyList()
        // PDFBox preserves extractable text; scanned/image-only PDFs have no text layer.
        return PDDocument.load(file).use { doc ->
            val stripper=PDFTextStripper()
            (1..doc.numberOfPages).filter { n ->
                stripper.startPage=n; stripper.endPage=n
                stripper.getText(doc).contains(term,ignoreCase=true)
            }.map { it-1 }
        }
    }
    /** Exports *visually* annotated PDF; pages are rasterized. See README on searchable-text trade-off. */
    fun exportAnnotated(context:Context, lib:LocalLibrary, doc:LibraryDoc, dest:Uri) {
        val pdf=PdfDocument()
        try {
            for (i in 0 until doc.pageCount) {
                val original=render(lib.sourceFile(doc.id),i,maxWidth=1400)
                try {
                    val pageInfo=PdfDocument.PageInfo.Builder(original.width,original.height,i+1).create()
                    val p=pdf.startPage(pageInfo)
                    p.canvas.drawColor(Color.WHITE)
                    p.canvas.drawBitmap(original,0f,0f,null)
                    lib.readInk(doc.id,i).forEach { stroke ->
                        if (stroke.points.isNotEmpty()) {
                            val path=Path()
                            stroke.points.forEachIndexed { index,pt ->
                                val x=pt.x*original.width; val y=pt.y*original.height
                                if(index==0) path.moveTo(x,y) else path.lineTo(x,y)
                            }
                            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color=stroke.color; alpha=if(stroke.highlighter) 95 else 255
                                strokeWidth=stroke.width*original.width
                                style=Paint.Style.STROKE
                                strokeCap=Paint.Cap.ROUND
                                strokeJoin=Paint.Join.ROUND
                            }
                            p.canvas.drawPath(path,paint)
                        }
                    }
                    pdf.finishPage(p)
                } finally { original.recycle() }
            }
            context.contentResolver.openOutputStream(dest,"w")?.use { pdf.writeTo(it) }
                ?: error("Cannot open export destination")
        } finally { pdf.close() }
    }
}

object OfflineModelImporter {
    /** Accepts ZIP from official Vosk model download; no network permission is needed. */
    fun import(context:Context, library:LocalLibrary, uri:Uri, language:String):String {
        require(language in setOf("en","de"))
        val finalDir=library.modelDirectory(language)
        val parent=finalDir.parentFile!!
        val temp=File(parent,".${language}-import-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                unzipSafely(input,temp)
            } ?: error("Could not open ZIP")
            val candidate=temp.walkTopDown().filter { it.isDirectory && File(it,"am").isDirectory && File(it,"conf").isDirectory }
                .firstOrNull() ?: error("ZIP does not contain a Vosk model (am/conf folders missing)")
            // Validate the entire model before modifying the installed model.
            val staging=File(parent,".${language}-staged")
            staging.deleteRecursively()
            require(candidate.copyRecursively(staging,overwrite=false)) { "Could not stage model" }
            val backup=File(parent,".${language}-previous")
            backup.deleteRecursively()
            if (finalDir.exists()) require(finalDir.renameTo(backup)) { "Could not back up old model" }
            if (!staging.renameTo(finalDir)) {
                if (backup.exists()) backup.renameTo(finalDir)
                error("Could not activate new model")
            }
            backup.deleteRecursively()
            return "${language.uppercase()} model imported: ${finalDir.name}"
        } finally { temp.deleteRecursively() }
    }
    private fun unzipSafely(input:InputStream, dest:File) {
        var expanded=0L; var files=0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry=zip.nextEntry ?: break
                files++
                require(files<=20000) { "Too many ZIP entries" }
                val out=File(dest,entry.name)
                val root=dest.canonicalFile
                require(out.canonicalPath.startsWith(root.path+File.separator)) { "Unsafe ZIP path" }
                if(entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { output ->
                        val buf=ByteArray(32768)
                        while (true) {
                            val count=zip.read(buf)
                            if(count<0) break
                            expanded+=count
                            require(expanded<=750L*1024*1024) { "ZIP expands beyond 750 MB" }
                            output.write(buf,0,count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}

object AudioTranscriber {
    /** Post-process stored 16k mono WAV, replacing segments with fresh offline decoding. */
    fun transcribe(lib:LocalLibrary, docId:String, session:LectureSession):LectureSession {
        val modelDir=lib.modelDirectory(session.language)
        require(File(modelDir,"am").isDirectory) { "Import the matching Vosk language model first" }
        val wav=lib.recordingFile(docId,session.id)
        require(wav.isFile && wav.length()>44) { "Recording is missing or empty" }
        val results=mutableListOf<TranscriptSegment>()
        Model(modelDir.absolutePath).use { model ->
            Recognizer(model,16000.0f).use { rec ->
                rec.setWords(true)
                FileInputStream(wav).use { stream ->
                    require(stream.skip(44L)==44L) { "Invalid WAV header" }
                    val buffer=ByteArray(4096)
                    var bytes=0L; var last=0L
                    while(true) {
                        val n=stream.read(buffer)
                        if(n<0) break
                        bytes+=n
                        if(rec.acceptWaveForm(buffer,n)) {
                            SpeechSegments.fromJson(rec.result,bytes/32L,last,session.segments.lastOrNull()?.page ?: 0)?.let {
                                results.add(it); last=it.endMs
                            }
                        }
                    }
                    SpeechSegments.fromJson(rec.finalResult,bytes/32L,last,session.segments.lastOrNull()?.page ?: 0)?.let(results::add)
                }
            }
        }
        // Page association is recalculated from the recorded page-change/ink event timeline.
        val cues=lib.cues(docId,session.id).sortedBy { it.timeMs }
        val adjusted=results.map { seg ->
            seg.copy(page=cues.lastOrNull { it.timeMs<=seg.startMs }?.page ?: 0)
        }
        val newSession=session.copy(segments=adjusted)
        lib.putSession(docId,newSession)
        return newSession
    }
}
