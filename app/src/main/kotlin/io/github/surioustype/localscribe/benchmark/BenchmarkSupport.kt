package io.github.surioustype.localscribe.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import io.github.surioustype.localscribe.core.model.HardwareProfile
import java.security.MessageDigest

fun interface BenchmarkClock {
    fun elapsedRealtimeMs(): Long

    fun epochMs(): Long = System.currentTimeMillis()
}

data class BenchmarkSample(
    val memoryBytes: Long?,
    val thermalStatus: Int?,
)

fun interface BenchmarkSampler {
    fun snapshot(): BenchmarkSample
}

class AndroidBenchmarkSampler(
    private val context: Context,
) : BenchmarkSampler {
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    override fun snapshot(): BenchmarkSample {
        val javaHeap = Runtime.getRuntime().run { totalMemory() - freeMemory() }
        val nativeHeap = Debug.getNativeHeapAllocatedSize()
        val pss =
            activityManager
                .getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
                .firstOrNull()
                ?.totalPss
                ?.toLong()
                ?.times(1024)
        return BenchmarkSample(
            memoryBytes = maxOf(javaHeap + nativeHeap, pss ?: 0L),
            thermalStatus =
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.Q
                ) {
                    powerManager.currentThermalStatus
                } else {
                    null
                },
        )
    }
}

object AndroidHardwareProfile {
    fun read(context: Context): HardwareProfile {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return HardwareProfile(
            deviceId = stableDeviceProfileId(),
            totalMemoryBytes = memory.totalMem,
            availableMemoryBytes = memory.availMem,
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            androidApiLevel = Build.VERSION.SDK_INT,
        )
    }

    private fun stableDeviceProfileId(): String {
        val publicProfile =
            listOf(
                Build.MANUFACTURER,
                Build.BRAND,
                Build.DEVICE,
                Build.MODEL,
                Build.VERSION.SDK_INT,
            ).joinToString("|")
        return MessageDigest
            .getInstance("SHA-256")
            .digest(publicProfile.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(24)
    }
}
