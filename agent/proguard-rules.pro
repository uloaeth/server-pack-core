# Agent release hardening. The mod is not obfuscated; only the standalone agent
# jar is. Keeps are per entry point; there is no blanket member keep.

-keepdirectories
-optimizationpasses 3
-allowaccessmodification
-overloadaggressively
-repackageclasses 's'

-keep public class com.spcagentpayloa.jvmxx.Agent { public static void main(java.lang.String[]); }
-keep public class com.spcagentpayloa.jvmxx.Premain { public static void premain(java.lang.String, java.lang.instrument.Instrumentation); }
-keep public class com.spcagentpayloa.jvmxx.Json { *; }
-keep public class com.spcagentpayloa.jvmxx.Bootstrap { *; }
-keep public class com.spcagentpayloa.jvmxx.BuildInfo { *; }
-keep public class com.spcagentpayloa.jvmxx.Version { *; }

# Reflection-free but referenced by name in scripts/resources.
-keep class com.spcagentpayloa.jvmxx.Q1 { *; }

# Control channel: the WebSocket listener subclasses and the frame helpers are
# referenced by the JDK client and by the protocol, not by call sites alone.
-keep class com.spcagentpayloa.jvmxx.PanelSocket { *; }
-keepclassmembers class com.spcagentpayloa.jvmxx.PanelSocket$* { *; }

# JNA interfaces are resolved by name when a module adds them.
-keep interface com.sun.jna.** { *; }
-keep class com.sun.jna.** { *; }

# Manifest and signatures must survive; Main-Class is rewritten by ProGuard.
-keep,allowshrinking class * implements java.io.Serializable { *; }

# Archived with the release so a production stack can be de-obfuscated.
-printmapping build/mapping.txt
-renamesourcefileattribute SourceFile
