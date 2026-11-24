# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
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
# WebView JS bridges are looked up reflectively by the WebView; keep annotated methods.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class io.fenjoon.app.NotificationBridge { *; }
-keep class io.fenjoon.app.WebAppInterface { *; }
-keep class io.fenjoon.app.ThemeBridge { *; }
-keep class io.fenjoon.app.billing.BillingBridge { *; }

# Store billing SDKs (only present in their own flavors). Poolakey ships empty consumer rules, so
# keep it rather than risk R8 stripping it.
-keep class ir.cafebazaar.poolakey.** { *; }
# Myket's documented keep rules: https://myket.ir/docs/technical/iab/android/
-keep class ir.myket.billingclient.** { *; }
-keep interface ir.myket.billingclient.** { *; }
-keep class com.android.vending.billing.** { *; }
-keepclassmembers class ir.myket.billingclient.util.** {
    public <fields>;
    public <methods>;
}
-dontwarn ir.cafebazaar.poolakey.**
-dontwarn ir.myket.billingclient.**

# WorkManager's Room database is instantiated reflectively (Room.getGeneratedImplementation);
# R8 otherwise strips the generated no-arg constructor and the app crashes at startup.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
