# Navigation type-safe routes are @Serializable classes resolved by name.
-keep @kotlinx.serialization.Serializable class com.rshop.navigation.** { *; }

# commons-compress optional codecs we do not ship (zstd); the formats we use never load them.
-dontwarn com.github.luben.zstd.**

# 7-Zip-JBinding: the native library calls back into these classes by name.
-keep class net.sf.sevenzipjbinding.** { *; }
-dontwarn net.sf.sevenzipjbinding.**

# SFTP (JSch) loads its algorithms by class name from a table; its optional dependencies are absent.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn org.ietf.jgss.**
