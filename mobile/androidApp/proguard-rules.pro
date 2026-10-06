# Ktor references optional logging backends that are not on the classpath.
-dontwarn org.slf4j.**
# kotlinx.serialization ships its own R8 rules; app-specific rules go below.
# Ktor's debug detector references JVM management APIs that don't exist on Android.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
