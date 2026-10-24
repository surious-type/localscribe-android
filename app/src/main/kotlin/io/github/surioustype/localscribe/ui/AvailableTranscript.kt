package io.github.surioustype.localscribe.ui

import io.github.surioustype.localscribe.core.domain.TranscriptAssembler
import io.github.surioustype.localscribe.core.model.TranscriptSegment
import io.github.surioustype.localscribe.core.model.TranscriptionChunk

/**
 * Room delivers chunks and their transcript segments on separate Flows. On a cold screen open,
 * the segment Flow can emit before the matching chunk Flow. That is not database corruption:
 * only assemble checkpoints whose metadata is available in this UI snapshot.
 *
 * Never modify or delete stored segments. The next Flow emission displays any missing rows.
 */
internal fun assembleAvailableTranscript(
    chunks: List<TranscriptionChunk>,
    rawSegments: List<TranscriptSegment>,
): List<TranscriptSegment> {
    val availableChunks = chunks.filter { it.startMs >= 0L && it.endMs > it.startMs }
    val ids = availableChunks.mapTo(mutableSetOf()) { it.id }
    val availableSegments =
        rawSegments.filter {
            it.chunkId in ids &&
                it.absoluteStartMs >= 0L &&
                it.absoluteEndMs > it.absoluteStartMs
        }
    return TranscriptAssembler().assemble(availableChunks, availableSegments)
}
