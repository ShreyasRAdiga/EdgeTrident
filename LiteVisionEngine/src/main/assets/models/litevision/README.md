# LiteVision Model Assets

Place NCNN model files for the LiteVision tracker in this folder.

The default Kotlin spec expects:

```text
models/litevision/object_tracker.param
models/litevision/object_tracker.bin
```

The checked-in `.placeholder` files are intentionally not loadable by NCNN. If the real `.param` or `.bin` asset is missing, `LiteVisionEngine.loadModel()` throws `LiteVisionError.ModelAssetMissing` before entering native code.

Do not commit large model binaries.

