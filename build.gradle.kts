plugins {
    id("magisk.android.library")
}

android {
    namespace = "io.github.magisk317.xposed"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    api(project(":magisk-xposed-kit:logging"))
    compileOnly(libs.libxposed.api)
    compileOnly(libs.androidx.annotation)
    testImplementation(libs.libxposed.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
