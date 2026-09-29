package io.github.surioustype.localscribe.service

/** Ensures a cold service cannot enqueue work until process recovery has completed. */
class ServiceStartRecoveryGate(
    private val recover: suspend () -> Unit,
    private val queue: suspend (jobId: String, startId: Int) -> Unit,
    private val onFailure: suspend (Throwable) -> Unit,
) {
    suspend fun start(jobId: String, startId: Int) {
        try {
            recover()
            queue(jobId, startId)
        } catch (failure: Throwable) {
            onFailure(failure)
        }
    }
}
