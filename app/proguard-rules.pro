# Shizuku instantiates the user service and talks to its provider by class name.
-keep class rikka.shizuku.** { *; }

# App classes stay whole: UInputService is created by name in the shell process, the AIDL
# stub/proxy must match on both sides, and cpp/uinput_jni.cpp binds to UInputNative's
# method names. Only third-party code is shrunk.
-keep class com.steamcontroller.android.** { *; }
