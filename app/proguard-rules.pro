# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.* { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# osmdroid
-keep class org.osmdroid.** { *; }

# commons-suncalc references this optional annotation in signatures only.
-dontwarn edu.umd.cs.findbugs.annotations.Nullable

# Strip debug/verbose logs in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# Keep data classes for Room
-keepclassmembers class com.bydmate.app.data.local.entity.** { *; }
-keepclassmembers class com.bydmate.app.data.local.dao.** { *; }

# Headless app_process command daemon — launched by name via `app_process ... com.bydmate.app.daemon.CommandDaemon`.
# Its main() entrypoint is resolved reflectively by the runtime, so the class + main must survive R8.
-keep class com.bydmate.app.daemon.CommandDaemon { public static void main(java.lang.String[]); }
