import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

val signingFile = providers.environmentVariable("AH_KEYSTORE_FILE").orNull
val signingStorePassword = providers.environmentVariable("AH_KEYSTORE_PASSWORD").orNull
val signingAlias = providers.environmentVariable("AH_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("AH_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    signingFile,
    signingStorePassword,
    signingAlias,
    signingKeyPassword
).all { !it.isNullOrBlank() }
val isPullRequestBuild = System.getenv("GITHUB_EVENT_NAME").equals("pull_request", ignoreCase = true)

android {
    namespace = "com.ahdownload.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.ahdownload.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "1.2.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("releaseOfficial") {
                storeFile = File(signingFile!!)
                storePassword = signingStorePassword
                keyAlias = signingAlias
                keyPassword = signingKeyPassword
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("releaseOfficial")
            }
        }
        debug { applicationIdSuffix = ".debug" }
    }

    buildFeatures { compose = true; buildConfig = true }
}

tasks.matching {
    it.name == "assembleRelease" || it.name == "bundleRelease"
}.configureEach {
    doFirst {
        if (!isPullRequestBuild) {
            check(hasReleaseSigning) {
                "Release signing is required for production builds. Configure the AH_* signing secrets."
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip { install("yt-dlp==2026.8.19") }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.navigation:navigation-compose:2.9.4")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    testImplementation("junit:junit:4.13.2")
}
