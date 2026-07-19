# MapLibre uses reflection/JNI; keep its classes when minifying (release).
-keep class org.maplibre.android.** { *; }
-keep class com.mapbox.** { *; }
-dontwarn org.maplibre.android.**
