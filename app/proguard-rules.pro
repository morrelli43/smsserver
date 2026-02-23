# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep NanoHTTPD classes
-keep class fi.iki.elonen.** { *; }

# Keep Gson model classes
-keep class com.smsserver.model.** { *; }
-keepclassmembers class com.smsserver.model.** { *; }
