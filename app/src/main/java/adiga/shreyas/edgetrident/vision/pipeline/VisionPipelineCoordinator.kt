package adiga.shreyas.edgetrident.vision.pipeline

/**
 * Fans one frame out to the enabled detector, pose, and tracker runners.
 */
class VisionPipelineCoordinator(
    private val objectDetectorRunner: ObjectDetectorRunner? = null,
    private val poseEstimatorRunner: PoseEstimatorRunner? = null,
    private val objectTrackerRunner: ObjectTrackerRunner? = null,
) : AutoCloseable {

    fun process(frame: VisionFrame): VisionPipelineResult {
        val objectDetectionResult = objectDetectorRunner?.detect(frame)
        val poseEstimationResult = poseEstimatorRunner?.estimate(frame)
        val objectTrackingResult = objectTrackerRunner?.track(frame)

        return VisionPipelineResult(
            frameTimestampNanos = frame.timestampNanos,
            objectDetectionResult = objectDetectionResult,
            poseEstimationResult = poseEstimationResult,
            objectTrackingResult = objectTrackingResult,
        )
    }

    override fun close() {
        var failure: Throwable? = null
        listOfNotNull(
            objectDetectorRunner,
            poseEstimatorRunner,
            objectTrackerRunner,
        ).forEach { runner ->
            try {
                runner.close()
            } catch (throwable: Throwable) {
                failure = failure?.apply { addSuppressed(throwable) } ?: throwable
            }
        }

        failure?.let { throw it }
    }
}

data class VisionPipelineResult(
    val frameTimestampNanos: Long,
    val objectDetectionResult: ObjectDetectionResult? = null,
    val poseEstimationResult: PoseEstimationResult? = null,
    val objectTrackingResult: ObjectTrackingResult? = null,
) {
    init {
        require(frameTimestampNanos >= 0L) { "frameTimestampNanos must be non-negative." }
    }

    val hasAnyResult: Boolean
        get() = objectDetectionResult != null ||
            poseEstimationResult != null ||
            objectTrackingResult != null
}
