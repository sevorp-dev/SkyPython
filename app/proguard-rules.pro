# R8 rules for SkyPython release builds.
# Compose, AndroidX, DataStore, coroutines and Gson ship their own consumer rules.

# Readable stack traces in crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Gson needs generic signatures and annotations of the models it fills by reflection.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod

# Sora Editor TextMate: grammars, themes and language configurations are parsed
# with Gson / reflection. The library's consumer rules only cover registry.model
# and languageconfiguration.internal.model; e.g. LanguageDefinitionReader's
# private LanguageDefinitionList (reading textmate/languages.json) is not covered.
-keep class io.github.rosemoe.sora.langs.textmate.registry.** { *; }
-keep class org.eclipse.tm4e.** { *; }

# joni (the regex engine tm4e uses): consumer rules keep only QuantifierNode;
# keep the whole engine, it is small and R8 has broken it before.
-keep class org.joni.** { *; }
# jcodings looks encodings up by class name (Encoding.load -> Class.forName).
-keep class org.jcodings.specific.*Encoding { *; }
-dontwarn org.jcodings.**
-dontwarn org.joni.**

# Saved crash reports (filesDir/crash/*.json) are read back with Gson, possibly by a newer
# build: keep the field and enum names stable.
-keepclassmembers class dev.skyloft.skypython.report.ProblemReport { <fields>; }
-keep enum dev.skyloft.skypython.report.ReportKind { *; }
