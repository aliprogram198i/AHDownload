plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin.jvmToolchain(17)

dependencies {
    implementation(project(":core:common"))
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation(kotlin("test"))
}
