# Bouncy Castle and OkHttp reference optional platforms not present on Android.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keep class org.bouncycastle.** { *; }
