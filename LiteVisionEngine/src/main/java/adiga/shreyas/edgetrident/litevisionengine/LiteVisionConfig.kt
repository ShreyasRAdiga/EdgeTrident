package adiga.shreyas.edgetrident.litevisionengine

data class LiteVisionConfig(
    val numThreads: Int = defaultThreadCount(),
    val preferVulkan: Boolean = false,
    val maxInFlightFrames: Int = 1,
    val nativeLibraryName: String = "litevisionengine",
) {
    init {
        if (numThreads < 1) {
            throw LiteVisionError.InvalidConfig("numThreads must be at least 1.")
        }
        if (maxInFlightFrames < 1) {
            throw LiteVisionError.InvalidConfig("maxInFlightFrames must be at least 1.")
        }
        if (nativeLibraryName.isBlank()) {
            throw LiteVisionError.InvalidConfig("nativeLibraryName must not be blank.")
        }
    }

    companion object {
        private fun defaultThreadCount(): Int =
            Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    }
}

