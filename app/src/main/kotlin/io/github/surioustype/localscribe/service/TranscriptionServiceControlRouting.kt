package io.github.surioustype.localscribe.service

internal class TranscriptionServiceControlRouting {
    var activeJobId: String? = null
        private set

    fun notificationJobIdFor(requestedJobId: String): String = activeJobId ?: requestedJobId

    fun executionStarted(jobId: String) {
        activeJobId = jobId
    }

    fun executionIdle() {
        activeJobId = null
    }
}
