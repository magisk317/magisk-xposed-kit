# Telemetry reasons carry exception class names (javaClass.simpleName) in release builds.
# Keep the original names so R8 renaming cannot turn reason values into opaque short names
# such as "u62" - the cloud side groups spans by reason.
-keepnames class * extends java.lang.Throwable
