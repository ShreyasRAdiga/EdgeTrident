package adiga.shreyas.edgetrident.litevisionengine

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteVisionContractsTest {

    @Test
    fun configRejectsInvalidThreadCount() {
        val error = assertThrows(LiteVisionError.InvalidConfig::class.java) {
            LiteVisionConfig(numThreads = 0)
        }

        assertTrue(error.message.orEmpty().contains("numThreads"))
    }

    @Test
    fun modelSpecRejectsAbsoluteAssetPaths() {
        val error = assertThrows(LiteVisionError.InvalidConfig::class.java) {
            NcnnModelSpec(
                id = "tracker",
                paramAssetPath = "/models/tracker.param",
                binAssetPath = "models/tracker.bin",
                inputName = "images",
                outputNames = listOf("output0"),
                inputWidth = 640,
                inputHeight = 640,
            )
        }

        assertTrue(error.message.orEmpty().contains("paramAssetPath"))
    }

    @Test
    fun visionFrameRequiresDirectBuffer() {
        val error = assertThrows(LiteVisionError.InvalidFrame::class.java) {
            VisionFrame(
                buffer = ByteBuffer.allocate(16),
                width = 2,
                height = 2,
                pixelFormat = VisionFrame.PixelFormat.RGBA_8888,
                rowStrideBytes = 8,
                timestampNanos = 1L,
            )
        }

        assertTrue(error.message.orEmpty().contains("direct ByteBuffer"))
    }

    @Test
    fun visionFrameAccountsForYuv420Capacity() {
        val width = 4
        val height = 4
        val rowStrideBytes = 4

        val frame = VisionFrame(
            buffer = ByteBuffer.allocateDirect(rowStrideBytes * height * 3 / 2),
            width = width,
            height = height,
            pixelFormat = VisionFrame.PixelFormat.NV21,
            rowStrideBytes = rowStrideBytes,
            timestampNanos = 2L,
            rotationDegrees = 270,
        )

        assertEquals(VisionFrame.PixelFormat.NV21, frame.pixelFormat)
    }

    @Test
    fun trackingResultUnpacksNativeRows() {
        val results = TrackingResult.fromNativeArray(
            values = floatArrayOf(
                7f,
                2f,
                0.8f,
                0.1f,
                0.2f,
                0.5f,
                0.9f,
            ),
            timestampNanos = 123L,
        )

        val result = results.single()
        assertEquals(7, result.trackId)
        assertEquals(2, result.classIndex)
        assertEquals(0.8f, result.confidence)
        assertEquals(TrackingResult.BoundingBox(0.1f, 0.2f, 0.5f, 0.9f), result.boundingBox)
        assertEquals(123L, result.timestampNanos)
    }

    @Test
    fun trackingResultRejectsMalformedNativeRows() {
        val error = assertThrows(LiteVisionError.NativeFailure::class.java) {
            TrackingResult.fromNativeArray(floatArrayOf(1f, 2f), timestampNanos = 123L)
        }

        assertTrue(error.message.orEmpty().contains("not divisible"))
    }

    @Test
    fun yoloAndTrackNetSpecsPointToExpectedAssets() {
        val yolo = NcnnModelSpec.yoloV7()
        val trackNet = NcnnModelSpec.trackNet()

        assertEquals("models/litevision/yolov7.param", yolo.paramAssetPath)
        assertEquals("models/litevision/yolov7.bin", yolo.binAssetPath)
        assertEquals("models/litevision/tracknet.param", trackNet.paramAssetPath)
        assertEquals("models/litevision/tracknet.bin", trackNet.binAssetPath)
    }
}
