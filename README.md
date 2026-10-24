# magisk-xposed-kit

Shared Xposed hook framework library for the magisk317 module ecosystem.

## What it provides

- **BaseLibXposedEntry** — base class for Xposed module entries with hot-reload, ClassLoader resolution, and hook dispatch
- **BaseHook** — base class for hook implementations
- **MethodHook / MethodHookParam** — unified hook callback with DSL and classic styles
- **HookHelpers** — reflection utilities (findClass, findMethod, callMethod, field access)
- **XposedDsl** — Kotlin extension functions for hooking (`Method.hook {}`, `Class.hookMethod {}`)
- **LibXposedHookApi** — libxposed-backed hook API with hot-reload hook replacement
- **LibXposedPreferenceSources** — read-only remote/local `SharedPreferences` adapters with explicit
  hit/missing/unavailable results and source attribution
- **service submodule (optional)** — app-side libxposed service channel: bind state, scope requests, remote preference mirroring

## Usage

Add as a git submodule and include in your `settings.gradle.kts`:

```kotlin
include(":magisk-xposed-kit")
```

Then depend on it:

```kotlin
dependencies {
    implementation(project(":magisk-xposed-kit"))
}
```

The preference adapter is intentionally policy-free:

```kotlin
val remote = LibXposedPreferenceSources.remote(module, "module_runtime_preferences")
when (val result = remote.readBoolean("feature_enabled", false)) {
    is PreferenceRead.Hit -> use(result.value)
    PreferenceRead.Missing -> use(false)
    PreferenceRead.Unavailable -> deferUntilRemoteIsAvailable()
}
```

It does not define application keys, write or mirror values, change file permissions, or decide
whether a local source overrides a remote source. Those rules stay in each parent repository.

## Optional: the service submodule (`:magisk-xposed-kit:service`)

A module **app** (the settings APK, not the hooked process) reaches a libxposed framework over a
binder the framework pushes into a `${applicationId}.XposedService` `ContentProvider`. That channel is
what lets a module read its own live scope, ask the user to widen it, and write remote preferences.
Three thin wrappers sit on top of it:

- **XposedServiceBridge** — owns the single `XposedServiceHelper.registerListener` call and publishes
  the binder as `StateFlow<ServiceState>` (`Waiting` / `Bound` / `Retracted`). Registration is
  idempotent, a failed attempt stays retriable, and a later bind replaces an earlier one because more
  than one framework can be installed on a device.
- **ScopeRequester.requestMissing(packages, …)** — diffs the wanted packages against
  `XposedService.scope` and asks only for what is missing. `META-INF/xposed/scope.list` is re-read
  only when the module APK is installed or updated, so this is how a module offers a package it
  started supporting in a later release without asking anyone to reinstall it.
- **RemotePreferenceMirror** — mirrors the app's own `SharedPreferences` into remote groups, refusing
  entries, and whole groups, that are estimated too large for the single binder transaction a group
  travels in, and reporting every key it did not move.

Callbacks arrive on a binder thread; pass an `Executor` when they must run elsewhere.

Two hard constraints:

1. `io.github.libxposed:service` is held by this submodule alone, never by the kit root. The AAR
   contributes an `exported=true` `ContentProvider` through its own merged manifest, and that provider
   is also how a framework notices an installed APK — so a consumer that only hooks must never be
   handed it transitively. The kit root stays `compileOnly` for libxposed artifacts for this reason.
2. The parent repository's version catalog must define `libxposed-service` and
   `kotlinx-coroutines-core`: the kit builds inside the parent's `settings.gradle.kts` and reads that
   catalog. `MiPushFramework`, `XposedSmsCode` and `xinyi-relay` already do.

Including the submodule is explicit per repository, the same shape as `entitlement` and `keepalive`,
so a project that does not list it is untouched:

```kotlin
include(":magisk-xposed-kit:service")
project(":magisk-xposed-kit:service").projectDir = file("magisk-xposed-kit/service")
```

```kotlin
// the module app's own build file — never a hook-side module
implementation(project(":magisk-xposed-kit:service"))
```

Like the preference adapter, these wrappers are intentionally policy-free: which keys are mirrored,
which group a key belongs to, which packages to offer, and when prompting the user is appropriate all
stay in the parent repository.
