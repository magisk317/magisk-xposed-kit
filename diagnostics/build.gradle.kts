plugins {
    id("magisk.android.library")
}

android {
    namespace = "io.github.magisk317.xposed.diagnostics"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
