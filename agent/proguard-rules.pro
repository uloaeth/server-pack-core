# Agent release hardening. The mod is not obfuscated; only the standalone agent
# jar is. Keep every entry point, resource lookup, manifest attribute and the
# JSON entry points, then shrink and rename the rest.

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-keepdirectories
-dontoptimize
-dontpreverify

-keep public class com.serverpackcore.agent.Agent { public static void main(java.lang.String[]); }
-keep public class com.serverpackcore.agent.Premain { public static void premain(java.lang.String, java.lang.instrument.Instrumentation); }
-keep public class com.serverpackcore.agent.Json { *; }
-keep public class com.serverpackcore.agent.Bootstrap { *; }
-keep public class com.serverpackcore.agent.BuildInfo { *; }
-keep public class com.serverpackcore.agent.Version { *; }

# Reflection-free but referenced by name in scripts/resources.
-keep class com.serverpackcore.agent.DiscordSecrets { *; }

# Keep enum-like switch helpers and field accessors that ProGuard can otherwise
# rename inconsistently across modules.
-keepclassmembers class com.serverpackcore.agent.** { *; }

# Control channel: the WebSocket listener subclasses and the frame helpers are
# referenced by the JDK client and by the protocol, not by call sites alone.
-keep class com.serverpackcore.agent.PanelSocket { *; }
-keepclassmembers class com.serverpackcore.agent.PanelSocket$* { *; }

# Manifest and signatures must survive.
-keep,allowshrinking class * implements java.io.Serializable { *; }

-renamesourcefileattribute SourceFile
