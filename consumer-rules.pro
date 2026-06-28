# Xposed log bridge - keep public API and ContentProvider lifecycle methods
-keep class io.github.magisk317.xposed.logging.BaseXposedLogProvider {
    <init>();
    public *** appendLog(...);
    public android.net.Uri insert(android.net.Uri, android.content.ContentValues);
}
-keep class io.github.magisk317.xposed.logging.XposedLogClient {
    public static *** configure(...);
    public static *** attachContext(...);
    public static *** send(...);
    public static *** append(...);
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}
-keep class io.github.magisk317.xposed.logging.CallerGuard {
    public *** isCallerAllowed(...);
    public static *** install(...);
    public static *** isPrivilegedUid(...);
    public static *** isSystemPackageFlags(...);
}
-keep class io.github.magisk317.xposed.logging.ProcessNameResolver {
    public static *** currentProcessName(...);
}
-keep class io.github.magisk317.xposed.logging.XposedLogEvent {
    <init>(...);
    public *** toContentValues(...);
    public static *** fromContentValues(...);
    public static *** appendUri(...);
}
