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
    // Platform-neutral encoding/limiter/redaction primitives live in
    // logging-core; the Android-facing XLog stack composes on top of them.
    api(project(":magisk-xposed-kit:logging-core"))
    api(libs.kermit)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
