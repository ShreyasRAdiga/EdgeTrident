package adiga.shreyas.edgetrident.litevisionengine

import java.nio.ByteBuffer

data class VisionFrame(
    val buffer: ByteBuffer,
    val width: Int,
    val height: Int,
    val pixelFormat: PixelFormat,
    val rowStrideBytes: Int,
    val timestampNanos: Long,
    val rotationDegrees: Int = 0,
) {
    init {
        if (width < 1 || height < 1) {
            throw LiteVisionError.InvalidFrame("Frame dimensions must be positive.")
        }
        if (rowStrideBytes < width * pixelFormat.minimumBytesPerPixel) {
            throw LiteVisionError.InvalidFrame("rowStrideBytes is too small for $pixelFormat.")
        }
        if (rotationDegrees !in VALID_ROTATIONS) {
            throw LiteVisionError.InvalidFrame("rotationDegrees must be one of $VALID_ROTATIONS.")
        }
        requireNativeCompatible()
    }

    internal fun requireNativeCompatible() {
        if (!buffer.isDirect) {
            throw LiteVisionError.InvalidFrame("VisionFrame requires a direct ByteBuffer for JNI handoff.")
        }
        val requiredCapacity = minimumCapacityBytes()
        if (buffer.capacity().toLong() < requiredCapacity) {
            throw LiteVisionError.InvalidFrame(
                "Frame buffer capacity ${buffer.capacity()} is smaller than required $requiredCapacity bytes.",
            )
        }
    }

    private fun minimumCapacityBytes(): Long {
        val rowsNumerator = height.toLong() * pixelFormat.bufferRowsNumerator
        val rows = (rowsNumerator + pixelFormat.bufferRowsDenominator - 1) / pixelFormat.bufferRowsDenominator
        return rowStrideBytes.toLong() * rows
    }

    enum class PixelFormat(
        internal val nativeCode: Int,
        internal val minimumBytesPerPixel: Int,
        internal val bufferRowsNumerator: Int,
        internal val bufferRowsDenominator: Int,
    ) {
        RGBA_8888(
            nativeCode = 1,
            minimumBytesPerPixel = 4,
            bufferRowsNumerator = 1,
            bufferRowsDenominator = 1,
        ),
        RGB_888(
            nativeCode = 2,
            minimumBytesPerPixel = 3,
            bufferRowsNumerator = 1,
            bufferRowsDenominator = 1,
        ),
        NV21(
            nativeCode = 3,
            minimumBytesPerPixel = 1,
            bufferRowsNumerator = 3,
            bufferRowsDenominator = 2,
        ),
        YUV_420_888(
            nativeCode = 4,
            minimumBytesPerPixel = 1,
            bufferRowsNumerator = 3,
            bufferRowsDenominator = 2,
        ),
    }

    companion object {
        private val VALID_ROTATIONS = setOf(0, 90, 180, 270)
    }
}
