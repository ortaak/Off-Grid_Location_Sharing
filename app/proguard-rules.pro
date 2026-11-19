# OSMDroid rules
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Strip verbose debug logs in release
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
