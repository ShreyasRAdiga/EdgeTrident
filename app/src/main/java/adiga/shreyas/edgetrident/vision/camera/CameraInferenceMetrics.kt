package adiga.shreyas.edgetrident.vision.camera

data class CameraInferenceMetrics(
    val frameCount: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val copyMs: Double,
    val inferenceMs: Double,
    val totalMs: Double,
    val fps: Double,
    val trackCount: Int,
    val modelReady: Boolean,
    val inferenceRan: Boolean,
    val requestedVulkan: Boolean,
    val nativeCapabilities: String,
    val modelStatus: String,
    val copyPath: String,
    val lastError: String? = null,
) {
    companion object {
        fun idle(requestedVulkan: Boolean = true) = CameraInferenceMetrics(
            frameCount = 0L,
            width = 0,
            height = 0,
            rotationDegrees = 0,
            copyMs = 0.0,
            inferenceMs = 0.0,
            totalMs = 0.0,
            fps = 0.0,
            trackCount = 0,
            modelReady = false,
            inferenceRan = false,
            requestedVulkan = requestedVulkan,
            nativeCapabilities = "native engine not opened yet",
            modelStatus = "waiting for camera permission",
            copyPath = "CameraX YUV_420_888 -> direct ByteBuffer",
        )
    }
}
