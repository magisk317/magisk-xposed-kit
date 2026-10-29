plugins { id("magisk.android.library") }

android { namespace = "io.github.magisk317.xposed.capability" }

dependencies {
    api(project(":magisk-xposed-kit"))
    api(project(":magisk-xposed-kit:logging"))
    api(project(":magisk-xposed-kit:runtime"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
