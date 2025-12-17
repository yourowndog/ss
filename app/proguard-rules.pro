# Add project specific ProGuard rules here.

# ML Kit
-keep class com.google.mlkit.** { *; }

# Keep our provider interfaces
-keep interface com.screensense.vision.VisionProvider { *; }
-keep interface com.screensense.tts.TTSProvider { *; }
