package adiga.shreyas.edgetrident.litevisionengine

data class NcnnModelSpec(
    val id: String,
    val paramAssetPath: String,
    val binAssetPath: String,
    val inputName: String,
    val outputNames: List<String>,
    val inputWidth: Int,
    val inputHeight: Int,
    val preferVulkan: Boolean? = null,
) {
    init {
        if (id.isBlank()) {
            throw LiteVisionError.InvalidConfig("Model id must not be blank.")
        }
        if (paramAssetPath.isBlank() || paramAssetPath.startsWith("/")) {
            throw LiteVisionError.InvalidConfig("paramAssetPath must be a relative asset path.")
        }
        if (binAssetPath.isBlank() || binAssetPath.startsWith("/")) {
            throw LiteVisionError.InvalidConfig("binAssetPath must be a relative asset path.")
        }
        if (inputName.isBlank()) {
            throw LiteVisionError.InvalidConfig("inputName must not be blank.")
        }
        if (outputNames.isEmpty() || outputNames.any { it.isBlank() }) {
            throw LiteVisionError.InvalidConfig("outputNames must contain non-blank names.")
        }
        if (inputWidth < 1 || inputHeight < 1) {
            throw LiteVisionError.InvalidConfig("Model input dimensions must be positive.")
        }
    }

    companion object {
        const val DEFAULT_OBJECT_TRACKER_ID = "litevision_object_tracker"

        fun objectTracker(
            id: String = DEFAULT_OBJECT_TRACKER_ID,
            inputName: String = "images",
            outputNames: List<String> = listOf("output0"),
            inputWidth: Int = 640,
            inputHeight: Int = 640,
            preferVulkan: Boolean? = null,
        ): NcnnModelSpec = NcnnModelSpec(
            id = id,
            paramAssetPath = "models/litevision/object_tracker.param",
            binAssetPath = "models/litevision/object_tracker.bin",
            inputName = inputName,
            outputNames = outputNames,
            inputWidth = inputWidth,
            inputHeight = inputHeight,
            preferVulkan = preferVulkan,
        )
    }
}

