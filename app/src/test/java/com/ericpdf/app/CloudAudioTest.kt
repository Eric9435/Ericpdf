package com.ericpdf.app
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CloudAudioTest {
    @Test fun wrapsPcmAsSixteenKilohertzMonoWav() {
        val input=ByteArray(OnlineLectureTranscriber.CHUNK_BYTES)
        val wav=OnlineLectureTranscriber.wavBytes(input)
        assertEquals(44+input.size,wav.size)
        assertEquals("RIFF",String(wav,0,4,Charsets.US_ASCII))
        assertEquals("WAVE",String(wav,8,4,Charsets.US_ASCII))
        val header=ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16000,header.getInt(24))
        assertEquals(1,header.getShort(22).toInt())
        assertEquals(16,header.getShort(34).toInt())
        assertEquals(input.size,header.getInt(40))
    }
    @Test(expected=IllegalArgumentException::class)
    fun rejectsMisalignedSamples() {
        OnlineLectureTranscriber.wavBytes(ByteArray(3))
    }
}
