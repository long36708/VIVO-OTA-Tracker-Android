-dontwarn org.slf4j.helpers.SubstituteLogger

# Protobuf
-keep class chromeos_update_engine.** { *; }
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Native JNI
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.mytiantian.updater.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Apache Commons Compress
-dontwarn org.apache.commons.compress.**
-keep class org.apache.commons.compress.** { *; }

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**

# Miuix
-keep class top.yukonga.miuix.** { *; }
