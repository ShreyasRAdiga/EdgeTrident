package adiga.shreyas.edgetrident.vision.pipeline

data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        requireNormalized("left", left)
        requireNormalized("top", top)
        requireNormalized("right", right)
        requireNormalized("bottom", bottom)
        require(left <= right) { "left must be <= right." }
        require(top <= bottom) { "top must be <= bottom." }
    }

    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top
}

data class NormalizedPoint3D(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
) {
    init {
        requireNormalized("x", x)
        requireNormalized("y", y)
        require(z.isFinite()) { "z must be finite." }
    }
}

internal fun requireConfidence(name: String, value: Float) {
    require(value.isFinite()) { "$name must be finite." }
    require(value in 0f..1f) { "$name must be in [0, 1]." }
}

internal fun requireNormalized(name: String, value: Float) {
    require(value.isFinite()) { "$name must be finite." }
    require(value in 0f..1f) { "$name must be normalized to [0, 1]." }
}
