package com.example.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioFileUtils
import com.example.audio.AudioRecordEngine
import com.example.model.DownloadState
import com.example.model.ModelInfo
import com.example.model.ModelManager
import com.example.model.WhisperEngine
import com.whispercpp.whisper.WhisperCpuConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "MainViewModel"

data class SupportedLanguage(
    val code: String,
    val name: String,
    val nativeName: String
)

data class MainUiState(
    val isRecording: Boolean = false,
    val recordingDurationSec: Int = 0,
    val liveAmplitude: Float = 0f,
    val livePartialTranscript: String = "",
    val fullTranscript: String = "",
    val isTranscribing: Boolean = false,
    val isFileTranscribing: Boolean = false,
    val statusMessage: String = "",
    val statsInfo: String? = null,
    val isModelLoaded: Boolean = false,
    val activeModelName: String = "ggml-base.bin",
    val installedModels: List<String> = emptyList(),
    val downloadState: DownloadState = DownloadState.Idle,
    val selectedLanguage: String = "auto",
    val showTimestamps: Boolean = true,
    val threadCount: Int = WhisperCpuConfig.preferredThreadCount,
    val showModelSheet: Boolean = false,
    val snackbarMessage: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val audioRecorder = AudioRecordEngine()
    val modelManager = ModelManager(application)
    val whisperEngine = WhisperEngine(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var recordingTimerJob: Job? = null
    private var partialTranscriptionJob: Job? = null
    private var recordingFile: File? = null

    val supportedLanguages = listOf(
        SupportedLanguage("auto", "Auto Detect", "خودکار پہچان"),
        SupportedLanguage("en", "English", "English"),
        SupportedLanguage("ur", "Urdu", "اردو"),
        SupportedLanguage("hi", "Hindi", "हिन्दी"),
        SupportedLanguage("ar", "Arabic", "العربية"),
        SupportedLanguage("es", "Spanish", "Español"),
        SupportedLanguage("fr", "French", "Français"),
        SupportedLanguage("de", "German", "Deutsch"),
        SupportedLanguage("zh", "Chinese", "中文"),
        SupportedLanguage("ja", "Japanese", "日本語"),
        SupportedLanguage("tr", "Turkish", "Türkçe"),
        SupportedLanguage("fa", "Persian", "فارسی")
    )

    init {
        // Collect model manager states
        viewModelScope.launch {
            modelManager.installedModels.collect { models ->
                _uiState.update { it.copy(installedModels = models) }
                checkAndAutoLoadModel()
            }
        }

        viewModelScope.launch {
            modelManager.downloadState.collect { state ->
                _uiState.update { it.copy(downloadState = state) }
                if (state is DownloadState.Success) {
                    checkAndAutoLoadModel()
                    showSnackbar("Model downloaded and ready for 100% offline use!")
                } else if (state is DownloadState.Error) {
                    showSnackbar("Download error: ${state.message}")
                }
            }
        }

        viewModelScope.launch {
            modelManager.activeModelId.collect { activeId ->
                if (activeId != null) {
                    _uiState.update { it.copy(activeModelName = activeId) }
                    checkAndAutoLoadModel()
                }
            }
        }

        viewModelScope.launch {
            whisperEngine.isModelLoaded.collect { loaded ->
                _uiState.update { it.copy(isModelLoaded = loaded) }
            }
        }

        viewModelScope.launch {
            audioRecorder.amplitude.collect { amp ->
                _uiState.update { it.copy(liveAmplitude = amp) }
            }
        }
    }

    private fun checkAndAutoLoadModel() {
        val activeId = modelManager.activeModelId.value ?: "ggml-base.bin"
        if (modelManager.isModelDownloaded(activeId)) {
            val file = modelManager.getModelFile(activeId)
            viewModelScope.launch {
                whisperEngine.loadModel(file)
            }
        }
    }

    fun toggleRecording() {
        if (_uiState.value.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    fun startRecording() {
        if (!_uiState.value.isModelLoaded) {
            showSnackbar("Please download or select a Whisper model first")
            _uiState.update { it.copy(showModelSheet = true) }
            return
        }

        val cacheDir = getApplication<Application>().cacheDir
        val file = File(cacheDir, "recorded_speech_${System.currentTimeMillis()}.wav")
        recordingFile = file

        _uiState.update {
            it.copy(
                isRecording = true,
                recordingDurationSec = 0,
                livePartialTranscript = "",
                statusMessage = "Listening... (16kHz PCM)"
            )
        }

        audioRecorder.startRecording(
            outputFile = file,
            onError = { ex ->
                Log.e(TAG, "Audio recording failed", ex)
                viewModelScope.launch {
                    stopRecording()
                    showSnackbar("Recording error: ${ex.localizedMessage}")
                }
            }
        )

        // Timer job
        recordingTimerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _uiState.update { it.copy(recordingDurationSec = it.recordingDurationSec + 1) }
            }
        }

        // Live partial transcription loop (every 3 seconds)
        partialTranscriptionJob = viewModelScope.launch(Dispatchers.Default) {
            delay(2500)
            while (_uiState.value.isRecording) {
                val snapshot = audioRecorder.getAudioSnapshot()
                if (snapshot.size >= 16000 * 2) { // At least 2 seconds of audio
                    try {
                        val result = whisperEngine.transcribeAudio(
                            audioSamples = snapshot,
                            language = _uiState.value.selectedLanguage,
                            includeTimestamps = false,
                            threads = _uiState.value.threadCount
                        )
                        if (result != null && result.text.isNotBlank()) {
                            _uiState.update { it.copy(livePartialTranscript = result.text) }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Live partial transcription tick", e)
                    }
                }
                delay(3000)
            }
        }
    }

    fun stopRecording() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        partialTranscriptionJob?.cancel()
        partialTranscriptionJob = null

        val file = audioRecorder.stopRecording() ?: recordingFile
        _uiState.update {
            it.copy(
                isRecording = false,
                isTranscribing = true,
                statusMessage = "Finalizing offline transcription..."
            )
        }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (file != null && file.exists()) {
                    val pcmFloats = AudioFileUtils.decodeWaveFile(file)
                    val result = whisperEngine.transcribeAudio(
                        audioSamples = pcmFloats,
                        language = _uiState.value.selectedLanguage,
                        includeTimestamps = _uiState.value.showTimestamps,
                        threads = _uiState.value.threadCount
                    )

                    withContext(Dispatchers.Main) {
                        if (result != null && result.text.isNotBlank()) {
                            val ratio = if (result.durationSeconds > 0) {
                                String.format("%.2fx", (result.latencyMs / 1000f) / result.durationSeconds)
                            } else "N/A"
                            val stats = "Audio: %.1fs | Processed in %.1fs (%s realtime) | Model: %s".format(
                                result.durationSeconds,
                                result.latencyMs / 1000f,
                                ratio,
                                result.modelId
                            )
                            _uiState.update {
                                it.copy(
                                    fullTranscript = result.text,
                                    livePartialTranscript = "",
                                    statsInfo = stats,
                                    isTranscribing = false,
                                    statusMessage = "Transcription complete (100% offline)"
                                )
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    fullTranscript = if (it.livePartialTranscript.isNotBlank()) it.livePartialTranscript else "[No clear speech detected]",
                                    isTranscribing = false,
                                    statusMessage = "Completed"
                                )
                            }
                        }
                    }
                } else {
                    _uiState.update { it.copy(isTranscribing = false, statusMessage = "No audio recorded") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Finalize transcription error", e)
                _uiState.update { it.copy(isTranscribing = false, statusMessage = "Error: ${e.localizedMessage}") }
                showSnackbar("Error processing audio: ${e.localizedMessage}")
            }
        }
    }

    fun transcribeAudioFile(uri: Uri) {
        if (!_uiState.value.isModelLoaded) {
            showSnackbar("Please download or select a Whisper model first")
            _uiState.update { it.copy(showModelSheet = true) }
            return
        }

        _uiState.update {
            it.copy(
                isFileTranscribing = true,
                statusMessage = "Decoding audio file..."
            )
        }

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val context = getApplication<Application>()
                val audioData = AudioFileUtils.decodeAudioUri(context, uri)

                if (audioData.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(isFileTranscribing = false, statusMessage = "Could not decode audio") }
                        showSnackbar("Unable to decode audio samples from file")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(statusMessage = "Transcribing file with Whisper on-device...") }
                }

                val result = whisperEngine.transcribeAudio(
                    audioSamples = audioData,
                    language = _uiState.value.selectedLanguage,
                    includeTimestamps = _uiState.value.showTimestamps,
                    threads = _uiState.value.threadCount
                )

                withContext(Dispatchers.Main) {
                    if (result != null && result.text.isNotBlank()) {
                        val ratio = if (result.durationSeconds > 0) {
                            String.format("%.2fx", (result.latencyMs / 1000f) / result.durationSeconds)
                        } else "N/A"
                        val stats = "File: %.1fs | Processed in %.1fs (%s realtime) | Model: %s".format(
                            result.durationSeconds,
                            result.latencyMs / 1000f,
                            ratio,
                            result.modelId
                        )
                        _uiState.update {
                            it.copy(
                                fullTranscript = result.text,
                                livePartialTranscript = "",
                                statsInfo = stats,
                                isFileTranscribing = false,
                                statusMessage = "File transcription finished"
                            )
                        }
                        showSnackbar("File transcription complete!")
                    } else {
                        _uiState.update {
                            it.copy(
                                isFileTranscribing = false,
                                statusMessage = "Transcription returned no speech"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "File transcription error", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isFileTranscribing = false, statusMessage = "Failed: ${e.localizedMessage}") }
                    showSnackbar("File error: ${e.localizedMessage}")
                }
            }
        }
    }

    fun downloadModel(modelInfo: ModelInfo) {
        viewModelScope.launch {
            modelManager.downloadModel(modelInfo)
        }
    }

    fun importModel(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "Importing model file...") }
            val imported = modelManager.importModelFromUri(uri)
            if (imported != null) {
                checkAndAutoLoadModel()
                showSnackbar("Model $imported imported and ready!")
            } else {
                showSnackbar("Failed to import model. Ensure it's a valid ggml .bin file.")
            }
        }
    }

    fun switchActiveModel(modelId: String) {
        modelManager.setActiveModel(modelId)
        checkAndAutoLoadModel()
        showSnackbar("Switched active model to $modelId")
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            if (_uiState.value.activeModelName == modelId) {
                whisperEngine.unload()
            }
            modelManager.deleteModel(modelId)
            showSnackbar("Deleted $modelId")
        }
    }

    fun setLanguage(languageCode: String) {
        _uiState.update { it.copy(selectedLanguage = languageCode) }
    }

    fun toggleTimestamps() {
        _uiState.update { it.copy(showTimestamps = !it.showTimestamps) }
    }

    fun setThreadCount(threads: Int) {
        _uiState.update { it.copy(threadCount = threads.coerceIn(1, 8)) }
    }

    fun setModelSheetVisible(visible: Boolean) {
        _uiState.update { it.copy(showModelSheet = visible) }
    }

    fun clearTranscript() {
        _uiState.update {
            it.copy(
                fullTranscript = "",
                livePartialTranscript = "",
                statsInfo = null,
                statusMessage = "Transcript cleared"
            )
        }
        showSnackbar("Transcript cleared")
    }

    fun copyTranscriptToClipboard() {
        val text = getActiveTranscript()
        if (text.isBlank()) {
            showSnackbar("Nothing to copy")
            return
        }
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Whisper Transcript", text)
        clipboard.setPrimaryClip(clip)
        showSnackbar("Copied to clipboard!")
    }

    fun shareTranscript(context: Context) {
        val text = getActiveTranscript()
        if (text.isBlank()) {
            showSnackbar("Nothing to share")
            return
        }
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "OfflineWhisper Transcript")
            type = "text/plain"
        }
        val shareIntent = Intent.createChooser(sendIntent, "Share Transcript via")
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(shareIntent)
    }

    fun getActiveTranscript(): String {
        return if (_uiState.value.fullTranscript.isNotBlank()) {
            _uiState.value.fullTranscript
        } else {
            _uiState.value.livePartialTranscript
        }
    }

    fun showSnackbar(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun dismissSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
