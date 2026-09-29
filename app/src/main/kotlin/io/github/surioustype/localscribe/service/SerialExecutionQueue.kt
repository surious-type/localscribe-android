package io.github.surioustype.localscribe.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Serializes service execution requests on the service's main-thread scope.
 *
 * Requests are registered synchronously so a start or resume received while the previous execution
 * is unwinding cannot be lost. All methods and callbacks must run on the scope's dispatcher.
 */
internal class SerialExecutionQueue(
    private val scope: CoroutineScope,
    private val execute: suspend (String) -> Unit,
    private val onStarted: (String) -> Unit,
    private val onIdle: (Int) -> Unit,
) {
    private val pending = ArrayDeque<ExecutionRequest>()
    private var active: ActiveExecution? = null
    private var closed = false

    fun request(jobId: String, startId: Int) {
        if (closed) return
        pending.removeAll { it.jobId == jobId }
        pending.addLast(ExecutionRequest(jobId, startId))
        if (active == null) launchNext()
    }

    fun cancel() {
        closed = true
        pending.clear()
        active?.job?.cancel()
    }

    private fun launchNext() {
        val request = pending.pollFirst() ?: return
        val execution = ActiveExecution()
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    execute(request.jobId)
                } finally {
                    if (active === execution) {
                        active = null
                        if (!closed && pending.isNotEmpty()) {
                            launchNext()
                        } else if (!closed) {
                            onIdle(request.startId)
                        }
                    }
                }
            }
        execution.job = job
        active = execution
        onStarted(request.jobId)
        job.start()
    }

    private data class ExecutionRequest(
        val jobId: String,
        val startId: Int,
    )

    private class ActiveExecution {
        lateinit var job: Job
    }
}
