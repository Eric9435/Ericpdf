package com.ericpdf.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class WavFileTest {
    @Test fun headerIsValidMono16kPcm() {
        val file=File.createTempFile("ericpdf", ".wav")
        try {
            RandomAccessFile(file,"rw").use { raf ->
                WavFile.writeHeader(raf,32000L)
                assertEquals(44L,raf.filePointer)
                assertEquals(44L,raf.length())
            }
            val bytes=file.readBytes()
            assertEquals("RIFF",String(bytes.sliceArray(0..3),Charsets.US_ASCII))
            assertEquals("WAVE",String(bytes.sliceArray(8..11),Charsets.US_ASCII))
            assertEquals("fmt ",String(bytes.sliceArray(12..15),Charsets.US_ASCII))
            assertEquals("data",String(bytes.sliceArray(36..39),Charsets.US_ASCII))
            assertEquals(1, bytes[22].toInt())    // 1 channel
            assertEquals(0x80, bytes[24].toInt() and 0xff) // 16000 Hz, LSB
            assertEquals(16, bytes[34].toInt())   // 16 bits per sample
        } finally {file.delete()}
    }
}
