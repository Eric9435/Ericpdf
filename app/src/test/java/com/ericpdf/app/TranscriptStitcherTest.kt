package com.ericpdf.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptStitcherTest {
    @Test fun keepsFirstWindowUntouched() {
        assertEquals("The synchronous generator has inertia.",
            TranscriptStitcher.removeOverlap("", "The synchronous generator has inertia."))
    }
    @Test fun removesRepeatedBoundaryWordsWithPunctuation() {
        assertEquals("reactive power flow.",
            TranscriptStitcher.removeOverlap(
                "The synchronous machine controls the reactive",
                "the reactive power flow."))
    }
    @Test fun handlesAccentedGermanTerms() {
        assertEquals("mit großer Spannung",
            TranscriptStitcher.removeOverlap(
                "Die Übertragung erfolgt mit",
                "erfolgt mit großer Spannung"))
    }
    @Test fun toleratesSingleTranscriptionMistakeInLongOverlap() {
        assertEquals("for stability analysis.",
            TranscriptStitcher.removeOverlap(
                "we use the state space model",
                "we use a state space model for stability analysis."))
    }
    @Test fun preservesGenuineNewShortPhrase() {
        assertEquals("the motor starts",TranscriptStitcher.removeOverlap(
            "we define the parameter", "the motor starts"))
    }
    @Test fun ignoresBlankResult() {
        assertEquals("",TranscriptStitcher.removeOverlap("already said", "   "))
    }
    @Test fun silenceDetectionDoesNotEraseSpokenVolume() {
        val quiet=ByteArray(OnlineLectureTranscriber.MIN_BYTES)
        assertTrue(OnlineLectureTranscriber.isNearSilence(quiet))
        val speech=ByteArray(OnlineLectureTranscriber.MIN_BYTES)
        for (i in speech.indices step 2) {
            speech[i] = 0
            speech[i + 1] = 8 // PCM sample 2048
        }
        assertFalse(OnlineLectureTranscriber.isNearSilence(speech))
    }
}
