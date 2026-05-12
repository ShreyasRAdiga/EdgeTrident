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
