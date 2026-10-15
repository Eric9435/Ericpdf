package com.ericpdf.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLectureNotesTest {
    @Test fun emptyLecture() {
        assertEquals("",LiveLectureNotes.fromSegments(emptyList()))
    }
    @Test fun pageGroupedAndTimestamped() {
        val data=listOf(
            TranscriptSegment(1500,2300," a  first   formula ",0),
            TranscriptSegment(62000,63000,"this is the answer",0),
            TranscriptSegment(3763000,3764000,"important definition",2))
        val out=LiveLectureNotes.fromSegments(data)
        assertTrue(out.contains("PAGE 1"))
        assertTrue(out.contains("[00:00:01] a first formula"))
        assertTrue(out.contains("[00:01:02] this is the answer"))
        assertTrue(out.contains("PAGE 3"))
        assertTrue(out.contains("[01:02:43] important definition"))
    }
    @Test fun blankRecognitionDoesNotCreateNote() {
        assertEquals("",LiveLectureNotes.fromSegments(listOf(TranscriptSegment(0,1000,"  ",0))))
    }
}
