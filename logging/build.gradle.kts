plugins {
    id("magisk.android.library")
}

android {
    namespace = "io.github.magisk317.logging"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(libs.kermit)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
