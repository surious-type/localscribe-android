package io.github.surioustype.localscribe.core.model

@JvmInline
value class AppVersion(val value: String) : Comparable<AppVersion> {
    init {
        require(VERSION_PATTERN.matches(value)) { "Invalid application version" }
        val prerelease = value.substringAfter('-', missingDelimiterValue = "")
        require(
            prerelease.isEmpty() ||
                prerelease.split('.').none { identifier ->
                    identifier.length > 1 &&
                        identifier.all(Char::isDigit) &&
                        identifier.startsWith('0')
                },
        ) { "Numeric prerelease identifiers must not contain leading zeroes" }
    }

    override fun compareTo(other: AppVersion): Int {
        val left = value.removePrefix("v").substringBefore('-').split('.')
        val right =
            other.value
                .removePrefix("v")
                .substringBefore('-')
                .split('.')
        left.indices.forEach { index ->
            compareNumericIdentifiers(
                left[index],
                right[index],
            ).takeIf { it != 0 }?.let { return it }
        }

        val leftPrerelease = value.substringAfter('-', missingDelimiterValue = "")
        val rightPrerelease = other.value.substringAfter('-', missingDelimiterValue = "")
        if (leftPrerelease.isEmpty() || rightPrerelease.isEmpty()) {
            return when {
                leftPrerelease.isEmpty() && rightPrerelease.isEmpty() -> 0
                leftPrerelease.isEmpty() -> 1
                else -> -1
            }
        }

        val leftParts = leftPrerelease.split('.')
        val rightParts = rightPrerelease.split('.')
        repeat(minOf(leftParts.size, rightParts.size)) { index ->
            comparePrereleaseIdentifiers(leftParts[index], rightParts[index])
                .takeIf {
                    it != 0
                }?.let { return it }
        }
        return leftParts.size.compareTo(rightParts.size)
    }

    companion object {
        private val VERSION_PATTERN =
            Regex(
                """v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?""",
            )

        private fun comparePrereleaseIdentifiers(left: String, right: String): Int {
            val leftNumeric = left.all(Char::isDigit)
            val rightNumeric = right.all(Char::isDigit)
            return when {
                leftNumeric && rightNumeric -> compareNumericIdentifiers(left, right)
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
        }

        private fun compareNumericIdentifiers(left: String, right: String): Int =
            left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
    }
}

data class ReleaseAsset(
    val fileName: String,
    val downloadUrl: String,
    val bytes: Long,
    val sha256: String,
)

data class AppRelease(
    val version: AppVersion,
    val releaseNotes: String,
    val publishedAtEpochMs: Long,
    val apk: ReleaseAsset,
)

enum class UpdateAvailability {
    UNKNOWN,
    DISABLED,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    FAILED,
}

data class UpdateState(
    val availability: UpdateAvailability,
    val release: AppRelease? = null,
    val lastCheckedAtEpochMs: Long? = null,
    val failure: DomainFailure? = null,
)

enum class UpdateDownloadStatus {
    IDLE,
    DOWNLOADING,
    VERIFYING,
    READY_TO_INSTALL,
    CANCELLED,
    FAILED,
}

data class UpdateDownload(
    val status: UpdateDownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val localUri: String? = null,
    val failure: DomainFailure? = null,
)
