plugins {
    id("magisk.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.magisk317.xposed.backup"
}

dependencies {
    implementation(project(":magisk-xposed-kit:logging"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}
