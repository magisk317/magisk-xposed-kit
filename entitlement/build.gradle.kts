plugins {
    id("magisk.android.library")
}

android {
    namespace = "io.github.magisk317.xposed.entitlement"
}

dependencies {
    // MobileHookGateCheck delegates to the entitlement library; the consuming
    // app ships it, so compileOnly keeps it out of this module's POM.
    compileOnly(libs.mobile.entitlement.android)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
