package adiga.shreyas.edgetrident.vision.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import adiga.shreyas.edgetrident.litevisionengine.VisionFrame
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.nio.ByteBuffer

class MediaPipePoseEstimator(
    context: Context,
    modelAssetPath: String = "models/litevision/pose_landmarker_lite.task",
) : AutoCloseable {

    private var poseLandmarker: PoseLandmarker? = null
    val status: String

    init {
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().lowercase()
        if (primaryAbi.contains("x86")) {
            poseLandmarker = null
            status = "disabled on x86/x86_64 emulator (MediaPipe JNI not packaged for this ABI)"
        } else {
            val initialization = runCatching {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(modelAssetPath)
                    .build()
                val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.IMAGE)
                    .build()
                PoseLandmarker.createFromOptions(context, options)
            }
            poseLandmarker = initialization.getOrNull()
            status = initialization.fold(
                onSuccess = { "model loaded" },
                onFailure = { "model not loaded: ${it.message.orEmpty()}" },
            )
        }
    }

    val ready: Boolean
        get() = poseLandmarker != null

    fun estimatePoseCount(frame: VisionFrame): Int {
        val landmarker = poseLandmarker ?: throw IllegalStateException("Pose model is not loaded.")
        if (frame.pixelFormat != VisionFrame.PixelFormat.RGBA_8888) {
            throw IllegalArgumentException("MediaPipe pose lane expects RGBA_8888 frames.")
        }

        val bitmap = frame.toArgbBitmap()
        val image = BitmapImageBuilder(bitmap).build()
        val result = landmarker.detect(image)
        return result.landmarks().size
    }

    override fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
    }

    private fun VisionFrame.toArgbBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val contiguous = ByteBuffer.allocateDirect(width * height * 4)
        val source = buffer.duplicate()
        source.position(0)

        if (rowStrideBytes == width * 4) {
            source.limit(width * height * 4)
            contiguous.put(source)
        } else {
            repeat(height) { row ->
                val rowStart = row * rowStrideBytes
                source.limit(rowStart + width * 4)
                source.position(rowStart)
                contiguous.put(source)
            }
        }

        contiguous.flip()
        bitmap.copyPixelsFromBuffer(contiguous)
        return bitmap
    }
}
