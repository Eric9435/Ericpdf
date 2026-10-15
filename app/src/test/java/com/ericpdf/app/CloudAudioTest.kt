package com.ericpdf.app
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudAudioTest {
    @Test fun upsamplingScalesByteLength() {
        assertEquals(4800,OnlineLectureTranscriber.upsample16To24(ByteArray(3200),3200).size)
    }
    @Test fun silenceStaysSilent() {
        val buffer=OnlineLectureTranscriber.upsample16To24(ByteArray(3200),3200)
        assertEquals(0,buffer.count { it!=0.toByte() })
    }
    @Test fun zeroInputIsEmpty() {
        assertEquals(0,OnlineLectureTranscriber.upsample16To24(ByteArray(0),0).size)
    }
}
