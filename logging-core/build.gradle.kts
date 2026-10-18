plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Platform-neutral core of the shared logging stack: JSONL encoding, the
// fixed-window limiter and the structured-credential redactor. Consumers that
// need the full Android-facing XLog stay on :magisk-xposed-kit:logging, which
// depends on this module.
kotlin {
    jvm()

    android {
        namespace = "io.github.magisk317.logging.core"
        compileSdk(project.magiskCompileSdk())
        minSdk = libs.versions.minSdk.get().toInt()
    }

    sourceSets {
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnitPlatform()
}
