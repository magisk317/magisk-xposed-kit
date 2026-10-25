plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// The XLog stack is platform-neutral. The facade, the sanitizer policy, the record
// types and the file/dispatch plumbing compile for both the JVM and the Android
// target, so a desktop or server-side consumer gets the same logger the hook process
// writes through -- previously that code could only be reached from androidMain, which
// forced KMP consumers to keep two logging shapes.
//
// Only two things are genuinely platform-specific:
//   - where a line is printed (logcat versus stdout/stderr): expect/actual in PlatformLog;
//   - how the anonymous installation identity is persisted (SharedPreferences): androidMain.
//
// `jvmSharedMain` is an intermediate source set that both leaves include. It holds the
// JVM-family code (java.time, printf formatting, ThreadLocal, java.io, @JvmStatic) that is
// not Android-specific, instead of pretending those APIs are multiplatform in commonMain.
kotlin {
    jvm()

    android {
        namespace = "io.github.magisk317.logging"
        compileSdk(project.magiskCompileSdk())
        minSdk = libs.versions.minSdk.get().toInt()
        // The R8 rule that keeps Throwable class names readable in telemetry reasons ships
        // through the KMP target's consumer keep rules (AGP 9.5 exposes them under optimization).
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.files(file("consumer-rules.pro"))
        }
    }

    sourceSets {
        // Neither of KGP's two supported shapes for a shared source set fits this pair: declaring
        // the group through applyDefaultHierarchyTemplate leaves `jvmSharedMain` off commonMain,
        // and wiring it with dependsOn() is reported as a deviation from the default template
        // ("Default Kotlin Hierarchy Template Not Applied Correctly" -- and the repo-wide
        // kotlin.mpp.applyDefaultHierarchyTemplate=false would take the template away from every
        // other KMP module). So the directory is shared instead of the source set, which is the
        // remedy KGP itself proposes for this case and leaves both leaves with a single root.
        getByName("jvmMain").kotlin.srcDir("src/jvmSharedMain/kotlin")
        getByName("androidMain").kotlin.srcDir("src/jvmSharedMain/kotlin")
        commonMain.dependencies {
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
