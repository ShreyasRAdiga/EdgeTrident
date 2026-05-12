import java.net.URI
import java.security.MessageDigest
import java.util.Locale

plugins {
    alias(libs.plugins.android.library)
}

val liteVisionNcnnVersion = providers.gradleProperty("liteVision.ncnnVersion").getOrElse("20260113")
val liteVisionNcnnArchiveName = "ncnn-$liteVisionNcnnVersion-android-vulkan.zip"
val liteVisionNcnnUrl = providers.gradleProperty("liteVision.ncnnUrl")
    .getOrElse("https://github.com/Tencent/ncnn/releases/download/$liteVisionNcnnVersion/$liteVisionNcnnArchiveName")
val liteVisionNcnnSha256 = providers.gradleProperty("liteVision.ncnnSha256")
    .getOrElse("41944630a7b305b190e7caea4b405bc6d103c6383dc4988e9e85616058e3cccd")
val liteVisionNcnnAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
val liteVisionNcnnRoot = layout.projectDirectory.dir("src/main/cpp/ncnn")
val liteVisionNcnnArchive = layout.buildDirectory.file("downloads/ncnn/$liteVisionNcnnArchiveName")
val liteVisionModelRoot = layout.projectDirectory.dir("src/main/assets/models/litevision")
val liteVisionYoloV7ParamUrl = providers.gradleProperty("liteVision.yolov7ParamUrl")
    .getOrElse("https://raw.githubusercontent.com/xiang-wuu/ncnn-android-yolov7/master/app/src/main/assets/yolov7-tiny.param")
val liteVisionYoloV7ParamFallbackUrl = providers.gradleProperty("liteVision.yolov7ParamFallbackUrl")
    .getOrElse("https://raw.githubusercontent.com/xiang-wuu/ncnn-android-yolov7/main/app/src/main/assets/yolov7-tiny.param")
val liteVisionYoloV7BinUrl = providers.gradleProperty("liteVision.yolov7BinUrl")
    .getOrElse("https://raw.githubusercontent.com/xiang-wuu/ncnn-android-yolov7/master/app/src/main/assets/yolov7-tiny.bin")
val liteVisionYoloV7BinFallbackUrl = providers.gradleProperty("liteVision.yolov7BinFallbackUrl")
    .getOrElse("https://raw.githubusercontent.com/xiang-wuu/ncnn-android-yolov7/main/app/src/main/assets/yolov7-tiny.bin")
val liteVisionPoseTaskUrl = providers.gradleProperty("liteVision.poseTaskUrl")
    .getOrElse("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task")
val liteVisionTrackNetParamUrl = providers.gradleProperty("liteVision.tracknetParamUrl").getOrElse("")
val liteVisionTrackNetBinUrl = providers.gradleProperty("liteVision.tracknetBinUrl").getOrElse("")

fun File.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte ->
        "%02x".format(Locale.US, byte)
    }
}

fun ncnnConfigExists(abi: String): Boolean {
    val abiDir = liteVisionNcnnRoot.dir(abi).asFile
    return abiDir.resolve("lib/cmake/ncnn/ncnnConfig.cmake").isFile ||
        abiDir.resolve("lib/cmake/ncnn/ncnn-config.cmake").isFile
}

fun liteVisionNcnnReady(): Boolean = liteVisionNcnnAbis.all(::ncnnConfigExists)

val downloadLiteVisionNcnn = tasks.register("downloadLiteVisionNcnn") {
    group = "litevision"
    description = "Downloads the official NCNN Android Vulkan package used by LiteVisionEngine."
    outputs.file(liteVisionNcnnArchive)
    outputs.upToDateWhen {
        val archive = liteVisionNcnnArchive.get().asFile
        archive.isFile && archive.sha256Hex().equals(liteVisionNcnnSha256, ignoreCase = true)
    }
    onlyIf {
        !liteVisionNcnnReady()
    }

    doLast {
        val archive = liteVisionNcnnArchive.get().asFile
        if (archive.isFile && archive.sha256Hex().equals(liteVisionNcnnSha256, ignoreCase = true)) {
            logger.lifecycle("NCNN archive already downloaded: ${archive.absolutePath}")
            return@doLast
        }

        archive.parentFile.mkdirs()
        val temporaryArchive = archive.resolveSibling("${archive.name}.part")
        temporaryArchive.delete()

        logger.lifecycle("Downloading NCNN $liteVisionNcnnVersion from $liteVisionNcnnUrl")
        URI(liteVisionNcnnUrl).toURL().openStream().use { input ->
            temporaryArchive.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val actualSha256 = temporaryArchive.sha256Hex()
        if (!actualSha256.equals(liteVisionNcnnSha256, ignoreCase = true)) {
            temporaryArchive.delete()
            throw GradleException(
                "NCNN archive checksum mismatch. Expected $liteVisionNcnnSha256 but got $actualSha256.",
            )
        }

        if (archive.exists()) {
            archive.delete()
        }
        if (!temporaryArchive.renameTo(archive)) {
            temporaryArchive.copyTo(archive, overwrite = true)
            temporaryArchive.delete()
        }
    }
}

val prepareLiteVisionNcnn = tasks.register("prepareLiteVisionNcnn") {
    group = "litevision"
    description = "Downloads and extracts NCNN Android Vulkan prebuilts into src/main/cpp/ncnn."
    dependsOn(downloadLiteVisionNcnn)
    outputs.dir(liteVisionNcnnRoot)
    outputs.upToDateWhen {
        liteVisionNcnnReady()
    }

    doLast {
        if (liteVisionNcnnReady()) {
            logger.lifecycle("NCNN Android package is already prepared under ${liteVisionNcnnRoot.asFile.absolutePath}")
            return@doLast
        }

        val archive = liteVisionNcnnArchive.get().asFile
        if (!archive.isFile) {
            throw GradleException("NCNN archive is missing. Run :LiteVisionEngine:downloadLiteVisionNcnn first.")
        }

        val expandedDir = layout.buildDirectory.dir("tmp/litevision-ncnn-expanded/$liteVisionNcnnVersion").get().asFile
        delete(expandedDir)
        copy {
            from(zipTree(archive))
            into(expandedDir)
        }

        val configFiles = fileTree(expandedDir) {
            include("**/lib/cmake/ncnn/ncnnConfig.cmake")
            include("**/lib/cmake/ncnn/ncnn-config.cmake")
        }.files

        if (configFiles.isEmpty()) {
            throw GradleException("NCNN archive did not contain lib/cmake/ncnn/ncnnConfig.cmake for any ABI.")
        }

        configFiles.forEach { configFile ->
            val abiDir = configFile.parentFile.parentFile.parentFile.parentFile
            if (abiDir.name in liteVisionNcnnAbis) {
                val targetAbiDir = liteVisionNcnnRoot.dir(abiDir.name).asFile
                delete(targetAbiDir)
                copy {
                    from(abiDir)
                    into(targetAbiDir)
                }
                logger.lifecycle("Prepared NCNN ABI ${abiDir.name} at ${targetAbiDir.absolutePath}")
            }
        }

        // AGP may have configured CMake once in fallback mode before NCNN was
        // extracted. Drop only generated native caches so the next CMake pass
        // sees the newly prepared package configs.
        delete(layout.projectDirectory.dir(".cxx"))
        delete(layout.buildDirectory.dir("intermediates/cxx"))

        if (!liteVisionNcnnReady()) {
            throw GradleException(
                "NCNN extraction did not prepare all required ABIs: ${liteVisionNcnnAbis.joinToString()}.",
            )
        }
    }
}

tasks.register("verifyLiteVisionNcnn") {
    group = "litevision"
    description = "Verifies that all LiteVisionEngine NCNN ABI package configs are present."
    doLast {
        val missingAbis = liteVisionNcnnAbis.filterNot(::ncnnConfigExists)
        if (missingAbis.isNotEmpty()) {
            throw GradleException("Missing NCNN package config for ABI(s): ${missingAbis.joinToString()}")
        }
        logger.lifecycle("NCNN is ready for ${liteVisionNcnnAbis.joinToString()} under ${liteVisionNcnnRoot.asFile.absolutePath}")
    }
}

val autoPrepareLiteVisionNcnn = providers.gradleProperty("liteVision.autoPrepareNcnn")
    .map(String::toBoolean)
    .getOrElse(true)

if (autoPrepareLiteVisionNcnn) {
    tasks.configureEach {
        if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
            dependsOn(prepareLiteVisionNcnn)
            inputs.dir(liteVisionNcnnRoot)
        }
    }
}

fun downloadModelAsset(urlCandidates: List<String>, destination: File, label: String) {
    if (destination.isFile && destination.length() > 0L) {
        return
    }

    destination.parentFile.mkdirs()
    var lastFailure: Throwable? = null

    for (url in urlCandidates.distinct()) {
        val temporaryFile = destination.resolveSibling("${destination.name}.part")
        temporaryFile.delete()
        try {
            logger.lifecycle("Downloading $label from $url")
            URI(url).toURL().openStream().use { input ->
                temporaryFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (destination.exists()) {
                destination.delete()
            }
            if (!temporaryFile.renameTo(destination)) {
                temporaryFile.copyTo(destination, overwrite = true)
                temporaryFile.delete()
            }
            return
        } catch (error: Throwable) {
            temporaryFile.delete()
            lastFailure = error
        }
    }

    throw GradleException("Failed to download $label from any configured URL.", lastFailure)
}

tasks.register("prepareLiteVisionModels") {
    group = "litevision"
    description = "Downloads runtime model assets used by LiteVisionEngine probes."
    outputs.dir(liteVisionModelRoot)
    inputs.property("liteVisionYoloV7ParamUrl", liteVisionYoloV7ParamUrl)
    inputs.property("liteVisionYoloV7ParamFallbackUrl", liteVisionYoloV7ParamFallbackUrl)
    inputs.property("liteVisionYoloV7BinUrl", liteVisionYoloV7BinUrl)
    inputs.property("liteVisionYoloV7BinFallbackUrl", liteVisionYoloV7BinFallbackUrl)
    inputs.property("liteVisionPoseTaskUrl", liteVisionPoseTaskUrl)
    inputs.property("liteVisionTrackNetParamUrl", liteVisionTrackNetParamUrl)
    inputs.property("liteVisionTrackNetBinUrl", liteVisionTrackNetBinUrl)

    doLast {
        val modelRoot = liteVisionModelRoot.asFile
        downloadModelAsset(
            urlCandidates = listOf(liteVisionYoloV7ParamUrl, liteVisionYoloV7ParamFallbackUrl),
            destination = modelRoot.resolve("yolov7.param"),
            label = "YOLOv7 param",
        )
        downloadModelAsset(
            urlCandidates = listOf(liteVisionYoloV7BinUrl, liteVisionYoloV7BinFallbackUrl),
            destination = modelRoot.resolve("yolov7.bin"),
            label = "YOLOv7 bin",
        )
        downloadModelAsset(
            urlCandidates = listOf(liteVisionPoseTaskUrl),
            destination = modelRoot.resolve("pose_landmarker_lite.task"),
            label = "MediaPipe Pose task",
        )

        if (liteVisionTrackNetParamUrl.isNotBlank() && liteVisionTrackNetBinUrl.isNotBlank()) {
            downloadModelAsset(
                urlCandidates = listOf(liteVisionTrackNetParamUrl),
                destination = modelRoot.resolve("tracknet.param"),
                label = "TrackNet param",
            )
            downloadModelAsset(
                urlCandidates = listOf(liteVisionTrackNetBinUrl),
                destination = modelRoot.resolve("tracknet.bin"),
                label = "TrackNet bin",
            )
        } else {
            logger.lifecycle(
                "TrackNet download skipped. Set -PliteVision.tracknetParamUrl and -PliteVision.tracknetBinUrl to enable.",
            )
        }
    }
}

tasks.named("preBuild") {
    dependsOn("prepareLiteVisionModels")
}

android {
    namespace = "adiga.shreyas.edgetrident.litevisionengine"
    ndkVersion = "27.0.12077973"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 35
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation(libs.junit)
}
