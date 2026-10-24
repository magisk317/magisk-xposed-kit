package io.github.magisk317.xposed.diagnostics

import android.os.Build

/**
 * Which device and which build produced a diagnostic bundle.
 *
 * Every product used to stamp a different subset — some wrote only an export time, one wrote
 * a package line without a version, another kept the commit hash to itself — which left the
 * person reading the bundle unable to tell a hand-built install from a distributed release.
 * The bundle header now carries the same fields for all of them, and the shared libraries
 * report their own commit hash instead of relying on the host app to forward it.
 */
data class DiagnosticBundleIdentity(
    val product: String,
    val packageName: String,
    val versionName: String,
    val versionCode: String,
    val distributionChannel: String,
    val commitSha: String,
    val kitCommitSha: String,
    val libraryCommitShas: Map<String, String>,
    val deviceManufacturer: String,
    val deviceModel: String,
    val deviceName: String,
    val osVersion: String,
    val sdkInt: Int,
) {
    fun summaryLines(): List<String> = buildList {
        add("Product: $product")
        add("Package: $packageName version=$versionName ($versionCode) channel=$distributionChannel commit=$commitSha")
        add("Libraries: kit=$kitCommitSha" + libraryCommitShas.entries.joinToString("") { " ${it.key}=${it.value}" })
        add("Device: $deviceManufacturer $deviceModel ($deviceName) Android $osVersion (API $sdkInt)")
    }

    /** Flat string payload so every product can embed the same field names in its own summary. */
    fun jsonFields(): Map<String, String> = linkedMapOf(
        "product" to product,
        "packageName" to packageName,
        "versionName" to versionName,
        "versionCode" to versionCode,
        "distributionChannel" to distributionChannel,
        "commitSha" to commitSha,
        "kitCommitSha" to kitCommitSha,
        "deviceManufacturer" to deviceManufacturer,
        "deviceModel" to deviceModel,
        "deviceName" to deviceName,
        "osVersion" to osVersion,
        "sdkInt" to sdkInt.toString(),
    ).apply {
        libraryCommitShas.forEach { (name, sha) -> put("lib_$name", sha) }
    }

    companion object {
        const val UNKNOWN = "unknown"

        fun of(
            product: String,
            packageName: String,
            versionName: String,
            versionCode: String,
            distributionChannel: String,
            commitSha: String,
            libraryCommitShas: Map<String, String> = emptyMap(),
        ): DiagnosticBundleIdentity = DiagnosticBundleIdentity(
            product = product.ifBlank { UNKNOWN },
            packageName = packageName,
            versionName = versionName.ifBlank { UNKNOWN },
            versionCode = versionCode.ifBlank { UNKNOWN },
            distributionChannel = distributionChannel.ifBlank { UNKNOWN },
            commitSha = commitSha.ifBlank { UNKNOWN },
            kitCommitSha = DiagnosticBuildStamps.kitCommitHash,
            libraryCommitShas = libraryCommitShas,
            deviceManufacturer = readText { Build.MANUFACTURER },
            deviceModel = readText { Build.MODEL },
            deviceName = readText { Build.DEVICE },
            osVersion = readText { Build.VERSION.RELEASE },
            sdkInt = runCatching { Build.VERSION.SDK_INT }.getOrDefault(0),
        )

        /** Unit tests run against stubbed framework classes, so every read has to be guarded. */
        private fun readText(reader: () -> String?): String =
            runCatching(reader).getOrNull().orEmpty().ifBlank { UNKNOWN }
    }
}
