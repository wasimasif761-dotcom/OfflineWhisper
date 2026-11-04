package com.whispercpp.whisper

import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

private const val LOG_TAG = "LibWhisper"

class WhisperContext private constructor(private var ptr: Long) {
    // Meet Whisper C++ constraint: Don't access from more than one thread at a time.
    private val scope: CoroutineScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    suspend fun transcribeData(
        data: FloatArray,
        printTimestamp: Boolean = true,
        language: String = "auto",
        threadCount: Int = WhisperCpuConfig.preferredThreadCount
    ): String = withContext(scope.coroutineContext) {
        require(ptr != 0L) { "WhisperContext has already been released" }
        Log.d(LOG_TAG, "Running transcribeData with $threadCount threads and language=$language (samples: ${data.size})")
        WhisperLib.fullTranscribe(ptr, threadCount, data, language)
        val textCount = WhisperLib.getTextSegmentCount(ptr)
        return@withContext buildString {
            for (i in 0 until textCount) {
                if (printTimestamp) {
                    val textTimestamp = "[${toTimestamp(WhisperLib.getTextSegmentT0(ptr, i))} --> ${toTimestamp(WhisperLib.getTextSegmentT1(ptr, i))}] "
                    val textSegment = WhisperLib.getTextSegment(ptr, i)
                    append("$textTimestamp$textSegment\n")
                } else {
                    append(WhisperLib.getTextSegment(ptr, i))
                    append(" ")
                }
            }
        }.trim()
    }

    suspend fun release() = withContext(scope.coroutineContext) {
        if (ptr != 0L) {
            WhisperLib.freeContext(ptr)
            ptr = 0L
        }
    }

    fun isReleased(): Boolean = ptr == 0L

    protected fun finalize() {
        if (ptr != 0L) {
            runBlocking {
                release()
            }
        }
    }

    companion object {
        fun createContextFromFile(filePath: String): WhisperContext {
            if (!WhisperLib.isLoaded) {
                throw IllegalStateException("Whisper native library failed to load: ${WhisperLib.loadError}")
            }
            val file = File(filePath)
            if (!file.exists()) {
                throw IllegalArgumentException("Model file does not exist at $filePath")
            }
            val ptr = WhisperLib.initContext(filePath)
            if (ptr == 0L) {
                throw RuntimeException("Couldn't initialize whisper context with path: $filePath")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromInputStream(stream: InputStream): WhisperContext {
            if (!WhisperLib.isLoaded) {
                throw IllegalStateException("Whisper native library failed to load: ${WhisperLib.loadError}")
            }
            val ptr = WhisperLib.initContextFromInputStream(stream)
            if (ptr == 0L) {
                throw RuntimeException("Couldn't initialize whisper context from input stream")
            }
            return WhisperContext(ptr)
        }

        fun createContextFromAsset(assetManager: AssetManager, assetPath: String): WhisperContext {
            if (!WhisperLib.isLoaded) {
                throw IllegalStateException("Whisper native library failed to load: ${WhisperLib.loadError}")
            }
            val ptr = WhisperLib.initContextFromAsset(assetManager, assetPath)
            if (ptr == 0L) {
                throw RuntimeException("Couldn't initialize whisper context from asset: $assetPath")
            }
            return WhisperContext(ptr)
        }

        fun getSystemInfo(): String {
            return if (WhisperLib.isLoaded) WhisperLib.getSystemInfo() else "Native Whisper Not Loaded"
        }
    }
}

internal class WhisperLib {
    companion object {
        var isLoaded = false
            private set
        var loadError: String? = null
            private set

        init {
            try {
                // Pre-load dynamic helper libraries if present
                val helperLibs = listOf("omp", "ggml-base", "ggml-cpu", "ggml")
                for (lib in helperLibs) {
                    try {
                        System.loadLibrary(lib)
                        Log.d(LOG_TAG, "Loaded helper library: lib$lib.so")
                    } catch (_: Throwable) {
                        // Statically linked or not present, ignore safely
                    }
                }

                val primaryAbi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "unknown"
                Log.d(LOG_TAG, "Primary ABI: $primaryAbi")

                var loaded = false
                val cpu = cpuInfo() ?: ""

                // 1. Try architecture-optimized library first if supported
                if (primaryAbi == "arm64-v8a" && cpu.contains("fphp")) {
                    try {
                        Log.d(LOG_TAG, "Attempting to load libwhisper_v8fp16_va.so...")
                        System.loadLibrary("whisper_v8fp16_va")
                        loaded = true
                    } catch (t: Throwable) {
                        Log.w(LOG_TAG, "Optimized v8fp16 library load failed, will fallback: ${t.message}")
                    }
                } else if (primaryAbi == "armeabi-v7a" && cpu.contains("vfpv4")) {
                    try {
                        Log.d(LOG_TAG, "Attempting to load libwhisper_vfpv4.so...")
                        System.loadLibrary("whisper_vfpv4")
                        loaded = true
                    } catch (t: Throwable) {
                        Log.w(LOG_TAG, "Optimized vfpv4 library load failed, will fallback: ${t.message}")
                    }
                }

                // 2. Load standard libwhisper.so as reliable default
                if (!loaded) {
                    Log.d(LOG_TAG, "Loading standard libwhisper.so...")
                    System.loadLibrary("whisper")
                    loaded = true
                }

                isLoaded = loaded
                Log.i(LOG_TAG, "Whisper native libraries loaded successfully!")
            } catch (t: Throwable) {
                Log.e(LOG_TAG, "Fatal error initializing Whisper native libraries", t)
                isLoaded = false
                loadError = t.localizedMessage ?: t.toString()
            }
        }

        @JvmStatic
        external fun initContextFromInputStream(inputStream: InputStream): Long

        @JvmStatic
        external fun initContextFromAsset(assetManager: AssetManager, assetPath: String): Long

        @JvmStatic
        external fun initContext(modelPath: String): Long

        @JvmStatic
        external fun freeContext(contextPtr: Long)

        @JvmStatic
        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray, language: String)

        @JvmStatic
        external fun getTextSegmentCount(contextPtr: Long): Int

        @JvmStatic
        external fun getTextSegment(contextPtr: Long, index: Int): String

        @JvmStatic
        external fun getTextSegmentT0(contextPtr: Long, index: Int): Long

        @JvmStatic
        external fun getTextSegmentT1(contextPtr: Long, index: Int): Long

        @JvmStatic
        external fun getSystemInfo(): String

        @JvmStatic
        external fun benchMemcpy(nthread: Int): String

        @JvmStatic
        external fun benchGgmlMulMat(nthread: Int): String

        private fun cpuInfo(): String? {
            return try {
                File("/proc/cpuinfo").inputStream().bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't read /proc/cpuinfo", e)
                null
            }
        }
    }
}

private fun toTimestamp(t: Long, comma: Boolean = false): String {
    var msec = t * 10
    val hr = msec / (1000 * 60 * 60)
    msec -= hr * (1000 * 60 * 60)
    val min = msec / (1000 * 60)
    msec -= min * (1000 * 60)
    val sec = msec / 1000
    msec -= sec * 1000
    val delimiter = if (comma) "," else "."
    return if (hr > 0) {
        String.format("%02d:%02d:%02d%s%03d", hr, min, sec, delimiter, msec)
    } else {
        String.format("%02d:%02d%s%03d", min, sec, delimiter, msec)
    }
}
