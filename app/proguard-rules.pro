# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ============================================================================
# AgentPro release hardening (R8 only runs for release builds; debug is unaffected)
# ============================================================================

# Strip ALL logging in release so the shipped binary leaks no runtime info (logic, SIP addresses,
# credentials that might appear in messages, etc.) and is a bit smaller/faster. assumenosideeffects
# needs the -optimize config, which the release build uses (proguard-android-optimize.txt).
-assumenosideeffects class org.linphone.core.tools.Log {
    public static *** i(...);
    public static *** d(...);
    public static *** w(...);
    public static *** e(...);
    public static *** message(...);
}
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}

# Obfuscation: R8's DEFAULT class/method renaming is already enabled for release builds and is the
# real protection. Aggressive rules (-repackageclasses / -allowaccessmodification) are deliberately
# NOT used: their extra benefit is marginal and they can't be runtime-tested here without risk, so we
# stick to the proven upstream R8 config to keep the app stable.

# ============================================================================
# Data Binding — the whole UI is built on <layout> data binding. With the
# optimizing R8 config (enabled by the log-stripping -assumenosideeffects above)
# R8 was stripping the GENERATED mapper, so release builds crashed at launch:
#   java.lang.NoClassDefFoundError: androidx.databinding.DataBinderMapperImpl
#       at org.linphone.ui.main.MainActivity.onCreate
# Keep the data-binding runtime and every generated mapper / *Binding class.
# (Debug builds aren't minified, which is why only release builds hit this.)
# ============================================================================
-keep class androidx.databinding.** { *; }
-keep class org.linphone.DataBinderMapperImpl { *; }
-keep class org.linphone.databinding.** { *; }
-keep class * extends androidx.databinding.ViewDataBinding { *; }
-dontwarn androidx.databinding.**