package com.uoai.core.resources

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.CopyOnWriteArrayList

@Serializable
enum class ComputeCoexistencePolicy {
    /** Allow ASR on throttled CPU threads while LLM runs on GPU/NPU; auto-serialize if LLM runs on CPU. */
    SMART_THROTTLE,
    /** Strictly serialize LLM and ASR inference regardless of hardware accelerator. */
    STRICT_SERIAL,
}

enum class ThermalLevel { NOMINAL, MODERATE, SEVERE, CRITICAL }

data class DeviceMemoryInfo(
    val availMb: Long,
    val totalMb: Long,
    val lowMemory: Boolean,
)

data class ResourceSnapshot(
    val deviceAvailMb: Long = 0,
    val deviceTotalMb: Long = 0,
    val isLowMemory: Boolean = false,
    val thermalLevel: ThermalLevel = ThermalLevel.NOMINAL,
    val llmGenerating: Boolean = false,
    val llmOnCpu: Boolean = false,
    val asrTranscribing: Boolean = false,
    val recommendedAsrThreads: Int = 4,
)

sealed interface LoadGuardResult {
    data object Allowed : LoadGuardResult
    data class InsufficientMemory(val availMb: Long, val requiredMb: Long, val message: String) : LoadGuardResult
}

sealed interface ExecutionGuardResult {
    data object Allowed : ExecutionGuardResult
    data class ThermalThrottled(val level: ThermalLevel, val message: String) : ExecutionGuardResult
}

/** Platform abstraction so resource coordination stays deterministic in JVM unit tests. */
interface DeviceResourceMonitor {
    fun memoryInfo(): DeviceMemoryInfo
    fun thermalLevel(): ThermalLevel
    fun availableCpuCores(): Int
}

class AndroidDeviceResourceMonitor(context: Context) : DeviceResourceMonitor {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun memoryInfo(): DeviceMemoryInfo {
        val info = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(info)
        return DeviceMemoryInfo(
            availMb = info.availMem / BYTES_PER_MB,
            totalMb = info.totalMem / BYTES_PER_MB,
            lowMemory = info.lowMemory,
        )
    }

    override fun thermalLevel(): ThermalLevel {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalLevel.NOMINAL
        return when (powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) {
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT,
            -> ThermalLevel.NOMINAL
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
            -> ThermalLevel.CRITICAL
            else -> ThermalLevel.NOMINAL
        }
    }

    override fun availableCpuCores(): Int {
        val total = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        // Prefer counting big/prime performance cores on heterogeneous big.LITTLE ARM SoCs
        // so ggml barriers do not stall waiting on slow little cores.
        val freqs = (0 until total).mapNotNull { idx ->
            runCatching {
                java.io.File("/sys/devices/system/cpu/cpu$idx/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toLongOrNull()
            }.getOrNull()
        }
        if (freqs.size >= 4) {
            val minFreq = freqs.min()
            val perfCores = freqs.count { it > minFreq }
            if (perfCores in 2..6) return perfCores
        }
        return total
    }

    private companion object {
        const val BYTES_PER_MB = 1_048_576L
    }
}

/**
 * Coordinates CPU, RAM, thermal headroom, and memory-trim signals across LLM (Gemma) and ASR (Whisper).
 *
 * Serialization semantics:
 * - ASR always acquires [cpuComputeMutex] during active native decoding (1 ASR job at a time).
 * - LLM acquires [cpuComputeMutex] whenever LLM runs on CPU OR [coexistencePolicy] == [ComputeCoexistencePolicy.STRICT_SERIAL].
 * - When LLM runs on GPU/NPU under [ComputeCoexistencePolicy.SMART_THROTTLE], both run concurrently while
 *   ASR automatically throttles its CPU thread budget to protect memory bandwidth and thermals.
 */
class ComputeResourceCoordinator(
    private val monitor: DeviceResourceMonitor,
    private val policyProvider: () -> ComputeCoexistencePolicy = { ComputeCoexistencePolicy.SMART_THROTTLE },
) {
    private val cpuComputeMutex = Mutex()
    private val trimListeners = CopyOnWriteArrayList<(Int) -> Unit>()
    private val _snapshot = MutableStateFlow(buildSnapshot(llmGen = false, llmCpu = false, asrBusy = false))
    val snapshot: StateFlow<ResourceSnapshot> = _snapshot.asStateFlow()

    fun refresh(): ResourceSnapshot {
        val cur = _snapshot.value
        val next = buildSnapshot(cur.llmGenerating, cur.llmOnCpu, cur.asrTranscribing)
        _snapshot.value = next
        return next
    }

    fun registerTrimListener(listener: (trimLevel: Int) -> Unit) {
        trimListeners.add(listener)
    }

    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        refresh()
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            for (listener in trimListeners) {
                runCatching { listener(level) }
            }
        }
    }

    @Suppress("DEPRECATION")
    fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)

    fun canLoadAsr(minRequiredAvailMb: Long): LoadGuardResult {
        val mem = monitor.memoryInfo()
        refresh()
        if (mem.totalMb > 0L && (mem.lowMemory || mem.availMb < minRequiredAvailMb)) {
            return LoadGuardResult.InsufficientMemory(
                availMb = mem.availMb,
                requiredMb = minRequiredAvailMb,
                message = "Not enough free memory to load ASR model (available: ${mem.availMb} MB, required: >= $minRequiredAvailMb MB). Unload LLM or choose a smaller profile.",
            )
        }
        return LoadGuardResult.Allowed
    }

    fun canStartAsrExecution(): ExecutionGuardResult {
        val thermal = monitor.thermalLevel()
        refresh()
        if (thermal == ThermalLevel.CRITICAL) {
            return ExecutionGuardResult.ThermalThrottled(
                level = thermal,
                message = "Device thermal state is CRITICAL; pausing speech recognition until device cools.",
            )
        }
        return ExecutionGuardResult.Allowed
    }

    fun recommendedAsrThreads(userMaxThreads: Int = 0): Int {
        val cores = monitor.availableCpuCores()
        val defaultCap = cores.coerceIn(2, 4)
        val base = if (userMaxThreads > 0) userMaxThreads.coerceIn(1, 8) else defaultCap
        val thermal = monitor.thermalLevel()
        val cur = _snapshot.value
        val thermalCap = when (thermal) {
            ThermalLevel.CRITICAL, ThermalLevel.SEVERE -> 1
            ThermalLevel.MODERATE -> 2
            ThermalLevel.NOMINAL -> base
        }
        val llmCap = if (cur.llmGenerating && !cur.llmOnCpu) 2 else base
        return minOf(base, thermalCap, llmCap).coerceAtLeast(1)
    }

    suspend fun <T> withLlmExecution(onCpu: Boolean, block: suspend () -> T): T {
        val serialize = onCpu || policyProvider() == ComputeCoexistencePolicy.STRICT_SERIAL
        return if (serialize) {
            cpuComputeMutex.withLock {
                updateFlags(llmGen = true, llmCpu = onCpu)
                try {
                    block()
                } finally {
                    updateFlags(llmGen = false, llmCpu = false)
                }
            }
        } else {
            updateFlags(llmGen = true, llmCpu = false)
            try {
                block()
            } finally {
                updateFlags(llmGen = false, llmCpu = false)
            }
        }
    }

    suspend fun <T> withAsrExecution(block: suspend (threads: Int) -> T): T {
        return cpuComputeMutex.withLock {
            updateFlags(asrBusy = true)
            try {
                val threads = recommendedAsrThreads()
                block(threads)
            } finally {
                updateFlags(asrBusy = false)
            }
        }
    }

    private fun updateFlags(
        llmGen: Boolean? = null,
        llmCpu: Boolean? = null,
        asrBusy: Boolean? = null,
    ) {
        _snapshot.update { cur ->
            buildSnapshot(
                llmGen = llmGen ?: cur.llmGenerating,
                llmCpu = llmCpu ?: cur.llmOnCpu,
                asrBusy = asrBusy ?: cur.asrTranscribing,
            )
        }
    }

    private fun buildSnapshot(llmGen: Boolean, llmCpu: Boolean, asrBusy: Boolean): ResourceSnapshot {
        val mem = monitor.memoryInfo()
        val thermal = monitor.thermalLevel()
        val cores = monitor.availableCpuCores()
        val base = cores.coerceIn(2, 4)
        val recThreads = when {
            thermal >= ThermalLevel.SEVERE -> 1
            thermal == ThermalLevel.MODERATE || (llmGen && !llmCpu) -> minOf(base, 2)
            else -> base
        }.coerceAtLeast(1)
        return ResourceSnapshot(
            deviceAvailMb = mem.availMb,
            deviceTotalMb = mem.totalMb,
            isLowMemory = mem.lowMemory,
            thermalLevel = thermal,
            llmGenerating = llmGen,
            llmOnCpu = llmCpu,
            asrTranscribing = asrBusy,
            recommendedAsrThreads = recThreads,
        )
    }
}

