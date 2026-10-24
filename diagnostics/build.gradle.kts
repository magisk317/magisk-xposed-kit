plugins {
    id("magisk.android.library")
}

android {
    namespace = "io.github.magisk317.xposed.diagnostics"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // The bundle pipeline owns the log policy: it reads MagiskLogPolicy and redacts exported
    // text with the shared sanitizer, so the logging stack is part of this module's surface.
    api(project(":magisk-xposed-kit:logging"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
