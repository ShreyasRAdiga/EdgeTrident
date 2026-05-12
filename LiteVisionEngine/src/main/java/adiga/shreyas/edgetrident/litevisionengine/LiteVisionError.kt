package adiga.shreyas.edgetrident.litevisionengine

sealed class LiteVisionError(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    class InvalidConfig(message: String) : LiteVisionError(message)

    class LibraryLoadFailed(libraryName: String, cause: Throwable) :
        LiteVisionError("Failed to load native library '$libraryName'.", cause)

    class NativeFailure(message: String) : LiteVisionError(message)

    class ModelAssetMissing(assetPath: String, detail: String) :
        LiteVisionError("$detail Asset path: $assetPath")

    class ModelLoadFailed(modelId: String, detail: String) :
        LiteVisionError("Failed to load NCNN model '$modelId': $detail")

    class ModelNotLoaded(modelId: String) :
        LiteVisionError("NCNN model '$modelId' has not been loaded.")

    class InvalidFrame(message: String) : LiteVisionError(message)

    class EngineClosed : LiteVisionError("LiteVisionEngine has already been closed.")
}

