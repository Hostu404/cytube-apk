# R8 is now on for release builds (see app/build.gradle.kts). These rules are
# deliberately conservative — there is no automated test suite here to catch
# a method R8 stripped that only mattered at runtime, so every dependency
# that leans on reflection is kept whole rather than trimmed.

# ---- CyTube protocol / Socket.IO ----
-keep class io.socket.** { *; }
-dontwarn io.socket.**

# ---- OkHttp / okio ----
# Both ship their own consumer rules inside their AARs, which AGP merges in
# automatically — these are kept explicit anyway since this is the first
# time R8 has run on this project at all.
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ---- NewPipeExtractor (YouTube resolving) ----
# Pulls in Mozilla Rhino (evaluates YouTube's cipher JS) and nanojson, both
# reflection-heavy. Keeping the whole tree costs some APK size but a broken
# YouTube extraction from an over-eager strip would be silent until someone
# hit it in the field.
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keep class com.grack.nanojson.** { *; }
-dontwarn com.grack.nanojson.**

# ---- Jsoup (chat/MOTD/poll HTML handling) ----
-dontwarn org.jsoup.**

# ---- Kotlin / coroutines metadata ----
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*, Exceptions
-keepclassmembers class kotlin.Metadata { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
