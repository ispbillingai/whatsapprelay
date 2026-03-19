# Keep model classes for Gson
-keep class com.freeisp.wa.MessageModel { *; }
-keep class com.freeisp.wa.ApiClient$ApiResult { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Gson - critical: keep TypeToken for generic type resolution
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }

# Keep FileProvider
-keep class androidx.core.content.FileProvider { *; }

# Keep JSON parsing
-dontwarn org.json.**
-keep class org.json.** { *; }

# Prevent R8 from stripping interface info needed for OkHttp
-dontwarn java.lang.invoke.StringConcatFactory
