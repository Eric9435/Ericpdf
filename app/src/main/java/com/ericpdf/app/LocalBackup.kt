package com.ericpdf.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** User-initiated local backup: PDFs, ink, page notes, bookmarks, WAV and transcripts.
 * Vosk model ZIPs are excluded because they can be downloaded again.
 * The archive is not encrypted; users should protect sensitive recordings.
 */
object LocalBackup {
    private const val FORMAT = "ericpdf-library"
    private const val VERSION = 1
    private const val MAX_FILES = 20000
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024

    data class RestoreResult(val imported:Int,val skipped:Int)

    fun export(context:Context,uri:Uri) {
        val root=File(context.filesDir,"ericpdf/library")
        val docs=LocalLibrary(context).list()
        val output=context.contentResolver.openOutputStream(uri) ?: error("Cannot open backup destination")
        ZipOutputStream(output.buffered()).use { zip ->
            val manifest=JSONObject().put("format",FORMAT).put("version",VERSION)
                .put("createdAt",System.currentTimeMillis()).toString().toByteArray(Charsets.UTF_8)
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(manifest)
            zip.closeEntry()
            for(doc in docs) {
                val dir=File(root,doc.id)
                if(!dir.isDirectory) continue
                dir.walkTopDown().filter { it.isFile }.forEach { file ->
                    require(file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "Unexpected backup path" }
                    val name="documents/" + doc.id + "/" + file.relativeTo(dir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun restore(context:Context,uri:Uri):RestoreResult {
        val staging=File(context.cacheDir,"ericpdf-restore-" + UUID.randomUUID()).apply { mkdirs() }
        val root=File(context.filesDir,"ericpdf/library").apply { mkdirs() }
        val validId=Regex("[a-f0-9-]{36}")
        try {
            val source=context.contentResolver.openInputStream(uri) ?: error("Cannot read backup ZIP")
            var bytes=0L
            var entries=0
            val encountered=HashSet<String>()
            ZipInputStream(source.buffered()).use { zip ->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    val name=entry.name
                    require(name=="backup.json" || name.startsWith("documents/")) { "Invalid EricPDF backup entry" }
                    require(encountered.add(name)) { "Duplicate backup entry" }
                    entries++
                    require(entries<=MAX_FILES) { "Backup has too many files" }
                    val file=File(staging,name)
                    require(file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Unsafe backup path" }
                    if(entry.isDirectory) file.mkdirs() else {
                        file.parentFile?.mkdirs()
                        file.outputStream().use { out ->
                            val buffer=ByteArray(32768)
                            while(true) {
                                val n=zip.read(buffer)
                                if(n<0) break
                                bytes+=n
                                require(bytes<=MAX_TOTAL_BYTES) { "Backup is too large" }
                                out.write(buffer,0,n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val marker=File(staging,"backup.json")
            require(marker.isFile) { "EricPDF backup manifest is missing" }
            val format=JSONObject(marker.readText())
            require(format.optString("format")==FORMAT && format.optInt("version")==VERSION) { "Unsupported EricPDF backup" }
            val documents=File(staging,"documents").listFiles().orEmpty().filter { it.isDirectory }
            // Validate every document before installing any of them.
            for(dir in documents) {
                require(validId.matches(dir.name)) { "Invalid document identifier" }
                val meta=File(dir,"document.json")
                val pdf=File(dir,"document.pdf")
                require(meta.isFile && pdf.isFile) { "Backup document is incomplete" }
                val info=JSONObject(meta.readText())
                require(info.optString("id")==dir.name && info.optInt("pageCount")>0) { "Invalid document metadata" }
                require(PdfTools.pageCount(pdf)==info.optInt("pageCount")) { "Document PDF is damaged" }
            }
            var imported=0
            var skipped=0
            for(dir in documents) {
                val target=File(root,dir.name)
                if(target.exists()) { skipped++;continue }
                require(dir.renameTo(target)) { "Cannot restore document ${dir.name}" }
                imported++
            }
            return RestoreResult(imported,skipped)
        } finally {
            staging.deleteRecursively()
        }
    }
}
