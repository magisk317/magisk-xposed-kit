import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

val libsCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun versionStringOrNull(name: String): String? {
    val version = libsCatalog.findVersion(name)
    return if (version.isPresent) version.get().requiredVersion else null
}

fun bytecodeTargetString(): String = versionStringOrNull("javaBytecode")
    ?: versionStringOrNull("java")
    ?: "17"

fun versionInt(name: String, fallback: Int): Int = versionStringOrNull(name)?.toIntOrNull() ?: fallback

android {
    namespace = "io.github.magisk317.logging"
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        minSdk = versionInt("minSdk", 26)
    }

    compileOptions {
        val javaVersion = JavaVersion.toVersion(bytecodeTargetString())
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(
                runCatching { JvmTarget.fromTarget(bytecodeTargetString()) }
                    .getOrDefault(JvmTarget.JVM_25)
            )
        }
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
