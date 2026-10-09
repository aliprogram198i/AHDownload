import java.io.File

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
        versionCode = 42
        versionName = "1.4.20"
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
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")
    // FFmpegKit 8.1.7 declares this runtime dependency incompletely; keep it explicit to prevent release-time NoClassDefFoundError.
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