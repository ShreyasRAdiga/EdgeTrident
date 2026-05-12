package adiga.shreyas.edgetrident.vision.pipeline

/**
 * Contract for pose estimators such as a MediaPipe Pose implementation.
 */
fun interface PoseEstimatorRunner : AutoCloseable {
    fun estimate(frame: VisionFrame): PoseEstimationResult

    override fun close() = Unit
}

data class PoseEstimationResult(
    val frameTimestampNanos: Long,
    val poses: List<PoseObservation>,
) {
    init {
        require(frameTimestampNanos >= 0L) { "frameTimestampNanos must be non-negative." }
    }

    companion object {
        fun empty(frameTimestampNanos: Long) = PoseEstimationResult(
            frameTimestampNanos = frameTimestampNanos,
            poses = emptyList(),
        )
    }
}

data class PoseObservation(
    val landmarks: List<PoseLandmark>,
    val confidence: Float? = null,
    val poseId: Long? = null,
) {
    init {
        confidence?.let { requireConfidence("confidence", it) }
        poseId?.let { require(it >= 0L) { "poseId must be non-negative." } }
    }
}

data class PoseLandmark(
    val index: Int,
    val position: NormalizedPoint3D,
    val visibility: Float? = null,
    val presence: Float? = null,
) {
    init {
        require(index >= 0) { "index must be non-negative." }
        visibility?.let { requireConfidence("visibility", it) }
        presence?.let { requireConfidence("presence", it) }
    }
}
