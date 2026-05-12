package adiga.shreyas.edgetrident.litevisionengine

import android.content.Context
import android.content.res.AssetManager
import java.io.Closeable
import java.io.FileNotFoundException
import java.io.IOException

class LiteVisionEngine(
    context: Context,
    private val config: LiteVisionConfig = LiteVisionConfig(),
) : Closeable {
    private val assetManager: AssetManager = context.applicationContext.assets
    private val loadedModelIds = mutableSetOf<String>()
    private var nativeHandle: Long = 0L

    init {
        NativeBridge.load(config.nativeLibraryName)
        nativeHandle = NativeBridge.nativeCreate(
            numThreads = config.numThreads,
            preferVulkan = config.preferVulkan,
            maxInFlightFrames = config.maxInFlightFrames,
        )
        if (nativeHandle == 0L) {
            throw LiteVisionError.NativeFailure("Native engine creation failed.")
        }
    }

    val capabilities: String
        @Synchronized get() = NativeBridge.nativeCapabilities(requireOpenHandle())

    @Synchronized
    fun loadModel(spec: NcnnModelSpec = NcnnModelSpec.objectTracker()): LiteVisionEngine {
        requireAssetPresent(spec.paramAssetPath)
        requireAssetPresent(spec.binAssetPath)

        val error = NativeBridge.nativeLoadModel(
            handle = requireOpenHandle(),
            assetManager = assetManager,
            modelId = spec.id,
            paramAssetPath = spec.paramAssetPath,
            binAssetPath = spec.binAssetPath,
            inputName = spec.inputName,
            outputNames = spec.outputNames.toTypedArray(),
            inputWidth = spec.inputWidth,
            inputHeight = spec.inputHeight,
            preferVulkan = spec.preferVulkan ?: config.preferVulkan,
        )

        if (error != null) {
            throw LiteVisionError.ModelLoadFailed(spec.id, error)
        }

        loadedModelIds += spec.id
        return this
    }

    @Synchronized
    fun track(
        frame: VisionFrame,
        modelId: String = NcnnModelSpec.DEFAULT_OBJECT_TRACKER_ID,
    ): List<TrackingResult> {
        val handle = requireOpenHandle()
        frame.requireNativeCompatible()

        if (modelId !in loadedModelIds) {
            throw LiteVisionError.ModelNotLoaded(modelId)
        }

        val packedResults = NativeBridge.nativeTrack(
            handle = handle,
            modelId = modelId,
            buffer = frame.buffer,
            width = frame.width,
            height = frame.height,
            pixelFormat = frame.pixelFormat.nativeCode,
            rowStrideBytes = frame.rowStrideBytes,
            timestampNanos = frame.timestampNanos,
            rotationDegrees = frame.rotationDegrees,
        )

        return TrackingResult.fromNativeArray(packedResults, frame.timestampNanos)
    }

    @Synchronized
    override fun close() {
        val handle = nativeHandle
        if (handle != 0L) {
            NativeBridge.nativeRelease(handle)
            nativeHandle = 0L
            loadedModelIds.clear()
        }
    }

    private fun requireOpenHandle(): Long {
        val handle = nativeHandle
        if (handle == 0L) {
            throw LiteVisionError.EngineClosed()
        }
        return handle
    }

    private fun requireAssetPresent(assetPath: String) {
        if (assetManager.assetExists(assetPath)) {
            return
        }

        val placeholderPath = "$assetPath.placeholder"
        val message = if (assetManager.assetExists(placeholderPath)) {
            "Expected real NCNN asset '$assetPath' but only placeholder '$placeholderPath' is present."
        } else {
            "Missing NCNN asset '$assetPath'. Place the extracted model file under assets/models/litevision/."
        }
        throw LiteVisionError.ModelAssetMissing(assetPath, message)
    }

    private fun AssetManager.assetExists(path: String): Boolean =
        try {
            open(path).use { true }
        } catch (_: FileNotFoundException) {
            false
        } catch (_: IOException) {
            false
        }
}

