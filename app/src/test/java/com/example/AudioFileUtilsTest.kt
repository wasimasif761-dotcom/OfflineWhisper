package com.example

import com.example.audio.AudioFileUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioFileUtilsTest {

    @Test
    fun testWavEncodeAndDecode() {
        val tempFile = File.createTempFile("test_audio", ".wav")
        tempFile.deleteOnExit()

        val sampleRate = 16000
        val numSamples = 16000 // 1 second
        val inputShorts = ShortArray(numSamples) { i ->
            (kotlin.math.sin(2.0 * Math.PI * 440.0 * i / sampleRate) * 16000).toInt().toShort()
        }

        AudioFileUtils.encodeWaveFile(tempFile, inputShorts, sampleRate)
        assertTrue(tempFile.exists())
        assertTrue(tempFile.length() > 44)

        val decodedFloats = AudioFileUtils.decodeWaveFile(tempFile)
        assertEquals(numSamples, decodedFloats.size)
        // Check value range
        for (sample in decodedFloats) {
            assertTrue(sample in -1.01f..1.01f)
        }
    }

    @Test
    fun testResampleLinear() {
        val input = floatArrayOf(0.0f, 1.0f)
        val resampled = AudioFileUtils.resampleLinear(input, 8000, 16000)
        assertEquals(4, resampled.size)
    }
}
