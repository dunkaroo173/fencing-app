-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# DJI Mobile SDK V5
-keep class dji.** { *; }
-keep class com.dji.** { *; }
-keep class com.secneo.** { *; }
-dontwarn dji.**
-dontwarn com.dji.**
-dontwarn com.secneo.**

# Keep SDK model classes
-keep class dji.v5.** { *; }
-keep interface dji.v5.** { *; }
-keepclassmembers class dji.v5.** { *; }

# App model classes
-keep class com.sclassfencing.app.model.** { *; }

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}
