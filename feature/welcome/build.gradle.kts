plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ahdownload.feature.welcome"
    compileSdk = 36
    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
}
