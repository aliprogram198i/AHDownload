import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ahdownload.app"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.ahdownload.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 111
        versionName = "1.4.89"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    val signingFile = providers.gradleProperty("AH_KEYSTORE_FILE")
        .orElse(providers.environmentVariable("AH_KEYSTORE_FILE")).orNull
    val signingStorePassword = providers.gradleProperty("AH_KEYSTORE_PASSWORD")
        .orElse(providers.environmentVariable("AH_KEYSTORE_PASSWORD")).orNull
    val signingAlias = providers.gradleProperty("AH_KEY_ALIAS")
        .orElse(providers.environmentVariable("AH_KEY_ALIAS")).orNull
    val signingKeyPassword = providers.gradleProperty("AH_KEY_PASSWORD")
        .orElse(providers.environmentVariable("AH_KEY_PASSWORD")).orNull

    val hasReleaseSigning =
        !signingFile.isNullOrBlank() &&
        File(signingFile!!).exists() &&
        !signingStorePassword.isNullOrBlank() &&
        !signingAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()

    if (hasReleaseSigning) {
        signingConfigs {
            create("releaseOfficial") {
                storeFile = File(signingFile!!)
                storePassword = signingStorePassword
                keyAlias = signingAlias
                keyPassword = signingKeyPassword
                storeType = "PKCS12"
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("releaseOfficial")
            } else if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
                val filePresent = !signingFile.isNullOrBlank()
                val fileExists = signingFile?.let { File(it).exists() } == true
                val storePasswordPresent = !signingStorePassword.isNullOrBlank()
                val aliasPresent = !signingAlias.isNullOrBlank()
                val keyPasswordPresent = !signingKeyPassword.isNullOrBlank()
                error("Official release signing unavailable: filePresent=$filePresent fileExists=$fileExists storePasswordPresent=$storePasswordPresent aliasPresent=$aliasPresent keyPasswordPresent=$keyPasswordPresent")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

/**
 * Select the custom LGPL Full + GnuTLS runtime only when CI explicitly requires it.
 * The AAR URL is version-pinned, and its SHA-256 must be supplied by the caller from
 * a reviewed, version-controlled pin. Missing or mismatched artifacts fail closed.
 */
val useVerifiedFfmpegRuntimeProperty = providers.gradleProperty("ahdownloadUseVerifiedFfmpegRuntime")
val useVerifiedFfmpegRuntime = useVerifiedFfmpegRuntimeProperty.orNull?.let { value ->
    require(value == "true" || value == "false") {
        "ahdownloadUseVerifiedFfmpegRuntime must be exactly 'true' or 'false'"
    }
    value.toBoolean()
} ?: false

val verifiedFfmpegAar = rootProject.layout.projectDirectory
    .file(".gradle/verified-ffmpeg/ahdownload-ffmpeg-full-https-8.1.9.aar")
    .asFile

val prepareVerifiedFfmpegRuntimeAar by tasks.registering {
    group = "build setup"
    description = "Fetches and verifies the pinned AHDownload FFmpeg Full + HTTPS AAR."
    outputs.file(verifiedFfmpegAar)
    outputs.upToDateWhen { false }
    onlyIf { useVerifiedFfmpegRuntime }

    doLast {
        val expectedSha256 = providers.gradleProperty("ahdownloadFfmpegRuntimeSha256")
            .orNull
            ?.trim()
            ?.lowercase()
            .orEmpty()
        require(Regex("^[0-9a-f]{64}$").matches(expectedSha256)) {
            "Verified FFmpeg runtime selected, but ahdownloadFfmpegRuntimeSha256 is missing or invalid"
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun validateAar(file: File) {
            require(file.isFile && file.length() > 0L) {
                "Verified FFmpeg runtime AAR is missing or empty"
            }
            ZipFile(file).use { aar ->
                require(aar.getEntry("classes.jar") != null) {
                    "Verified FFmpeg runtime AAR has no classes.jar"
                }
                require(aar.getEntry("jni/arm64-v8a/libavformat.so") != null) {
                    "Verified FFmpeg runtime AAR has no arm64-v8a libavformat.so"
                }
                require(aar.getEntry("jni/arm64-v8a/libavcodec.so") != null) {
                    "Verified FFmpeg runtime AAR has no arm64-v8a libavcodec.so"
                }
                require(aar.getEntry("jni/x86_64/libavformat.so") != null) {
                    "Verified FFmpeg runtime AAR has no x86_64 libavformat.so"
                }
                require(aar.getEntry("jni/x86_64/libavcodec.so") != null) {
                    "Verified FFmpeg runtime AAR has no x86_64 libavcodec.so"
                }
            }
        }

        if (verifiedFfmpegAar.isFile && sha256(verifiedFfmpegAar) == expectedSha256) {
            validateAar(verifiedFfmpegAar)
            logger.lifecycle("Using cached verified FFmpeg Full + HTTPS AAR (SHA-256 matched).")
            return@doLast
        }

        verifiedFfmpegAar.parentFile.mkdirs()
        val temporaryFile = File(verifiedFfmpegAar.parentFile, "${verifiedFfmpegAar.name}.part")
        temporaryFile.delete()
        val assetUrl = URL(
            "https://github.com/aliprogram198i/AHDownload/releases/download/" +
                "ahdownload-ffmpeg-full-https-8.1.9-r1/ahdownload-ffmpeg-full-https-8.1.9.aar",
        )
        try {
            val connection = assetUrl.openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            if (connection is HttpURLConnection) {
                connection.instanceFollowRedirects = true
                connection.connect()
                require(connection.responseCode in 200..299) {
                    "FFmpeg runtime download returned HTTP ${connection.responseCode}"
                }
            }
            connection.getInputStream().buffered().use { input ->
                temporaryFile.outputStream().buffered().use { output -> input.copyTo(output) }
            }

            val actualSha256 = sha256(temporaryFile)
            require(actualSha256 == expectedSha256) {
                "FFmpeg runtime SHA-256 mismatch: expected=$expectedSha256 actual=$actualSha256"
            }
            validateAar(temporaryFile)
            Files.move(
                temporaryFile.toPath(),
                verifiedFfmpegAar.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            logger.lifecycle("Downloaded and verified FFmpeg Full + HTTPS AAR (SHA-256 matched).")
        } finally {
            temporaryFile.delete()
        }
    }
}

if (useVerifiedFfmpegRuntime) {
    tasks.configureEach {
        val needsVerifiedRuntime = name == "preBuild" ||
            name.startsWith("compile", ignoreCase = true) ||
            name.startsWith("assemble", ignoreCase = true) ||
            name.startsWith("test", ignoreCase = true) ||
            name.startsWith("connected", ignoreCase = true) ||
            name.startsWith("lint", ignoreCase = true)
        if (needsVerifiedRuntime) {
            dependsOn(prepareVerifiedFfmpegRuntimeAar)
        }
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(project(":core:designsystem"))
    implementation(project(":core:common"))
    implementation(project(":feature:welcome"))
    implementation(project(":feature:home"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:studio"))
    implementation(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    if (useVerifiedFfmpegRuntime) {
        implementation(files(verifiedFfmpegAar))
    } else {
        // Transitional default only. Production media acceptance and release builds
        // explicitly opt into the SHA-256-pinned custom Full + HTTPS AAR.
        implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full:8.1.9")
    }
    // Keep the FFmpegKit runtime exception dependency explicit to prevent release-time NoClassDefFoundError.
    implementation("com.arthenica:smart-exception-java:0.2.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation(libs.junit)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}