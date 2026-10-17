package com.ericpdf.app

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Final document uses confirmed transcript segments, never unconfirmed live captions. */
object FinalLectureDocument {
    private val spaces = Regex("\\s+")

    fun hasWords(session: LectureSession): Boolean =
        session.segments.any { it.text.isNotBlank() }

    fun plainText(doc: LibraryDoc, session: LectureSession): String {
        if (!hasWords(session)) return ""
        val created = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(Date(session.createdAt))
        return buildString {
            append("ERICPDF - FINAL LECTURE TRANSCRIPT\n")
            append("Lecture: ").append(doc.title).append("\n")
            append("Course: ").append(doc.course).append("\n")
            append("Recorded: ").append(created).append("\n")
            append("Language: ").append(if (session.language == "de") "German" else "English").append("\n")
            append("Duration: ").append(clock(session.durationMs)).append("\n")
            append("Source: speech recognition; review technical terms and formulas.\n")
            append("Original WAV recording is retained on this device.\n\n")
            var previousPage = -1
            for (segment in session.segments) {
                val clean = segment.text.trim().replace(spaces, " ")
                if (clean.isBlank()) continue
                if (segment.page != previousPage) {
                    if (previousPage >= 0) append("\n")
                    append("PDF SLIDE ").append(segment.page + 1).append("\n")
                    previousPage = segment.page
                }
                append("[").append(clock(segment.startMs)).append("] ")
                append(clean).append("\n")
            }
        }
    }

    fun clock(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return "%02d:%02d:%02d".format(Locale.US,
            seconds / 3600, (seconds / 60) % 60, seconds % 60)
    }

    /**
     * Saves finalized TXT and PDF to EricPDF's app-private storage.
     * These files are distinct from the original WAV and lecture slides.
     * Called on a background worker after recording stops or transcription finishes.
     */
    fun save(library: LocalLibrary, docId: String, session: LectureSession): Boolean {
        if (!hasWords(session)) return false
        val text = plainText(library.get(docId), session)
        library.saveFinalTranscript(docId, session.id, text)
        renderPdf(library, docId, session)
        return true
    }

    fun renderPdf(library: LocalLibrary, docId: String, session: LectureSession): File {
        require(hasWords(session)) { "No recognized words available to export" }
        val target = library.finalTranscriptPdfFile(docId, session.id)
        val tmp = File(target.parentFile, target.name + ".tmp")
        target.parentFile?.mkdirs()
        val doc = library.get(docId)
        val pdf = PdfDocument()
        val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(35, 48, 68)
            textSize = 10.5f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val heading = Paint(normal).apply {
            textSize = 20f
            color = android.graphics.Color.rgb(27, 67, 130)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val section = Paint(normal).apply {
            textSize = 11.5f
            color = android.graphics.Color.rgb(28, 70, 132)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }
        val muted = Paint(normal).apply {
            textSize = 9f
            color = android.graphics.Color.rgb(93, 107, 126)
        }
        var number = 0
        var page: PdfDocument.Page? = null
        var y = 0f
        val left = 48f
        val right = 545f
        val usable = right - left

        fun endPage() {
            val active = page ?: return
            active.canvas.drawLine(left, 798f, right, 798f, muted)
            active.canvas.drawText("EricPDF | Private lecture transcript", left, 813f, muted)
            val countText = "Page $number"
            active.canvas.drawText(countText, right - muted.measureText(countText), 813f, muted)
            pdf.finishPage(active)
            page = null
        }

        fun newPage() {
            endPage()
            number++
            val info = PdfDocument.PageInfo.Builder(595, 842, number).create()
            page = pdf.startPage(info)
            y = 56f
        }

        fun ensure(height: Float) {
            if (page == null || y + height > 778f) newPage()
        }

        fun drawLine(content: String, paint: Paint, step: Float) {
            if (content.isBlank()) {
                ensure(step)
                y += step
                return
            }
            var leftText = content
            while (leftText.isNotEmpty()) {
                val maxCount = paint.breakText(leftText, true, usable, null)
                    .coerceAtLeast(1).coerceAtMost(leftText.length)
                var cut = maxCount
                if (maxCount < leftText.length) {
                    val space = leftText.lastIndexOf(' ', maxCount - 1)
                    if (space > maxCount / 3) cut = space
                }
                val fragment = leftText.substring(0, cut).trimEnd()
                if (fragment.isNotEmpty()) {
                    ensure(step)
                    page!!.canvas.drawText(fragment, left, y, paint)
                    y += step
                }
                leftText = leftText.substring(cut).trimStart()
            }
        }

        try {
            newPage()
            drawLine("FINAL LECTURE TRANSCRIPT", heading, 28f)
            y += 10f
            drawLine("Document: ${doc.title}", normal, 16f)
            drawLine("Course: ${doc.course}", normal, 16f)
            val created = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(session.createdAt))
            drawLine("Recorded: $created | Duration: ${clock(session.durationMs)}", normal, 16f)
            drawLine("Language: ${if (session.language == "de") "German" else "English"}", normal, 16f)
            y += 7f
            drawLine("Speech recognition can mishear technical words and equations.", muted, 14f)
            drawLine("Verify important details against the audio and original lecture slides.", muted, 14f)
            y += 22f

            var lastSlide = -1
            for (segment in session.segments) {
                val words = segment.text.trim().replace(spaces, " ")
                if (words.isEmpty()) continue
                if (segment.page != lastSlide) {
                    ensure(38f)
                    y += 10f
                    drawLine("PDF SLIDE ${segment.page + 1}", section, 20f)
                    lastSlide = segment.page
                }
                ensure(32f)
                drawLine("[${clock(segment.startMs)}] $words", normal, 15f)
                y += 5f
            }
            endPage()
            FileOutputStream(tmp).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        try {
            if (!tmp.renameTo(target)) {
                // Android/Linux normally replaces the old destination atomically.
                throw IllegalStateException("Unable to save finished PDF to app storage")
            }
            return target
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }
}
