# Navigation type-safe routes are @Serializable classes resolved by name.
-keep @kotlinx.serialization.Serializable class com.rshop.navigation.** { *; }

# commons-compress optional codecs we do not ship (zstd); the formats we use never load them.
-dontwarn com.github.luben.zstd.**

# 7-Zip-JBinding: the native library calls back into these classes by name.
-keep class net.sf.sevenzipjbinding.** { *; }
-dontwarn net.sf.sevenzipjbinding.**
