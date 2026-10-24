plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// Shared keep-alive hook. The platform-neutral decisions (should a kill be
// suppressed, what bucket does a package deserve) and the descriptor-matching
// logic live in commonMain so they can be unit tested on a plain JVM; the
// reflection against framework classes (ProcessRecord, OomAdjuster, ...) and the
// BaseHook wiring stay in androidMain.
kotlin {
    jvm()

    android {
        namespace = "io.github.magisk317.xposed.keepalive"
        compileSdk(project.magiskCompileSdk())
        minSdk = libs.versions.minSdk.get().toInt()
    }

    sourceSets {
        androidMain.dependencies {
            // BaseHook / HookHelpers / the hook DSL live in the kit root module.
            implementation(project(":magisk-xposed-kit"))
        }

        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnitPlatform()
}
