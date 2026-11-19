# CodeQuest AI Production ProGuard & R8 Optimization Rules

# 1. Keep Room Database entities and DAOs
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# 2. Keep Domain & DTO Data Models (Moshi / JSON serialization)
-keep class com.example.model.** { *; }
-keep class com.example.data.remote.** { *; }
-keepclassmembers class com.example.model.** { <fields>; }
-keepclassmembers class com.example.data.remote.** { <fields>; }

# 3. Retrofit & OkHttp
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# 4. Moshi & Reflection
-keep class com.squareup.moshi.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod

# 5. Preserve Line Numbers for Crash Reporting
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
