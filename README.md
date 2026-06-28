# magisk-xposed-kit

Shared Xposed hook framework library for the magisk317 module ecosystem.

## What it provides

- **BaseLibXposedEntry** — base class for Xposed module entries with hot-reload, ClassLoader resolution, and hook dispatch
- **BaseHook** — base class for hook implementations
- **MethodHook / MethodHookParam** — unified hook callback with DSL and classic styles
- **HookHelpers** — reflection utilities (findClass, findMethod, callMethod, field access)
- **XposedDsl** — Kotlin extension functions for hooking (`Method.hook {}`, `Class.hookMethod {}`)
- **LibXposedHookApi** — libxposed-backed hook API with hot-reload hook replacement

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
