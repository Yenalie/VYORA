# VYORA release rules (R8).

# Keep line numbers so crash reports stay readable; hide the original file names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- NewPipe Extractor --------------------------------------------------------------------
# The extractor finds services and parses YouTube's JSON and JavaScript reflectively.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**

# Rhino (runs YouTube's signature/throttling JavaScript) uses reflection heavily.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# jsoup / nanojson used by the extractor.
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
-keep class com.grack.nanojson.** { *; }
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.annotations.**

# OkHttp / Okio ship their own consumer rules; silence optional platform warnings.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Media3 session service is referenced from the manifest (kept by default), keep its
# reflective command/session helpers too.
-keep class androidx.media3.session.** { *; }

# --- Logging --------------------------------------------------------------------------------
# Debug / verbose / info logs are for development builds only; warnings and errors stay.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
