package adiga.shreyas.edgetrident.litevisionengine

import android.content.res.AssetManager
import java.nio.ByteBuffer

internal object NativeBridge {
    @Volatile
    private var loadedLibraryName: String? = null

    fun load(libraryName: String) {
        if (loadedLibraryName == libraryName) {
            return
        }

        synchronized(this) {
            if (loadedLibraryName == libraryName) {
                return
            }

            try {
                System.loadLibrary(libraryName)
                loadedLibraryName = libraryName
            } catch (error: UnsatisfiedLinkError) {
                throw LiteVisionError.LibraryLoadFailed(libraryName, error)
            }
        }
    }

    @JvmStatic
    external fun nativeCreate(
        numThreads: Int,
        preferVulkan: Boolean,
        maxInFlightFrames: Int,
    ): Long

    @JvmStatic
    external fun nativeCapabilities(handle: Long): String

    @JvmStatic
    external fun nativeLoadModel(
        handle: Long,
        assetManager: AssetManager,
        modelId: String,
        paramAssetPath: String,
        binAssetPath: String,
        inputName: String,
        outputNames: Array<String>,
        inputWidth: Int,
        inputHeight: Int,
        preferVulkan: Boolean,
    ): String?

    @JvmStatic
    external fun nativeTrack(
        handle: Long,
        modelId: String,
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        pixelFormat: Int,
        rowStrideBytes: Int,
        timestampNanos: Long,
        rotationDegrees: Int,
    ): FloatArray

    @JvmStatic
    external fun nativeRelease(handle: Long)
}

