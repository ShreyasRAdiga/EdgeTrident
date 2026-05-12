package adiga.shreyas.edgetrident.vision.pipeline

/**
 * Contract for object trackers.
 */
fun interface ObjectTrackerRunner : AutoCloseable {
    fun track(frame: VisionFrame): ObjectTrackingResult

    override fun close() = Unit
}

data class ObjectTrackingResult(
    val frameTimestampNanos: Long,
    val tracks: List<TrackedObject>,
) {
    init {
        require(frameTimestampNanos >= 0L) { "frameTimestampNanos must be non-negative." }
    }

    companion object {
        fun empty(frameTimestampNanos: Long) = ObjectTrackingResult(
            frameTimestampNanos = frameTimestampNanos,
            tracks = emptyList(),
        )
    }
}

data class TrackedObject(
    val trackId: Long,
    val bounds: NormalizedRect,
    val classIndex: Int? = null,
    val label: String? = null,
    val confidence: Float? = null,
    val state: TrackState = TrackState.TRACKED,
) {
    init {
        require(trackId >= 0L) { "trackId must be non-negative." }
        classIndex?.let { require(it >= 0) { "classIndex must be non-negative." } }
        label?.let { require(it.isNotBlank()) { "label must not be blank." } }
        confidence?.let { requireConfidence("confidence", it) }
    }
}

enum class TrackState {
    TENTATIVE,
    TRACKED,
    LOST,
}
