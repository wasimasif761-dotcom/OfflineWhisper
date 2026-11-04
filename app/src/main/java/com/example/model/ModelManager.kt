package com.example.model

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

private const val TAG = "ModelManager"
private const val PREFS_NAME = "whisper_prefs"
private const val KEY_ACTIVE_MODEL = "active_model_name"

data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val sizeText: String,
    val downloadUrl: String,
    val isMultilingual: Boolean,
    val recommendedForLowEnd: Boolean = false
)

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val modelId: String, val progress: Float, val speedText: String) : DownloadState()
    data class Success(val modelId: String) : DownloadState()
    data class Error(val modelId: String, val message: String) : DownloadState()
}

class ModelManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val availableModels = listOf(
        ModelInfo(
            id = "ggml-tiny.bin",
            displayName = "Whisper Tiny (Multilingual)",
            description = "Fastest speed, minimal RAM (~39 MB). Highly recommended for Oppo A15s & budget devices.",
            sizeText = "~39 MB",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
            isMultilingual = true,
            recommendedForLowEnd = true
        ),
        ModelInfo(
            id = "ggml-base.bin",
            displayName = "Whisper Base (Multilingual)",
            description = "Balanced accuracy & speed (~75 MB). Good multilingual recognition for Urdu, English, etc.",
            sizeText = "~142 MB",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            isMultilingual = true,
            recommendedForLowEnd = false
        ),
        ModelInfo(
            id = "ggml-tiny.en.bin",
            displayName = "Whisper Tiny (English Only)",
            description = "Lightest model (~39 MB) optimized strictly for English audio transcription.",
            sizeText = "~39 MB",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin",
            isMultilingual = false,
            recommendedForLowEnd = true
        ),
        ModelInfo(
            id = "ggml-base.en.bin",
            displayName = "Whisper Base (English Only)",
            description = "Accurate English speech transcription (~142 MB).",
            sizeText = "~142 MB",
            downloadUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin",
            isMultilingual = false,
            recommendedForLowEnd = false
        )
    )

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    private val _installedModels = MutableStateFlow<List<String>>(emptyList())
    val installedModels: StateFlow<List<String>> = _installedModels.asStateFlow()

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    init {
        refreshInstalledModels()
        val savedModel = prefs.getString(KEY_ACTIVE_MODEL, null)
        if (savedModel != null && isModelDownloaded(savedModel)) {
            _activeModelId.value = savedModel
        } else {
            // Default to first installed model if any, otherwise default target ggml-base.bin or ggml-tiny.bin
            _activeModelId.value = _installedModels.value.firstOrNull() ?: "ggml-base.bin"
        }
    }

    private fun getModelsDir(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getModelFile(modelId: String): File {
        return File(getModelsDir(), modelId)
    }

    fun isModelDownloaded(modelId: String): Boolean {
        val file = getModelFile(modelId)
        return file.exists() && file.length() > 1024 * 1024 // At least 1MB
    }

    fun refreshInstalledModels() {
        val dir = getModelsDir()
        val files = dir.listFiles()?.filter { it.isFile && it.length() > 1024 * 1024 }?.map { it.name } ?: emptyList()
        _installedModels.value = files
        if (_activeModelId.value == null || !files.contains(_activeModelId.value)) {
            _activeModelId.value = files.firstOrNull() ?: "ggml-base.bin"
        }
    }

    fun setActiveModel(modelId: String) {
        _activeModelId.value = modelId
        prefs.edit().putString(KEY_ACTIVE_MODEL, modelId).apply()
    }

    suspend fun downloadModel(modelInfo: ModelInfo): Boolean = withContext(Dispatchers.IO) {
        val targetFile = getModelFile(modelInfo.id)
        val tempFile = File(getModelsDir(), "${modelInfo.id}.download")

        _downloadState.value = DownloadState.Downloading(modelInfo.id, 0f, "Connecting...")

        try {
            val request = Request.Builder()
                .url(modelInfo.downloadUrl)
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorMsg = "HTTP Error ${response.code}: ${response.message}"
                Log.e(TAG, errorMsg)
                _downloadState.value = DownloadState.Error(modelInfo.id, errorMsg)
                return@withContext false
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")
            val totalBytes = body.contentLength()
            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(tempFile)

            val buffer = ByteArray(32 * 1024)
            var bytesRead: Int
            var totalRead: Long = 0
            val startTime = System.currentTimeMillis()
            var lastUpdate = 0L

            outputStream.use { fos ->
                inputStream.use { fis ->
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 300) {
                            lastUpdate = now
                            val progress = if (totalBytes > 0) totalRead.toFloat() / totalBytes else 0f
                            val elapsedSec = maxOf(0.1, (now - startTime) / 1000.0)
                            val speedKb = (totalRead / 1024.0) / elapsedSec
                            val speedText = if (speedKb > 1024) {
                                String.format("%.1f MB/s", speedKb / 1024.0)
                            } else {
                                String.format("%.0f KB/s", speedKb)
                            }
                            _downloadState.value = DownloadState.Downloading(modelInfo.id, progress, speedText)
                        }
                    }
                }
            }

            if (tempFile.exists()) {
                if (targetFile.exists()) targetFile.delete()
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            }

            refreshInstalledModels()
            setActiveModel(modelInfo.id)
            _downloadState.value = DownloadState.Success(modelInfo.id)
            Log.i(TAG, "Successfully downloaded model ${modelInfo.id}")
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${modelInfo.id}", e)
            if (tempFile.exists()) tempFile.delete()
            _downloadState.value = DownloadState.Error(modelInfo.id, e.localizedMessage ?: "Download failed")
            return@withContext false
        }
    }

    suspend fun importModelFromUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            // Determine filename or default to custom_model.bin
            var filename = "imported_model.bin"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val name = cursor.getString(nameIndex)
                    if (!name.isNullOrBlank()) filename = name
                }
            }

            if (!filename.endsWith(".bin")) {
                filename += ".bin"
            }

            val targetFile = getModelFile(filename)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (targetFile.exists() && targetFile.length() > 1024 * 1024) {
                refreshInstalledModels()
                setActiveModel(filename)
                Log.i(TAG, "Imported model $filename successfully (${targetFile.length() / 1024 / 1024} MB)")
                return@withContext filename
            } else {
                targetFile.delete()
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import model failed", e)
            return@withContext null
        }
    }

    fun deleteModel(modelId: String): Boolean {
        val file = getModelFile(modelId)
        val deleted = file.delete()
        refreshInstalledModels()
        return deleted
    }

    fun resetDownloadState() {
        _downloadState.value = DownloadState.Idle
    }
}
