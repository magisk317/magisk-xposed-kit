plugins {
    id("magisk.android.library")
}

android {
    namespace = "io.github.magisk317.xposed.permission"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
