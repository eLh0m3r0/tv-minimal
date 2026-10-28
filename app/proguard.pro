-dontobfuscate
-keep class cz.tvminimal.home.App { *; }
-keep class cz.tvminimal.home.HomeActivity { *; }
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-dontwarn com.google.j2objc.**
-dontwarn javax.annotation.**
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
-dontwarn afu.org.checkerframework.**
# compile-time-only annotations
-dontwarn androidx.annotation.**
# API 35+ class; Media3 only touches it behind an SDK_INT check (target device was API 34)
-dontwarn android.media.LoudnessCodecController
-dontwarn android.media.LoudnessCodecController$*
-keep class cz.tvminimal.home.HomeKeyService { *; }
