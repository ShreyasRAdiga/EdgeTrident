# NCNN Android Package

The module can prepare the official Android NCNN Vulkan package automatically:

```powershell
.\gradlew.bat :LiteVisionEngine:prepareLiteVisionNcnn --console=plain
```

Model assets used by the camera probe are prepared automatically as part of
`preBuild`, or on demand:

```powershell
.\gradlew.bat :LiteVisionEngine:prepareLiteVisionModels --console=plain
```

By default, native CMake tasks also depend on that preparation step, so
`:app:assembleDebug` downloads and extracts NCNN once if it is missing. To keep
the fallback/no-download build, pass:

```powershell
.\gradlew.bat :app:assembleDebug -PliteVision.autoPrepareNcnn=false --console=plain
```

The default package is Tencent/ncnn `20260113`,
`ncnn-20260113-android-vulkan.zip`, verified with SHA-256 before extraction.
Override with `-PliteVision.ncnnVersion=...`, `-PliteVision.ncnnUrl=...`, or
`-PliteVision.ncnnSha256=...` if needed.

The prepared layout must expose:

```text
LiteVisionEngine/src/main/cpp/ncnn/<ANDROID_ABI>/lib/cmake/ncnn/ncnnConfig.cmake
```

The CMake scaffold sets `ncnn_DIR` to this ABI-specific package path before calling `find_package(ncnn REQUIRED)`.

Expected layout after extraction:

```text
src/main/cpp/ncnn/
  arm64-v8a/
    include/
    lib/
      cmake/ncnn/
  armeabi-v7a/
  x86/
  x86_64/
```

Do not commit extracted NCNN binaries.
