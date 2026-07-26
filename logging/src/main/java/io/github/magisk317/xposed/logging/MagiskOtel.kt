package io.github.magisk317.xposed.logging

import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal OTLP/HTTP JSON exporter for GitLab Observability.
 *
 * Default is **off**. Never throws to callers. Do not attach SMS bodies, codes,
 * tokens, or other sensitive payloads in [attributes].
 */
object MagiskOtel {
    private const val NANOS_PER_MILLI = 1_000_000L

    const val DEFAULT_ENDPOINT: String =
        "https://136325658.otel.gitlab-o11y.com:14318/v1/traces"

    data class Config(
        val enabled: Boolean = false,
        val endpoint: String = DEFAULT_ENDPOINT,
        val serviceName: String,
        val serviceVersion: String = "unknown",
        val projectId: String = "",
        val projectName: String = "",
        val environment: String = "device",
        val serviceInstanceId: String = "",
        val activityTracker: InstallationActivityTracker? = null,
        val connectTimeoutMs: Int = 4_000,
        val readTimeoutMs: Int = 4_000,
    )

    private val configRef = AtomicReference<Config?>(null)
    private val executor =
        Executors.newSingleThreadExecutor(
            ThreadFactory { runnable ->
                Thread(runnable, "magisk-otel").apply {
                    isDaemon = true
                }
            },
        )

    @JvmStatic
    fun configure(config: Config) {
        configRef.set(config)
    }

    @JvmStatic
    @JvmOverloads
    fun configureForInstallation(
        context: android.content.Context,
        config: Config,
        preferencesName: String = AnonymousInstallationId.DEFAULT_PREFERENCES_NAME,
    ) {
        val state = AnonymousInstallationId.createState(context, preferencesName)
        configure(
            config.copy(
                serviceInstanceId = state.installationId,
                activityTracker = state.tracker,
            ),
        )
    }

    /**
     * Install config only when no process-local config exists yet.
     * Useful for hook processes that may race with later host configuration.
     */
    @JvmStatic
    fun configureIfAbsent(config: Config): Boolean {
        return configRef.compareAndSet(null, config)
    }

    @JvmStatic
    fun isEnabled(): Boolean {
        val configured = configRef.get()
        if (configured != null) {
            return configured.enabled
        }
        return systemPropertyEnabled()
    }

    /**
     * Emit a best-effort span. Safe to call from any thread.
     */
    @JvmStatic
    @JvmOverloads
    fun event(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        statusOk: Boolean = true,
    ) {
        val config = resolveConfig(name) ?: return
        if (!config.enabled) {
            return
        }
        val safeName = name.trim().ifEmpty { "event" }
        val safeAttrs = attributes.filterKeys { it.isNotBlank() }.mapValues { it.value }
        executor.execute {
            runCatching {
                if (safeName != "app.active") {
                    config.activityTracker?.claimWindows(System.currentTimeMillis())?.forEach { active ->
                        exportSpan(
                            config = config,
                            name = "app.active",
                            attributes = mapOf("window" to active.window, "bucket" to active.bucket),
                            statusOk = true,
                        )
                    }
                }
                exportSpan(config, safeName, safeAttrs, statusOk)
            }
        }
    }

    internal fun buildPayload(
        config: Config,
        name: String,
        attributes: Map<String, String>,
        statusOk: Boolean,
        nowNanos: Long = System.currentTimeMillis() * NANOS_PER_MILLI,
    ): String {
        val start = nowNanos - NANOS_PER_MILLI
        val resourceAttrs = mutableListOf(
            stringAttr("service.name", config.serviceName),
            stringAttr("service.version", config.serviceVersion),
            stringAttr("deployment.environment.name", config.environment),
        )
        if (config.projectId.isNotBlank()) {
            resourceAttrs += stringAttr("gitlab.project.id", config.projectId)
        }
        if (config.projectName.isNotBlank()) {
            resourceAttrs += stringAttr("gitlab.project.name", config.projectName)
        }
        AnonymousInstallationId.canonicalUuid(config.serviceInstanceId)?.let {
            resourceAttrs += stringAttr("service.instance.id", it)
        }

        val spanAttrs = mutableListOf(stringAttr("magisk.event", name))
        attributes.forEach { (key, value) ->
            if (key.isNotBlank()) {
                spanAttrs += stringAttr(key, value)
            }
        }

        val statusCode = if (statusOk) 1 else 2
        return buildString {
            append("{\"resourceSpans\":[{\"resource\":{\"attributes\":[")
            append(resourceAttrs.joinToString(","))
            append("]},\"scopeSpans\":[{\"scope\":{\"name\":\"magisk.app.otel\",\"version\":\"1\"},\"spans\":[{")
            append("\"traceId\":\"").append(randomHex(32)).append("\",")
            append("\"spanId\":\"").append(randomHex(16)).append("\",")
            append("\"name\":\"").append(jsonEscape(name)).append("\",")
            append("\"kind\":1,")
            append("\"startTimeUnixNano\":\"").append(start).append("\",")
            append("\"endTimeUnixNano\":\"").append(nowNanos).append("\",")
            append("\"attributes\":[").append(spanAttrs.joinToString(",")).append("],")
            append("\"status\":{\"code\":").append(statusCode).append("}")
            append("}]}]}]}")
        }
    }

    private fun resolveConfig(fallbackServiceHint: String): Config? {
        configRef.get()?.let {
            return it
        }
        if (!systemPropertyEnabled()) {
            return null
        }
        val service =
            System.getProperty("magisk.otel.service")
                ?.trim()
                .orEmpty()
                .ifEmpty { fallbackServiceHint.ifEmpty { "magisk-app" } }
        return Config(
            enabled = true,
            endpoint =
                System.getProperty("magisk.otel.endpoint")
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { DEFAULT_ENDPOINT },
            serviceName = service,
            serviceVersion =
                System.getProperty("magisk.otel.version")
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { "unknown" },
            projectId = System.getProperty("magisk.otel.project_id").orEmpty(),
            projectName = System.getProperty("magisk.otel.project_name").orEmpty(),
            environment =
                System.getProperty("magisk.otel.env")
                    ?.trim()
                    .orEmpty()
                    .ifEmpty { "device" },
        )
    }

    private fun systemPropertyEnabled(): Boolean {
        val value = System.getProperty("magisk.otel.enabled")?.trim()?.lowercase().orEmpty()
        return value == "1" || value == "true" || value == "yes"
    }

    private fun exportSpan(
        config: Config,
        name: String,
        attributes: Map<String, String>,
        statusOk: Boolean,
    ) {
        val payload = buildPayload(config, name, attributes, statusOk)
        val connection = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = config.connectTimeoutMs
            readTimeout = config.readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            BufferedOutputStream(connection.outputStream).use { output ->
                output.write(payload.toByteArray(Charsets.UTF_8))
                output.flush()
            }
            runCatching { connection.inputStream.use { it.readBytes() } }
            runCatching { connection.errorStream?.use { it.readBytes() } }
        } finally {
            connection.disconnect()
        }
    }

    private fun stringAttr(key: String, value: String): String {
        return "{\"key\":\"${jsonEscape(key)}\",\"value\":{\"stringValue\":\"${jsonEscape(value)}\"}}"
    }

    private fun jsonEscape(value: String): String {
        val out = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        out.append("\\u%04x".format(ch.code))
                    } else {
                        out.append(ch)
                    }
                }
            }
        }
        return out.toString()
    }

    private fun randomHex(length: Int): String {
        val raw =
            UUID.randomUUID().toString().replace("-", "") +
                UUID.randomUUID().toString().replace("-", "")
        return raw.take(length)
    }
}
