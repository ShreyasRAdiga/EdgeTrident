package adiga.shreyas.edgetrident.vision.pipeline

import adiga.shreyas.edgetrident.litevisionengine.LiteVisionEngine
import adiga.shreyas.edgetrident.litevisionengine.NcnnModelSpec
import adiga.shreyas.edgetrident.litevisionengine.TrackingResult

class LiteVisionObjectTrackerRunner private constructor(
    private val trackFrame: (VisionFrame) -> List<TrackingResult>,
    private val closeAction: () -> Unit,
) : ObjectTrackerRunner {

    constructor(
        engine: LiteVisionEngine,
        modelId: String = NcnnModelSpec.DEFAULT_OBJECT_TRACKER_ID,
        closeEngine: Boolean = true,
    ) : this(
        trackFrame = { frame -> engine.track(frame, modelId) },
        closeAction = { if (closeEngine) engine.close() },
    )

    override fun track(frame: VisionFrame): ObjectTrackingResult {
        val tracks = trackFrame(frame).map { result ->
            TrackedObject(
                trackId = result.trackId.toLong(),
                bounds = result.boundingBox.toNormalizedRect(),
                classIndex = result.classIndex,
                confidence = result.confidence,
            )
        }

        return ObjectTrackingResult(
            frameTimestampNanos = frame.timestampNanos,
            tracks = tracks,
        )
    }

    override fun close() {
        closeAction()
    }

    internal constructor(
        trackFrame: (VisionFrame) -> List<TrackingResult>,
    ) : this(trackFrame, {})

    private fun TrackingResult.BoundingBox.toNormalizedRect(): NormalizedRect =
        NormalizedRect(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
        )
}
