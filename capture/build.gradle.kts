plugins {
    id("magisk.android.library")
}

android {
    namespace = "io.github.magisk317.xposed.capture"
}

dependencies {
    val kitPathPrefix = if (project.path.startsWith(":magisk-xposed-kit:")) {
        ":magisk-xposed-kit"
    } else {
        ""
    }
    implementation(project("$kitPathPrefix"))
    api(project("$kitPathPrefix:runtime"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
