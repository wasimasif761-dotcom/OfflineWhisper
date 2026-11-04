package com.example.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

private const val TAG = "AudioRecordEngine"

class AudioRecordEngine {

    private var recorderThread: RecordingThread? = null
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    @Synchronized
    fun startRecording(outputFile: File, onError: (Exception) -> Unit) {
        if (_isRecording.value) return

        val thread = RecordingThread(
            outputFile = outputFile,
            onAmplitudeChanged = { amp -> _amplitude.value = amp },
            onStatusChanged = { recording -> _isRecording.value = recording },
            onError = onError
        )
        recorderThread = thread
        thread.start()
    }

    @Synchronized
    fun stopRecording(): File? {
        val thread = recorderThread ?: return null
        thread.stopRecording()
        try {
            thread.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted while waiting for recorder thread", e)
        }
        val file = thread.outputFile
        recorderThread = null
        _isRecording.value = false
        _amplitude.value = 0f
        return file
    }

    fun getAudioSnapshot(): FloatArray {
        return recorderThread?.getSnapshot() ?: FloatArray(0)
    }

    private class RecordingThread(
        val outputFile: File,
        private val onAmplitudeChanged: (Float) -> Unit,
        private val onStatusChanged: (Boolean) -> Unit,
        private val onError: (Exception) -> Unit
    ) : Thread("WhisperAudioRecorder") {

        private val quit = AtomicBoolean(false)
        private val samplesLock = Any()
        private val recordedSamples = ArrayList<Short>(16000 * 10) // preallocate 10s

        @SuppressLint("MissingPermission")
        override fun run() {
            onStatusChanged(true)
            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(
                    WHISPER_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ) * 2,
                16000 / 5 // ~200ms chunk
            )

            val buffer = ShortArray(bufferSize)
            var audioRecord: AudioRecord? = null

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    WHISPER_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize * 2
                )

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    // Fallback to default MIC
                    audioRecord.release()
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        WHISPER_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize * 2
                    )
                }

                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("AudioRecord initialization failed. State: ${audioRecord.state}")
                }

                audioRecord.startRecording()

                while (!quit.get()) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        var sumSquare = 0.0
                        synchronized(samplesLock) {
                            for (i in 0 until read) {
                                val s = buffer[i]
                                recordedSamples.add(s)
                                sumSquare += (s * s).toDouble()
                            }
                        }
                        val rms = sqrt(sumSquare / read) / 32767.0
                        val normAmp = (rms * 4.0).coerceIn(0.0, 1.0).toFloat()
                        onAmplitudeChanged(normAmp)
                    } else if (read < 0) {
                        Log.e(TAG, "AudioRecord read returned error code: $read")
                        break
                    }
                }

                try {
                    audioRecord.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping AudioRecord", e)
                }

                // Save WAV file
                val fullShorts: ShortArray
                synchronized(samplesLock) {
                    fullShorts = ShortArray(recordedSamples.size) { i -> recordedSamples[i] }
                }
                AudioFileUtils.encodeWaveFile(outputFile, fullShorts, WHISPER_SAMPLE_RATE)
                Log.d(TAG, "Saved recording with ${fullShorts.size} samples to ${outputFile.name}")

            } catch (e: Exception) {
                Log.e(TAG, "Recording exception", e)
                onError(e)
            } finally {
                try {
                    audioRecord?.release()
                } catch (_: Exception) {}
                onStatusChanged(false)
            }
        }

        fun stopRecording() {
            quit.set(true)
        }

        fun getSnapshot(): FloatArray {
            synchronized(samplesLock) {
                val size = recordedSamples.size
                if (size == 0) return FloatArray(0)
                return FloatArray(size) { i ->
                    (recordedSamples[i] / 32767.0f).coerceIn(-1f, 1f)
                }
            }
        }
    }
}
