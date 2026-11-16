# Minification is disabled in build.gradle.kts, but keep these rules as a safety net
# in case minification is enabled later.

# The Xposed entry point is referenced by name from assets/xposed_init.
-keep class com.iosshade.systemui.MainHook { *; }

# Never obfuscate hook classes (they are looked up by name against SystemUI).
-keep class com.iosshade.systemui.hooks.** { *; }
-keep class com.iosshade.systemui.overlay.** { *; }
-keep class com.iosshade.systemui.view.** { *; }
