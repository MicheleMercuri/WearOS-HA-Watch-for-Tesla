-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.michele.teslawatch.**$$serializer { *; }
-keepclassmembers class com.michele.teslawatch.** {
    *** Companion;
}
-keepclasseswithmembers class com.michele.teslawatch.** {
    kotlinx.serialization.KSerializer serializer(...);
}
