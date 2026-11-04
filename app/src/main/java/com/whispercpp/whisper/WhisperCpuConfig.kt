package com.whispercpp.whisper

import android.os.Build

object WhisperCpuConfig {
    val preferredThreadCount: Int
        get() {
            val cores = Runtime.getRuntime().availableProcessors()
            return when {
                cores <= 2 -> cores
                cores <= 4 -> cores
                // On big.LITTLE 8-core CPUs (like Helio P35 in Oppo A15s),
                // using 4 threads provides optimal throughput without thermal throttling.
                else -> 4
            }
        }
}
