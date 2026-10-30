plugins { id("magisk.android.library") }

android { namespace = "io.github.magisk317.xposed.runtime" }

dependencies {
    api(project(":magisk-xposed-kit:logging"))
    api(project(":magisk-xposed-kit:contract"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
