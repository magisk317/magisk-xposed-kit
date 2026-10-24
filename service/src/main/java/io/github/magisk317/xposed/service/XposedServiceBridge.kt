package io.github.magisk317.xposed.service

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Identity of the framework that pushed the service binder. */
data class FrameworkInfo(
    val apiVersion: Int,
    val name: String,
    val version: String,
    val versionCode: Long,
) {
    companion object {
        /** Used when the binder died between the bind callback and the identity read. */
        val UNKNOWN = FrameworkInfo(apiVersion = -1, name = "unknown", version = "unknown", versionCode = -1L)
    }
}

/** What the module app currently knows about its own hooking framework. */
sealed interface ServiceState {

    /** No binder has been pushed yet. */
    data object Waiting : ServiceState

    /** A framework binder is live. */
    data class Bound(val service: XposedService, val framework: FrameworkInfo) : ServiceState

    /** The live binder was retracted; a later bind replaces it. */
    data object Retracted : ServiceState
}

/**
 * The module app's single owner of the libxposed service listener.
 *
 * [XposedServiceHelper.registerListener] is documented as call-once, and a framework may push its
 * binder either before registration — the AAR's own provider caches it and replays it during
 * registration — or after, so the registration flag, the delivered binder and the identity read
 * belong in one place. Binding can happen while the credential-encrypted store is still locked, so
 * a collector must check `UserManager.isUserUnlocked` before touching CE data.
 */
object XposedServiceBridge {

    private val registered = AtomicBoolean(false)

    @Volatile
    private var report: (operation: String, error: Throwable) -> Unit = { _, _ -> }

    private val _state = MutableStateFlow<ServiceState>(ServiceState.Waiting)

    val state: StateFlow<ServiceState> = _state.asStateFlow()

    val currentService: XposedService?
        get() = (_state.value as? ServiceState.Bound)?.service

    val isBound: Boolean
        get() = _state.value is ServiceState.Bound

    /**
     * Installs the framework listener.
     *
     * @param registrar injectable only so the call-once guard is testable without a Binder; the
     *   default is the real [XposedServiceHelper.registerListener].
     * @return true when this call installed the listener. A repeat call is a no-op and returns
     *   false; a call whose registrar threw returns false and stays retriable, because the
     *   framework never got a listener to deliver to.
     */
    fun register(
        onError: (operation: String, error: Throwable) -> Unit = { _, _ -> },
        registrar: (XposedServiceHelper.OnServiceListener) -> Unit = { XposedServiceHelper.registerListener(it) },
    ): Boolean {
        if (!registered.compareAndSet(false, true)) return false
        report = onError
        val failure = runCatching { registrar(listener) }.exceptionOrNull()
        if (failure != null) {
            report("register Xposed service listener", failure)
            registered.set(false)
            return false
        }
        return true
    }

    private val listener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            // A later bind wins: more than one libxposed framework can be installed, and the newest
            // binder is the one this app has to talk to.
            _state.value = ServiceState.Bound(service, readFrameworkInfo(service))
        }

        override fun onServiceDied(service: XposedService) {
            val current = _state.value
            if (current is ServiceState.Bound && current.service === service) {
                _state.value = ServiceState.Retracted
            }
        }
    }

    private fun readFrameworkInfo(service: XposedService): FrameworkInfo =
        runCatching {
            FrameworkInfo(
                apiVersion = service.apiVersion,
                name = service.frameworkName,
                version = service.frameworkVersion,
                versionCode = service.frameworkVersionCode,
            )
        }.onFailure { report("read framework identity", it) }
            .getOrDefault(FrameworkInfo.UNKNOWN)
}
