# LiteVision Model Assets

This folder stores runtime model assets consumed by camera inference probes.

By default, `:LiteVisionEngine:prepareLiteVisionModels` downloads:

```text
models/litevision/yolov7.param
models/litevision/yolov7.bin
models/litevision/pose_landmarker_lite.task
```

Optional TrackNet NCNN assets can also be downloaded by providing:

```text
-PliteVision.tracknetParamUrl=<public .param URL>
-PliteVision.tracknetBinUrl=<public .bin URL>
```

If a required `.param` or `.bin` asset is missing, `LiteVisionEngine.loadModel()` throws `LiteVisionError.ModelAssetMissing` before entering native code.

Do not commit large model binaries.
