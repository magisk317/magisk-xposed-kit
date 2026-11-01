plugins { id("magisk.android.library") }

android { namespace = "io.github.magisk317.xposed.capability" }

dependencies {
    api(project(":magisk-xposed-kit"))
    api(project(":magisk-xposed-kit:logging"))
    api(project(":magisk-xposed-kit:runtime"))
    implementation(libs.androidx.core.ktx)
    // The capability module re-exports the kit's hook parameter types, so the libxposed api has to
    // be on the unit test classpath as well: without it the hook wrapper test cannot even load
    // the class it exercises.
    testImplementation(libs.libxposed.api)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
