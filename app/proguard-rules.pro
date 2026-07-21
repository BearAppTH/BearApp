-keep class app.bear.store.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Gson uses reflection on model fields — keep names so parsing (and cache
# read/write via Gson) keeps working after R8 shrinking/obfuscation.
-keepclassmembers class app.bear.store.model.** {
    <fields>;
}
-keepclassmembers class app.bear.store.viewmodel.MainViewModel$AppsCacheEnvelope {
    <fields>;
}
-keep class com.google.gson.reflect.TypeToken
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp / okio platform checks (standard rules for R8 with these libs)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
