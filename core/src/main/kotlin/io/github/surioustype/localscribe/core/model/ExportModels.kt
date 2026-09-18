package io.github.surioustype.localscribe.core.model

enum class ExportFormat {
    TXT,
    MARKDOWN,
    SRT,
    VTT,
    JSON,
}

data class ExportDocument(
    val suggestedFileName: String,
    val mimeType: String,
    val content: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ExportDocument &&
            suggestedFileName == other.suggestedFileName &&
            mimeType == other.mimeType &&
            content.contentEquals(other.content)

    override fun hashCode(): Int {
        var result = suggestedFileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}
