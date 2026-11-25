# Keep OpenCV JNI bindings
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Keep AccessibilityService & Foreground Services
-keep class com.dls26.tacticalbot.service.** { *; }
