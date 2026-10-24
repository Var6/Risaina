# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.risaina.app.**$$serializer { *; }
-keepclassmembers class com.risaina.app.** { *** Companion; }
-keepclasseswithmembers class com.risaina.app.** { kotlinx.serialization.KSerializer serializer(...); }
# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn okhttp3.internal.platform.**
