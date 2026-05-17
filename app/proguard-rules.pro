# Ktor & SLF4J rules
-keep class io.ktor.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn io.ktor.**
-dontwarn org.slf4j.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}