# Full builds can load Android CloudStream .cs3 packages dynamically.
# Keep shrinking/obfuscation enabled, but skip R8 optimization passes that become
# prohibitively slow with the CloudStream compatibility runtime dependency graph.
-dontoptimize

# Standard CloudStream .cs3 packages link against these names at runtime.
# They must remain binary-stable and unobfuscated in Android full release builds.
-keep class com.lagradost.** { *; }
-keep interface com.lagradost.** { *; }
-keep class com.nuvio.app.features.cloudstream.** { *; }
-keep interface com.nuvio.app.features.cloudstream.** { *; }

-keep class com.fasterxml.jackson.** { *; }
-keep interface com.fasterxml.jackson.** { *; }
-keep class org.jsoup.** { *; }
-keep interface org.jsoup.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep interface org.mozilla.javascript.** { *; }
-keep class com.uwetrottmann.tmdb2.** { *; }
-keep interface com.uwetrottmann.tmdb2.** { *; }
-keep class me.xdrop.fuzzywuzzy.** { *; }
-keep interface me.xdrop.fuzzywuzzy.** { *; }
-keep class dev.whyoleg.cryptography.** { *; }
-keep interface dev.whyoleg.cryptography.** { *; }
-keep class org.schabi.newpipe.extractor.** { *; }
-keep interface org.schabi.newpipe.extractor.** { *; }
-keep class kotlinx.datetime.** { *; }
-keep interface kotlinx.datetime.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep interface kotlinx.serialization.** { *; }
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-keep interface okio.** { *; }

-keep,allowoptimization class kotlin.** { public protected *; }
-keep,allowoptimization class kotlinx.coroutines.** { public protected *; }

-dontwarn com.lagradost.**
-dontwarn com.nuvio.app.features.cloudstream.**
-dontwarn com.fasterxml.jackson.**
-dontwarn org.jsoup.**
-dontwarn org.mozilla.javascript.**
-dontwarn com.uwetrottmann.tmdb2.**
-dontwarn me.xdrop.fuzzywuzzy.**
-dontwarn dev.whyoleg.cryptography.**
-dontwarn org.schabi.newpipe.extractor.**
-dontwarn kotlinx.datetime.**
-dontwarn kotlinx.serialization.**
-dontwarn okhttp3.**
-dontwarn okio.**

# Rhino ships optional JVM integrations that are not present on Android.
# CloudStream providers use Rhino core APIs; these desktop/JDK integrations are not required there.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

