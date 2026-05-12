package adiga.shreyas.edgetrident.vision.pipeline

/**
 * Contract for object detectors such as a YOLO 11 implementation.
 */
fun interface ObjectDetectorRunner : AutoCloseable {
    fun detect(frame: VisionFrame): ObjectDetectionResult

    override fun close() = Unit
}

data class ObjectDetectionResult(
    val frameTimestampNanos: Long,
    val detections: List<DetectedObject>,
) {
    init {
        require(frameTimestampNanos >= 0L) { "frameTimestampNanos must be non-negative." }
    }

    companion object {
        fun empty(frameTimestampNanos: Long) = ObjectDetectionResult(
            frameTimestampNanos = frameTimestampNanos,
            detections = emptyList(),
        )
    }
}

data class DetectedObject(
    val label: String,
    val confidence: Float,
    val bounds: NormalizedRect,
    val classIndex: Int? = null,
) {
    init {
        require(label.isNotBlank()) { "label must not be blank." }
        requireConfidence("confidence", confidence)
        classIndex?.let { require(it >= 0) { "classIndex must be non-negative." } }
    }
}
