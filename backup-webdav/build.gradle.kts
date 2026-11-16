plugins {
    id("magisk.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.magisk317.xposed.backup.webdav"
}

dependencies {
    // The WebDAV provider implements CloudBackupProvider, so the backup module's
    // API must stay visible to consumers of this module's AAR.
    api(project(":magisk-xposed-kit:backup"))
    implementation(project(":magisk-xposed-kit:logging"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    useJUnitPlatform()
}
