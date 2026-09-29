package io.github.surioustype.localscribe.execution

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

class AndroidThermalMonitor(
    context: Context,
    private val callbackExecutor: Executor = context.compatibleMainExecutor(),
) : ThermalState, AutoCloseable {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val started = AtomicBoolean(false)
    private val state = MutableStateFlow(currentSnapshot())
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun observe(): Flow<ThermalSnapshot> = state.asStateFlow()

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            !started.compareAndSet(false, true)
        ) {
            return
        }
        state.value = currentSnapshot()
        val registeredListener =
            PowerManager.OnThermalStatusChangedListener { status ->
                state.value = status.toSnapshot()
            }
        listener = registeredListener
        powerManager.addThermalStatusListener(callbackExecutor, registeredListener)
    }

    override fun snapshot(): ThermalSnapshot = state.value

    override fun close() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && started.compareAndSet(true, false)) {
            listener?.let(powerManager::removeThermalStatusListener)
            listener = null
        }
    }

    private fun currentSnapshot(): ThermalSnapshot =
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.Q
        ) {
            powerManager.currentThermalStatus.toSnapshot()
        } else {
            ThermalSnapshot(ThermalSeverity.NONE)
        }

    private fun Int.toSnapshot(): ThermalSnapshot =
        ThermalSnapshot(
            when {
                this >= PowerManager.THERMAL_STATUS_CRITICAL -> ThermalSeverity.CRITICAL
                this >= PowerManager.THERMAL_STATUS_SEVERE -> ThermalSeverity.SEVERE
                this >= PowerManager.THERMAL_STATUS_MODERATE -> ThermalSeverity.MODERATE
                else -> ThermalSeverity.NONE
            },
        )
}

private fun Context.compatibleMainExecutor(): Executor =
    if (Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.P
    ) {
        mainExecutor
    } else {
        val handler = Handler(Looper.getMainLooper())
        Executor { command -> handler.post(command) }
    }

class RecoveryInitializer(
    private val repository: DurableTranscriptionRepository,
    private val clock: ExecutionClock = ExecutionClock { System.currentTimeMillis() },
) {
    suspend fun recover() = repository.recoverInterrupted(clock.nowEpochMs())
}
