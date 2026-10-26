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

# Glance widgets (checked against the R8 output of the release build)
# 1. A widget button's ActionCallback (SendPingAction) is created by Glance from its class name with
#    getDeclaredConstructor().newInstance(). Glance's own rule keeps the class but, in R8 full mode, not
#    its constructor: R8 removed SendPingAction.<init>() and onAction(), so the ping button on the "Us"
#    and Touch Ping widgets did nothing in release builds.
-keep public class * implements androidx.glance.appwidget.action.ActionCallback {
    public <init>();
}
# 2. Glance stores, per receiver, the provider's class name (provider.javaClass.canonicalName), and
#    GlanceWidgetRefresher / updateAll() find placed widgets with getGlanceIds(provider.javaClass).
#    R8 merged all eight GlanceAppWidget classes into one, so every lookup returned every placed widget
#    and each widget was redrawn with the wrong layout. Keeping the names keeps them separate classes,
#    and stable across releases (the stored names survive an app update).
-keepnames class * extends androidx.glance.appwidget.GlanceAppWidget

# Navigation: RisainaAppUi.routeName() compares route::class.qualifiedName with destination.route,
# which is the route's serial name (its source class name). Route class names must not be obfuscated.
-keepnames @kotlinx.serialization.Serializable class com.risaina.app.navigation.**
