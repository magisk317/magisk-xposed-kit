plugins {
    id("magisk.android.library")
}

android {
    namespace = "io.github.magisk317.xposed.service"
}

dependencies {
    // The service AAR contributes android:authorities="${applicationId}.XposedService" through its
    // own merged manifest, which is also how a libxposed framework notices an installed APK. That is
    // why the dependency lives only here and never in the kit root: a consumer that only hooks must
    // not be handed an exported ContentProvider it did not ask for.
    api(libs.libxposed.service)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnitPlatform()
}
