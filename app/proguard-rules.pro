# Media3, Hilt, Room, Coil and kotlinx.serialization ship consumer rules.
# libmpv's AAR keeps dev.jdtech.mpv.MPVLib (its JNI entry points call back into it).

# Navigation's type-safe routes are @Serializable classes looked up by name.
-keep @kotlinx.serialization.Serializable class com.ayesha.player.ui.navigation.** { *; }

# Stack traces in crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
