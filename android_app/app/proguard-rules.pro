# ##################################################################################
# Global Rules: Allow Shrinking, but prevent Obfuscation (Renaming)
# ##################################################################################

# These rules are key. They instruct ProGuard to keep the original names of all
# classes, interfaces, enums, fields, and methods.
# This effectively disables obfuscation while still allowing code shrinking to work.
-keepnames class * { *; }
-keepclassmembernames class * { *; }

# ##################################################################################
# Keep important metadata for libraries and debugging
# ##################################################################################

# Keep annotations that are needed at runtime (important for Dagger/Hilt, Room, etc.).
-keepattributes *Annotation*

# Keep important metadata for debugging and reflection (signatures, inner classes).
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes SourceFile,LineNumberTable

# ##################################################################################
# Specific rules for libraries (as a safety net)
# ##################################################################################

# Although the global -keepnames rules cover this, it doesn't hurt to have
# explicit rules for libraries that are known to use reflection.
# These rules also ensure that members are not removed if ProGuard
# incorrectly thinks they are unused.

# Netty and JCTools
-keep class io.netty.** { *; }
-keep class org.jctools.** { *; }

# Dagger / Hilt
-keep class dagger.hilt.internal.aggregatedroot.codegen.** { *; }
-keep class hilt_aggregated_deps.** { *; }

# Room DB
# Necessary so that Room can find and not remove the DAO implementations and Entities.
-keep class * implements androidx.room.Dao
-keep class * { @androidx.room.Entity *; }
-keep class * { @androidx.room.Database *; }

# Jetpack Compose
# Ensures that no @Composable functions or their containing classes are removed.
-keepclasseswithmembers public class * {
    @androidx.compose.runtime.Composable <methods>;
}

# Jetpack Glance (App Widgets)
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }

# Keep all classes that extend CoroutineWorker and BroadcastReceiver
# The standard rules from AndroidManifest usually handle this, but being explicit is safer.
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends android.content.BroadcastReceiver