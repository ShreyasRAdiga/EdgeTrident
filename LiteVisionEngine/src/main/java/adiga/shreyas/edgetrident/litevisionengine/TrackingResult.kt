package adiga.shreyas.edgetrident.litevisionengine

data class TrackingResult(
    val trackId: Int,
    val classIndex: Int,
    val confidence: Float,
    val boundingBox: BoundingBox,
    val timestampNanos: Long,
) {
    data class BoundingBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        init {
            if (right < left || bottom < top) {
                throw LiteVisionError.InvalidFrame("Bounding box coordinates are inverted.")
            }
        }
    }

    companion object {
        private const val NATIVE_RESULT_STRIDE = 7

        internal fun fromNativeArray(values: FloatArray, timestampNanos: Long): List<TrackingResult> {
            if (values.isEmpty()) {
                return emptyList()
            }
            if (values.size % NATIVE_RESULT_STRIDE != 0) {
                throw LiteVisionError.NativeFailure(
                    "Native tracking result length ${values.size} is not divisible by $NATIVE_RESULT_STRIDE.",
                )
            }

            return values.asList()
                .chunked(NATIVE_RESULT_STRIDE)
                .map { row ->
                    TrackingResult(
                        trackId = row[0].toInt(),
                        classIndex = row[1].toInt(),
                        confidence = row[2],
                        boundingBox = BoundingBox(
                            left = row[3],
                            top = row[4],
                            right = row[5],
                            bottom = row[6],
                        ),
                        timestampNanos = timestampNanos,
                    )
                }
        }
    }
}

