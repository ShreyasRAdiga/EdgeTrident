package adiga.shreyas.edgetrident.vision.pipeline

import adiga.shreyas.edgetrident.litevisionengine.LiteVisionError
import adiga.shreyas.edgetrident.litevisionengine.TrackingResult
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionPipelineCoordinatorTest {

    @Test
    fun processFansOutSameFrameToEveryRunner() {
        val detector = RecordingDetector()
        val poseEstimator = RecordingPoseEstimator()
        val tracker = RecordingTracker()
        val coordinator = VisionPipelineCoordinator(
            objectDetectorRunner = detector,
            poseEstimatorRunner = poseEstimator,
            objectTrackerRunner = tracker,
        )
        val frame = testFrame(timestampNanos = 123L)

        val result = coordinator.process(frame)

        assertSame(frame, detector.receivedFrame)
        assertSame(frame, poseEstimator.receivedFrame)
        assertSame(frame, tracker.receivedFrame)
        assertEquals(123L, result.frameTimestampNanos)
        assertEquals("racket", result.objectDetectionResult?.detections?.single()?.label)
        assertEquals(0, result.poseEstimationResult?.poses?.single()?.landmarks?.single()?.index)
        assertEquals(7L, result.objectTrackingResult?.tracks?.single()?.trackId)
        assertTrue(result.hasAnyResult)
    }

    @Test
    fun processAllowsAllRunnersToBeAbsent() {
        val coordinator = VisionPipelineCoordinator()

        val result = coordinator.process(testFrame(timestampNanos = 456L))

        assertEquals(456L, result.frameTimestampNanos)
        assertNull(result.objectDetectionResult)
        assertNull(result.poseEstimationResult)
        assertNull(result.objectTrackingResult)
        assertFalse(result.hasAnyResult)
    }

    @Test
    fun visionFrameRejectsHeapBuffersAtContractBoundary() {
        val error = assertThrows(LiteVisionError.InvalidFrame::class.java) {
            VisionFrame(
                buffer = ByteBuffer.allocate(16),
                width = 2,
                height = 2,
                pixelFormat = VisionPixelFormat.RGBA_8888,
                rowStrideBytes = 8,
                timestampNanos = 1L,
            )
        }

        assertTrue(error.message.orEmpty().contains("direct ByteBuffer"))
    }

    @Test
    fun liteVisionTrackerRunnerMapsNativeTrackingResults() {
        val frame = testFrame(timestampNanos = 789L)
        val runner = LiteVisionObjectTrackerRunner(
            trackFrame = {
                listOf(
                    TrackingResult(
                        trackId = 3,
                        classIndex = 11,
                        confidence = 0.7f,
                        boundingBox = TrackingResult.BoundingBox(
                            left = 0.2f,
                            top = 0.3f,
                            right = 0.6f,
                            bottom = 0.9f,
                        ),
                        timestampNanos = it.timestampNanos,
                    ),
                )
            },
        )

        val result = runner.track(frame)

        val track = result.tracks.single()
        assertEquals(789L, result.frameTimestampNanos)
        assertEquals(3L, track.trackId)
        assertEquals(11, track.classIndex)
        assertEquals(0.7f, track.confidence)
        assertEquals(NormalizedRect(0.2f, 0.3f, 0.6f, 0.9f), track.bounds)
    }

    private class RecordingDetector : ObjectDetectorRunner {
        lateinit var receivedFrame: VisionFrame

        override fun detect(frame: VisionFrame): ObjectDetectionResult {
            receivedFrame = frame
            return ObjectDetectionResult(
                frameTimestampNanos = frame.timestampNanos,
                detections = listOf(
                    DetectedObject(
                        label = "racket",
                        confidence = 0.96f,
                        bounds = NormalizedRect(
                            left = 0.1f,
                            top = 0.2f,
                            right = 0.4f,
                            bottom = 0.8f,
                        ),
                        classIndex = 0,
                    ),
                ),
            )
        }
    }

    private class RecordingPoseEstimator : PoseEstimatorRunner {
        lateinit var receivedFrame: VisionFrame

        override fun estimate(frame: VisionFrame): PoseEstimationResult {
            receivedFrame = frame
            return PoseEstimationResult(
                frameTimestampNanos = frame.timestampNanos,
                poses = listOf(
                    PoseObservation(
                        landmarks = listOf(
                            PoseLandmark(
                                index = 0,
                                position = NormalizedPoint3D(
                                    x = 0.5f,
                                    y = 0.25f,
                                    z = -0.1f,
                                ),
                                visibility = 0.9f,
                            ),
                        ),
                        confidence = 0.8f,
                    ),
                ),
            )
        }
    }

    private class RecordingTracker : ObjectTrackerRunner {
        lateinit var receivedFrame: VisionFrame

        override fun track(frame: VisionFrame): ObjectTrackingResult {
            receivedFrame = frame
            return ObjectTrackingResult(
                frameTimestampNanos = frame.timestampNanos,
                tracks = listOf(
                    TrackedObject(
                        trackId = 7L,
                        bounds = NormalizedRect(
                            left = 0.1f,
                            top = 0.2f,
                            right = 0.4f,
                            bottom = 0.8f,
                        ),
                        label = "racket",
                        confidence = 0.88f,
                    ),
                ),
            )
        }
    }

    private fun testFrame(timestampNanos: Long): VisionFrame {
        val width = 2
        val height = 2
        return VisionFrame(
            buffer = ByteBuffer.allocateDirect(width * height * 4),
            width = width,
            height = height,
            pixelFormat = VisionPixelFormat.RGBA_8888,
            rowStrideBytes = width * 4,
            timestampNanos = timestampNanos,
            rotationDegrees = 90,
        )
    }
}
