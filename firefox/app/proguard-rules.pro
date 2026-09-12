# GeckoView keeps its JNI + reflection entry points; the shipped consumer rules
# cover it. These extra lines are for our own reflection-based pref lookups.
-keepclassmembers class org.mozilla.geckoview.GeckoRuntimeSettings {
    public *** set*(...);
}
-keep class com.minimal.browser.** { *; }
-dontwarn org.mozilla.gecko.**
