package com.example.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "AudioFileUtils"
const val WHISPER_SAMPLE_RATE = 16000

object AudioFileUtils {

    /**
     * Decodes standard 16-bit PCM WAV file into FloatArray normalized between -1.0f and 1.0f.
     */
    fun decodeWaveFile(file: File): FloatArray {
        return file.inputStream().use { decodeWaveStream(it) }
    }

    fun decodeWaveStream(inputStream: InputStream): FloatArray {
        val baos = ByteArrayOutputStream()
        inputStream.copyTo(baos)
        val bytes = baos.toByteArray()
        if (bytes.size < 44) return FloatArray(0)

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Validate "RIFF" and "WAVE"
        val riff = String(bytes, 0, 4)
        val wave = String(bytes, 8, 4)
        if (riff != "RIFF" || wave != "WAVE") {
            Log.w(TAG, "Not a standard RIFF WAVE file: riff=$riff, wave=$wave")
        }

        // Find fmt and data chunks
        var pos = 12
        var channels = 1
        var sampleRate = 16000
        var bitsPerSample = 16
        var dataPos = 44
        var dataSize = bytes.size - 44

        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4)
            val chunkSize = buffer.getInt(pos + 4)
            if (chunkId == "fmt ") {
                channels = buffer.getShort(pos + 8 + 2).toInt()
                sampleRate = buffer.getInt(pos + 8 + 4)
                bitsPerSample = buffer.getShort(pos + 8 + 14).toInt()
            } else if (chunkId == "data") {
                dataPos = pos + 8
                dataSize = chunkSize
                break
            }
            pos += 8 + chunkSize
        }

        buffer.position(dataPos)
        val shortBuffer = buffer.asShortBuffer()
        val totalShorts = minOf(shortBuffer.remaining(), dataSize / 2)
        val shortArray = ShortArray(totalShorts)
        shortBuffer.get(shortArray)

        val numFrames = shortArray.size / maxOf(1, channels)
        val pcm = FloatArray(numFrames) { i ->
            if (channels == 1) {
                (shortArray[i] / 32767.0f).coerceIn(-1f, 1f)
            } else {
                val sum = shortArray[2 * i] + shortArray[2 * i + 1]
                (sum / (2.0f * 32767.0f)).coerceIn(-1f, 1f)
            }
        }

        // Resample to 16kHz if needed
        return if (sampleRate != WHISPER_SAMPLE_RATE && sampleRate > 0) {
            resampleLinear(pcm, sampleRate, WHISPER_SAMPLE_RATE)
        } else {
            pcm
        }
    }

    /**
     * Decodes any supported audio format (MP3, M4A, AAC, WAV, OGG) using MediaExtractor and MediaCodec
     * to a 16kHz mono FloatArray.
     */
    fun decodeAudioUri(context: Context, uri: Uri): FloatArray {
        // First try fast WAV decoding if mime/extension is wav
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = ByteArray(12)
                val read = stream.read(bytes)
                if (read == 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
                    context.contentResolver.openInputStream(uri)?.use { fullStream ->
                        return decodeWaveStream(fullStream)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Fast WAV decode skipped, proceeding with MediaCodec", e)
        }

        // Fallback to Android MediaExtractor & MediaCodec for MP3, M4A, AAC, etc.
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) {
                throw IllegalArgumentException("No audio track found in file")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/raw"
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                16000
            }
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else {
                1
            }

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmShortList = mutableListOf<Short>()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inputBufIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufIndex >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIndex) ?: continue
                        val sampleSize = extractor.readSampleData(inputBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inputBufIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val res = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (res >= 0) {
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                    val outputBuf = codec.getOutputBuffer(res)
                    if (outputBuf != null && bufferInfo.size > 0) {
                        outputBuf.position(bufferInfo.offset)
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val shortBuf = outputBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (shortBuf.hasRemaining()) {
                            pcmShortList.add(shortBuf.get())
                        }
                    }
                    codec.releaseOutputBuffer(res, false)
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val totalShorts = pcmShortList.size
            val numFrames = totalShorts / maxOf(1, channelCount)
            val pcmFloats = FloatArray(numFrames) { i ->
                if (channelCount == 1) {
                    (pcmShortList[i] / 32767.0f).coerceIn(-1f, 1f)
                } else {
                    val sum = pcmShortList[2 * i] + pcmShortList[2 * i + 1]
                    (sum / (2.0f * 32767.0f)).coerceIn(-1f, 1f)
                }
            }

            return if (sampleRate != WHISPER_SAMPLE_RATE && sampleRate > 0) {
                resampleLinear(pcmFloats, sampleRate, WHISPER_SAMPLE_RATE)
            } else {
                pcmFloats
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio with MediaCodec", e)
            try { extractor.release() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Resamples audio PCM data from inRate to outRate using linear interpolation.
     */
    fun resampleLinear(input: FloatArray, inRate: Int, outRate: Int): FloatArray {
        if (inRate == outRate || input.isEmpty()) return input
        val ratio = inRate.toDouble() / outRate.toDouble()
        val outLength = (input.size / ratio).toInt()
        val output = FloatArray(outLength)

        for (i in 0 until outLength) {
            val srcIndex = i * ratio
            val index0 = srcIndex.toInt()
            val index1 = minOf(index0 + 1, input.size - 1)
            val frac = (srcIndex - index0).toFloat()
            output[i] = input[index0] * (1.0f - frac) + input[index1] * frac
        }
        return output
    }

    /**
     * Encodes raw 16-bit PCM ShortArray to a standard RIFF/WAV 16kHz mono audio file.
     */
    fun encodeWaveFile(file: File, data: ShortArray, sampleRate: Int = WHISPER_SAMPLE_RATE) {
        val byteLength = data.size * 2
        file.outputStream().use { fos ->
            fos.write(createWavHeader(byteLength, sampleRate))
            val buffer = ByteBuffer.allocate(byteLength).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asShortBuffer().put(data)
            fos.write(buffer.array())
        }
    }

    private fun createWavHeader(dataLength: Int, sampleRate: Int): ByteArray {
        val totalLength = dataLength + 44
        return ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            put('R'.code.toByte())
            put('I'.code.toByte())
            put('F'.code.toByte())
            put('F'.code.toByte())
            putInt(totalLength - 8)
            put('W'.code.toByte())
            put('A'.code.toByte())
            put('V'.code.toByte())
            put('E'.code.toByte())
            put('f'.code.toByte())
            put('m'.code.toByte())
            put('t'.code.toByte())
            put(' '.code.toByte())
            putInt(16) // Subchunk1Size
            putShort(1.toShort()) // AudioFormat (1 = PCM)
            putShort(1.toShort()) // NumChannels (1 = Mono)
            putInt(sampleRate) // SampleRate
            putInt(sampleRate * 2) // ByteRate (SampleRate * NumChannels * BitsPerSample/8)
            putShort(2.toShort()) // BlockAlign (NumChannels * BitsPerSample/8)
            putShort(16.toShort()) // BitsPerSample
            put('d'.code.toByte())
            put('a'.code.toByte())
            put('t'.code.toByte())
            put('a'.code.toByte())
            putInt(dataLength)
            position(0)
        }.array()
    }
}
