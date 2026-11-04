package com.example.model

import android.content.Context
import android.util.Log
import com.whispercpp.whisper.WhisperContext
import com.whispercpp.whisper.WhisperCpuConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "WhisperEngine"

data class TranscriptionResult(
    val text: String,
    val durationSeconds: Float,
    val latencyMs: Long,
    val modelId: String,
    val language: String
)

class WhisperEngine(private val context: Context) {

    private val mutex = Mutex()
    private var whisperContext: WhisperContext? = null
    private var currentModelPath: String? = null

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    private val _loadedModelName = MutableStateFlow<String?>(null)
    val loadedModelName: StateFlow<String?> = _loadedModelName.asStateFlow()

    private val _isTranscribing = MutableStateFlow(false)
    val isTranscribing: StateFlow<Boolean> = _isTranscribing.asStateFlow()

    suspend fun loadModel(modelFile: File): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!modelFile.exists() || modelFile.length() < 1024 * 1024) {
                Log.e(TAG, "Cannot load model: file not found or too small: ${modelFile.absolutePath}")
                _isModelLoaded.value = false
                _loadedModelName.value = null
                return@withContext false
            }

            if (currentModelPath == modelFile.absolutePath && whisperContext != null && !whisperContext!!.isReleased()) {
                Log.d(TAG, "Model already loaded: ${modelFile.name}")
                return@withContext true
            }

            try {
                whisperContext?.release()
                whisperContext = null
                _isModelLoaded.value = false
                _loadedModelName.value = null

                Log.d(TAG, "Loading whisper model from ${modelFile.absolutePath}...")
                val ctx = WhisperContext.createContextFromFile(modelFile.absolutePath)
                whisperContext = ctx
                currentModelPath = modelFile.absolutePath
                _isModelLoaded.value = true
                _loadedModelName.value = modelFile.name
                Log.i(TAG, "Successfully loaded whisper model: ${modelFile.name}")
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize whisper context", e)
                whisperContext = null
                currentModelPath = null
                _isModelLoaded.value = false
                _loadedModelName.value = null
                return@withContext false
            }
        }
    }

    suspend fun transcribeAudio(
        audioSamples: FloatArray,
        language: String = "auto",
        includeTimestamps: Boolean = true,
        threads: Int = WhisperCpuConfig.preferredThreadCount
    ): TranscriptionResult? = withContext(Dispatchers.Default) {
        if (audioSamples.isEmpty()) return@withContext null

        mutex.withLock {
            val ctx = whisperContext
            if (ctx == null || ctx.isReleased()) {
                Log.w(TAG, "Transcription called but model is not loaded")
                return@withContext null
            }

            _isTranscribing.value = true
            val startTime = System.currentTimeMillis()
            try {
                val transcript = ctx.transcribeData(
                    data = audioSamples,
                    printTimestamp = includeTimestamps,
                    language = language,
                    threadCount = threads
                )
                val latency = System.currentTimeMillis() - startTime
                val durationSec = audioSamples.size.toFloat() / 16000f

                return@withContext TranscriptionResult(
                    text = transcript,
                    durationSeconds = durationSec,
                    latencyMs = latency,
                    modelId = _loadedModelName.value ?: "unknown",
                    language = language
                )
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error", e)
                return@withContext null
            } finally {
                _isTranscribing.value = false
            }
        }
    }

    suspend fun unload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                whisperContext?.release()
            } catch (_: Exception) {}
            whisperContext = null
            currentModelPath = null
            _isModelLoaded.value = false
            _loadedModelName.value = null
        }
    }
}
