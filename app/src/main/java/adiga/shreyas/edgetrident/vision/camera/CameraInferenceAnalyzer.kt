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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class CameraInferenceAnalyzer(
    context: Context,
    private val requestedVulkan: Boolean,
    private val onMetrics: (CameraInferenceMetrics) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private companion object {
        private const val LOG_TAG = "EdgeTridentInference"
    }

    private data class NcnnLane(
        val lane: String,
        val modelId: String,
        val engine: LiteVisionEngine?,
        val ready: Boolean,
        val status: String,
    )

    private val appContext = context.applicationContext
    private val laneExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val initLock = Any()

    @Volatile
    private var initialized = false
    private var nativeCapabilities = "native engine not opened yet"
    private var modelStatus = "initializing"

    private var yoloLane: NcnnLane? = null
    private var poseLane: MediaPipePoseEstimator? = null

    private var frameBuffer = ByteBuffer.allocateDirect(0)
    private var frameCount = 0L
    private var lastFrameEndNanos = 0L
    private var closed = false

    override fun analyze(image: ImageProxy) {
        if (closed) {
            image.close()
            return
        }

        val frameStartNanos = System.nanoTime()
        var lastError: String? = null
        var copyMs = 0.0
        var inferenceMs = 0.0
        var yoloMetrics = InferenceLaneMetrics("YOLOv7", false, false, 0.0, 0, "not initialized")
        var poseMetrics = InferenceLaneMetrics("MediaPipe Pose", false, false, 0.0, 0, "not initialized")

        try {
            ensureInitialized()

            val copyStartNanos = System.nanoTime()
            val directBuffer = copyRgba8888ToDirectBuffer(image)
            copyMs = elapsedMs(copyStartNanos, System.nanoTime())

            val frame = VisionFrame(
                buffer = directBuffer,
                width = image.width,
                height = image.height,
                pixelFormat = VisionFrame.PixelFormat.RGBA_8888,
                rowStrideBytes = image.width * 4,
                timestampNanos = image.imageInfo.timestamp,
                rotationDegrees = image.imageInfo.rotationDegrees,
            )

            val inferenceStartNanos = System.nanoTime()
            val yoloFuture = laneExecutor.submit<InferenceLaneMetrics> { runNcnnLane(yoloLane, frame) }
            val poseFuture = laneExecutor.submit<InferenceLaneMetrics> { runPoseLane(poseLane, frame) }

            yoloMetrics = yoloFuture.get()
            poseMetrics = poseFuture.get()
            inferenceMs = elapsedMs(inferenceStartNanos, System.nanoTime())
        } catch (throwable: Throwable) {
            lastError = throwable.message ?: throwable::class.java.simpleName
        } finally {
            val frameEndNanos = System.nanoTime()
            frameCount += 1
            val inferenceRan = yoloMetrics.ran || poseMetrics.ran
            val modelReady = yoloMetrics.ready || poseMetrics.ready
            val metrics = CameraInferenceMetrics(
                frameCount = frameCount,
                width = image.width,
                height = image.height,
                rotationDegrees = image.imageInfo.rotationDegrees,
                copyMs = copyMs,
                inferenceMs = inferenceMs,
                totalMs = elapsedMs(frameStartNanos, frameEndNanos),
                fps = fps(frameEndNanos),
                modelReady = modelReady,
                inferenceRan = inferenceRan,
                requestedVulkan = requestedVulkan,
                nativeCapabilities = nativeCapabilities,
                modelStatus = modelStatus,
                copyPath = "CameraX RGBA_8888 -> packed direct ByteBuffer -> JNI",
                yoloLane = yoloMetrics,
                poseLane = poseMetrics,
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
        yoloLane?.engine?.close()
        poseLane?.close()
        laneExecutor.shutdown()
    }

    private fun ensureInitialized() {
        if (initialized) {
            return
        }
        synchronized(initLock) {
            if (initialized) {
                return
            }

            val yoloSpec = NcnnModelSpec.yoloV7(preferVulkan = requestedVulkan)
            yoloLane = createNcnnLane("YOLOv7", yoloSpec)
            poseLane = MediaPipePoseEstimator(appContext)

            nativeCapabilities = yoloLane?.engine?.let { engine ->
                runCatching { engine.capabilities }.getOrElse { "capability query failed: ${it.message.orEmpty()}" }
            } ?: "native engine unavailable"

            modelStatus = buildString {
                append("yolo=${yoloLane?.status.orEmpty()}; ")
                append("pose=${poseLane?.status.orEmpty()}")
            }

            initialized = true
        }
    }

    private fun createNcnnLane(
        lane: String,
        spec: NcnnModelSpec,
    ): NcnnLane {
        val result = runCatching {
            val engine = LiteVisionEngine(
                context = appContext,
                config = LiteVisionConfig(preferVulkan = requestedVulkan),
            )
            engine.loadModel(spec)
            engine
        }
        val engine = result.getOrNull()
        return if (engine != null) {
            NcnnLane(
                lane = lane,
                modelId = spec.id,
                engine = engine,
                ready = true,
                status = "model loaded",
            )
        } else {
            NcnnLane(
                lane = lane,
                modelId = spec.id,
                engine = null,
                ready = false,
                status = "model not loaded: ${result.exceptionOrNull()?.message.orEmpty()}",
            )
        }
    }

    private fun runNcnnLane(
        lane: NcnnLane?,
        frame: VisionFrame,
    ): InferenceLaneMetrics {
        if (lane == null) {
            return InferenceLaneMetrics("unknown", false, false, 0.0, 0, "lane not initialized")
        }
        if (!lane.ready || lane.engine == null) {
            return InferenceLaneMetrics(lane.lane, false, false, 0.0, 0, lane.status)
        }

        val start = System.nanoTime()
        return runCatching {
            val results = lane.engine.track(frame, lane.modelId)
            InferenceLaneMetrics(
                lane = lane.lane,
                ready = true,
                ran = true,
                durationMs = elapsedMs(start, System.nanoTime()),
                resultCount = results.size,
                status = lane.status,
            )
        }.getOrElse { error ->
            InferenceLaneMetrics(
                lane = lane.lane,
                ready = true,
                ran = false,
                durationMs = elapsedMs(start, System.nanoTime()),
                resultCount = 0,
                status = lane.status,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun runPoseLane(
        poseEstimator: MediaPipePoseEstimator?,
        frame: VisionFrame,
    ): InferenceLaneMetrics {
        if (poseEstimator == null) {
            return InferenceLaneMetrics("MediaPipe Pose", false, false, 0.0, 0, "lane not initialized")
        }
        if (!poseEstimator.ready) {
            return InferenceLaneMetrics("MediaPipe Pose", false, false, 0.0, 0, poseEstimator.status)
        }

        val start = System.nanoTime()
        return runCatching {
            val poseCount = poseEstimator.estimatePoseCount(frame)
            InferenceLaneMetrics(
                lane = "MediaPipe Pose",
                ready = true,
                ran = true,
                durationMs = elapsedMs(start, System.nanoTime()),
                resultCount = poseCount,
                status = poseEstimator.status,
            )
        }.getOrElse { error ->
            InferenceLaneMetrics(
                lane = "MediaPipe Pose",
                ready = true,
                ran = false,
                durationMs = elapsedMs(start, System.nanoTime()),
                resultCount = 0,
                status = poseEstimator.status,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }

    private fun copyRgba8888ToDirectBuffer(image: ImageProxy): ByteBuffer {
        val plane = image.planes.first()
        val rowStride = plane.rowStride
        val contiguousRowBytes = image.width * 4
        val requiredBytes = contiguousRowBytes * image.height

        if (frameBuffer.capacity() < requiredBytes) {
            frameBuffer = ByteBuffer.allocateDirect(requiredBytes)
        }

        frameBuffer.clear()
        val source = plane.buffer.duplicate()
        source.position(0)
        if (rowStride == contiguousRowBytes) {
            source.limit(requiredBytes)
            frameBuffer.put(source)
        } else {
            repeat(image.height) { row ->
                val rowStart = row * rowStride
                source.limit(rowStart + contiguousRowBytes)
                source.position(rowStart)
                frameBuffer.put(source)
            }
        }
        frameBuffer.flip()
        return frameBuffer
    }

    private fun fps(frameEndNanos: Long): Double {
        val previous = lastFrameEndNanos
        if (previous == 0L) {
            return 0.0
        }
        return 1_000_000_000.0 / max(1L, frameEndNanos - previous)
    }

    private fun elapsedMs(startNanos: Long, endNanos: Long): Double =
        (endNanos - startNanos) / 1_000_000.0

    private fun logMetrics(metrics: CameraInferenceMetrics) {
        if (metrics.frameCount != 1L && metrics.frameCount % 30L != 0L) {
            return
        }

        Log.i(
            LOG_TAG,
            "frame=${metrics.frameCount} copyMs=${format(metrics.copyMs)} " +
                "inferMs=${format(metrics.inferenceMs)} fps=${format(metrics.fps)} " +
                "yolo=${format(metrics.yoloLane.durationMs)}ms/${metrics.yoloLane.ran} " +
                "pose=${format(metrics.poseLane.durationMs)}ms/${metrics.poseLane.ran} " +
                "native='${metrics.nativeCapabilities}' lastError='${metrics.lastError.orEmpty()}'",
        )
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)
}
