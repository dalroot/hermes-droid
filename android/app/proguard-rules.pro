# HermesDroid Proguard Rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @org.json.* <fields>;
}

# NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }

# Models
-keep class com.hermesdroid.model.** { *; }

# Paho MQTT
-dontwarn org.eclipse.paho.client.mqttv3.**
-keep class org.eclipse.paho.client.mqttv3.** { *; }
