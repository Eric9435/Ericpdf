package com.ericpdf.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinalLectureDocumentTest {
    private val doc=LibraryDoc(
        id="00000000-0000-0000-0000-000000000001",
        title="Electrical Power Systems II",course="TUHH - EEIT",
        createdAt=0,pageCount=24
    )
    @Test fun onlyConfirmedWordsCount() {
        val empty=LectureSession("a",0L,0L,"en",
            listOf(TranscriptSegment(0,1000,"   ",0)))
        assertFalse(FinalLectureDocument.hasWords(empty))
        assertEquals("",FinalLectureDocument.plainText(doc,empty))
    }
    @Test fun exportsAllWordsSlidesAndTimes() {
        val session=LectureSession("b",0L,3765000L,"en",
            listOf(
                TranscriptSegment(1000,3000,"  active  and   reactive power ",0),
                TranscriptSegment(61000,64500,"power factor correction",1),
                TranscriptSegment(3764000,3765000,"the final equation",1)
            ))
        val text=FinalLectureDocument.plainText(doc,session)
        assertTrue(text.contains("FINAL LECTURE TRANSCRIPT"))
        assertTrue(text.contains("Electrical Power Systems II"))
        assertTrue(text.contains("PDF SLIDE 1"))
        assertTrue(text.contains("PDF SLIDE 2"))
        assertTrue(text.contains("[00:00:01] active and reactive power"))
        assertTrue(text.contains("[00:01:01] power factor correction"))
        assertTrue(text.contains("[01:02:44] the final equation"))
        assertTrue(text.contains("Original WAV recording"))
        assertEquals(1,Regex("PDF SLIDE 2").findAll(text).count())
    }
    @Test fun supportsGermanCharacters() {
        val session=LectureSession("c",0L,5000L,"de",
            listOf(TranscriptSegment(0,5000,"Die Übertragungsspannung beträgt 110 Kilovolt.",3)))
        val text=FinalLectureDocument.plainText(doc,session)
        assertTrue(text.contains("Übertragungsspannung beträgt"))
        assertTrue(text.contains("Language: German"))
        assertTrue(text.contains("PDF SLIDE 4"))
    }
}
