# LiteVisionEngine NCNN Foundation Plan

## Summary
Create a new Android library module `:LiteVisionEngine` for NCNN-backed tracking, linked through the official prebuilt `ncnn-20260113-android-vulkan.zip` layout. The first implementation will be a buildable foundation with clean Kotlin APIs, JNI/native scaffolding, zero-copy frame contracts where possible, model placeholders, and app-side pipeline interfaces for YOLO 11, MediaPipe Pose, and NCNN tracking.

## Key Changes
- Add `include(":LiteVisionEngine")`, Android library/Kotlin plugin aliases, and `implementation(project(":LiteVisionEngine"))` in `:app`.
- Add `LiteVisionEngine` with:
  - Kotlin facade: `LiteVisionEngine`, `LiteVisionConfig`, `VisionFrame`, `NcnnModelSpec`, `TrackingResult`, `LiteVisionError`.
  - Native bridge using `System.loadLibrary("litevisionengine")`.
  - CMake linking through `find_package(ncnn REQUIRED)` using `LiteVisionEngine/src/main/cpp/ncnn/${ANDROID_ABI}/lib/cmake/ncnn`.
- Use official NCNN Android practices:
  - Load `.param`/`.bin` from Android assets via `AAssetManager`.
  - Configure `ncnn::Net::opt` before load, create a fresh `Extractor` per inference, enable light mode, and use pooled allocators.
  - Prefer CPU path first with Vulkan-ready build hooks; enable Vulkan only when GPU is available and model/runtime support is confirmed.
- Define model placeholders under `assets/models/litevision/`, with clear runtime errors when missing. Do not commit large model binaries.
- Add app-side pipeline contracts:
  - `ObjectDetectorRunner`, `PoseEstimatorRunner`, `ObjectTrackerRunner`.
  - A coordinator that can fan out one `VisionFrame` to YOLO, MediaPipe, and NCNN runner implementations later.
  - YOLO/MediaPipe remain stubs in this first pass; NCNN tracker uses `LiteVisionEngine`.

## Performance Design
- Accept direct `ByteBuffer` frames with width, height, pixel format, row stride, timestamp, and rotation metadata.
- Avoid `Bitmap` as an inference boundary.
- Use zero-copy Java/Kotlin to native handoff for direct buffers; allocate NCNN input only when resize, color conversion, or normalization requires it.
- Keep one native engine handle per runtime instance, use RAII in C++, make release idempotent, and bound frame processing to avoid queue buildup.
- Keep camera lifecycle in `:app`; `LiteVisionEngine` only consumes frames and model specs.

## Test Plan
- Run `.\gradlew.bat :app:compileDebugKotlin :LiteVisionEngine:compileDebugKotlin --console=plain`.
- Run native build after NCNN prebuilt extraction: `.\gradlew.bat :LiteVisionEngine:externalNativeBuildDebug --console=plain`.
- Unit-test config/model validation, missing asset errors, direct-buffer rejection, handle lifecycle, and fake parallel coordinator behavior.
- Add one native smoke path that loads the JNI library and returns engine capability/status without requiring real model binaries.

## Assumptions
- Keep current `minSdk = 35`, `targetSdk = 36`, and Gradle 9.3.1 setup.
- Use latest verified NCNN release as of May 12, 2026: `20260113`, specifically `ncnn-android-vulkan.zip`.
- NCNN package is extracted into the module during implementation; trained YOLO 11, MediaPipe, and tracker models are not invented or downloaded.
- Research basis: Tencent NCNN README/features, Android build and `find_package` docs, model loading docs, allocator guidance, Vulkan notes, and source APIs for `Net`, `Extractor`, and `Mat`.
