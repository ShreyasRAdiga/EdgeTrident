package adiga.shreyas.edgetrident.vision.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import adiga.shreyas.edgetrident.litevisionengine.LiteVisionConfig
import adiga.shreyas.edgetrident.litevisionengine.LiteVisionEngine
import adiga.shreyas.edgetrident.litevisionengine.NcnnModelSpec
import adiga.shreyas.edgetrident.litevisionengine.VisionFrame
import java.nio.ByteBuffer
import kotlin.math.max

class CameraInferenceAnalyzer(
    context: Context,
    private val requestedVulkan: Boolean,
    private val onMetrics: (CameraInferenceMetrics) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private companion object {
        private const val LOG_TAG = "EdgeTridentInference"
    }

    private val engine: LiteVisionEngine?
    private val nativeCapabilities: String
    private var modelReady = false
    private var modelStatus: String
    private var frameBuffer = ByteBuffer.allocateDirect(0)
    private var frameCount = 0L
    private var lastFrameEndNanos = 0L
    private var closed = false

    init {
        val engineResult = runCatching {
            LiteVisionEngine(
                context = context.applicationContext,
                config = LiteVisionConfig(preferVulkan = requestedVulkan),
            )
        }
        engine = engineResult.getOrNull()

        nativeCapabilities = engine?.let { openedEngine ->
            runCatching { openedEngine.capabilities }
                .getOrElse { error -> "capability query failed: ${error.message.orEmpty()}" }
        } ?: "native engine unavailable: ${engineResult.exceptionOrNull()?.message.orEmpty()}"

        modelStatus = engine?.let { openedEngine ->
            runCatching {
                openedEngine.loadModel(NcnnModelSpec.objectTracker(preferVulkan = requestedVulkan))
                modelReady = true
                "model loaded"
            }.getOrElse { error ->
                "model not loaded: ${error.message.orEmpty()}"
            }
        } ?: "model not loaded: native engine unavailable"
    }

    override fun analyze(image: ImageProxy) {
        if (closed) {
            image.close()
            return
        }

        val frameStartNanos = System.nanoTime()
        var trackCount = 0
        var inferenceRan = false
        var lastError: String? = null
        var copyMs = 0.0
        var inferenceMs = 0.0

        try {
            val copyStartNanos = System.nanoTime()
            val directBuffer = copyYuv420ToDirectBuffer(image)
            copyMs = elapsedMs(copyStartNanos, System.nanoTime())

            val inferenceStartNanos = System.nanoTime()
            val frame = VisionFrame(
                buffer = directBuffer,
                width = image.width,
                height = image.height,
                pixelFormat = VisionFrame.PixelFormat.YUV_420_888,
                rowStrideBytes = image.planes.first().rowStride,
                timestampNanos = image.imageInfo.timestamp,
                rotationDegrees = image.imageInfo.rotationDegrees,
            )

            if (modelReady && engine != null) {
                trackCount = engine.track(frame).size
                inferenceRan = true
            }
            inferenceMs = elapsedMs(inferenceStartNanos, System.nanoTime())
        } catch (throwable: Throwable) {
            lastError = throwable.message ?: throwable::class.java.simpleName
        } finally {
            val frameEndNanos = System.nanoTime()
            frameCount += 1
            val metrics = CameraInferenceMetrics(
                frameCount = frameCount,
                width = image.width,
                height = image.height,
                rotationDegrees = image.imageInfo.rotationDegrees,
                copyMs = copyMs,
                inferenceMs = inferenceMs,
                totalMs = elapsedMs(frameStartNanos, frameEndNanos),
                fps = fps(frameEndNanos),
                trackCount = trackCount,
                modelReady = modelReady,
                inferenceRan = inferenceRan,
                requestedVulkan = requestedVulkan,
                nativeCapabilities = nativeCapabilities,
                modelStatus = modelStatus,
                copyPath = "CameraX ImageProxy planes -> pooled direct ByteBuffer -> JNI",
                lastError = lastError,
            )
            onMetrics(metrics)
            logMetrics(metrics)
            lastFrameEndNanos = frameEndNanos
            image.close()
        }
    }

    override fun close() {
        closed = true
        engine?.close()
    }

    private fun copyYuv420ToDirectBuffer(image: ImageProxy): ByteBuffer {
        val planeBytes = image.planes.sumOf { plane -> plane.buffer.remaining() }
        val yStrideBytes = image.planes.first().rowStride
        val minimumFrameBytes = yStrideBytes * ((image.height * 3 + 1) / 2)
        val requiredBytes = max(planeBytes, minimumFrameBytes)

        if (frameBuffer.capacity() < requiredBytes) {
            frameBuffer = ByteBuffer.allocateDirect(requiredBytes)
        }

        frameBuffer.clear()
        image.planes.forEach { plane ->
            val source = plane.buffer.duplicate()
            frameBuffer.put(source)
        }
        frameBuffer.flip()
        return frameBuffer
    }

    private fun fps(frameEndNanos: Long): Double {
        val previous = lastFrameEndNanos
        if (previous == 0L) {
            return 0.0
        }
        return 1_000_000_000.0 / (frameEndNanos - previous).coerceAtLeast(1L)
    }

    private fun elapsedMs(startNanos: Long, endNanos: Long): Double =
        (endNanos - startNanos) / 1_000_000.0

    private fun logMetrics(metrics: CameraInferenceMetrics) {
        if (metrics.frameCount != 1L && metrics.frameCount % 30L != 0L) {
            return
        }

        Log.i(
            LOG_TAG,
            "frame=${metrics.frameCount} size=${metrics.width}x${metrics.height} " +
                "copyMs=${"%.2f".format(metrics.copyMs)} " +
                "inferenceMs=${"%.2f".format(metrics.inferenceMs)} " +
                "totalMs=${"%.2f".format(metrics.totalMs)} fps=${"%.2f".format(metrics.fps)} " +
                "inferenceRan=${metrics.inferenceRan} tracks=${metrics.trackCount} " +
                "native='${metrics.nativeCapabilities}' model='${metrics.modelStatus}' " +
                "error='${metrics.lastError.orEmpty()}'",
        )
    }
}
