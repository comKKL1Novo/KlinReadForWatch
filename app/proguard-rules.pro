# R8 rules for the watch build.
#
# Release builds now shrink, so the reflection-based parts of Room and the
# generated parsers need to survive. Everything else is shrunk aggressively,
# which is the point: the app was carrying ~60 MB of mapped .dex on the watch,
# almost all unused Compose and AndroidX code.

# ---- Room ----
# Room generates _Impl classes at compile time and looks them up by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# ---- Kotlin ----
# Metadata is needed for reflection over data classes and coroutines.
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-dontwarn kotlinx.coroutines.**

# ---- Compose ----
# Compose is largely reflection-free, but its runtime keeps a few entry points.
-dontwarn androidx.compose.**

# ---- Coroutines ----
# The internal service loader entry is optional at runtime.
-dontwarn kotlinx.coroutines.debug.**

# ---- DataStore / Protobuf-lite used internally ----
-dontwarn androidx.datastore.**

# ---- Keep the app's own model classes ----
# They are read directly by Kotlin code, but the enum name lookups in
# ReaderPreferences and PageTurnMode rely on `enum.name`, so keep the names.
-keepclassmembers enum com.klin.read.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Room entity field names must match the stored column names.
-keepclassmembers class com.klin.read.data.** { *; }
