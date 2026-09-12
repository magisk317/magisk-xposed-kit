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
