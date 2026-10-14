# JSch and Bouncy Castle load their algorithm classes by name.
-keep class com.jcraft.jsch.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.sun.jna.**
-dontwarn org.newsclub.net.unix.**
-dontwarn javax.naming.**
# TerminalSession's JNI and the reflective FileDescriptor field.
-keep class com.termux.terminal.JNI { *; }
# Compile-time-only annotations referenced by Tink (security-crypto).
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
