# LSPosed loads the entry class by name (see assets/xposed_init) via reflection,
# so it must never be renamed or stripped.
-keep class com.melos.MelosHookEntry { *; }

# Keep the Xposed API surface we compile against.
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**
