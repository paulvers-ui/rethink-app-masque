# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
# commenting below, webview removed from version v053i
#-keepclassmembers class com.arcadesignpro.auroravpn.ui.DnsConfigureWebViewActivity$JSInterface {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

#Dont obfuscate
-dontobfuscate
-printmapping obfuscation/mapping.txt
-printmapping build/outputs/mapping/release/mapping.txt

# https://github.com/celzero/rethink-app/issues/875
# Keep generic signature of Call, Response (R8 full mode strips signatures from non-kept items).
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# With R8 full mode generic signatures are stripped for classes that are not
# kept. Suspend functions are wrapped in continuations where the type argument
# is used.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Retain generic signatures of TypeToken and its subclasses with R8 version 3.0 and higher.
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# JSR 305 annotations are for embedding nullability information.
-dontwarn javax.annotation.**

# A resource is loaded with a relative path so the package of this class must be preserved.
# ref: github.com/square/okhttp/issues/8154#issuecomment-1868462895
# issue: https://github.com/celzero/rethink-app/issues/1495
# square.github.io/okhttp/features/r8_proguard/
-keeppackagenames okhttp3.internal.publicsuffix.*
-adaptresourcefilenames okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz

# Animal Sniffer compileOnly dependency to ensure APIs are compatible with older versions of Java.
-dontwarn org.codehaus.mojo.animal_sniffer.*

# OkHttp platform used only on JVM and when Conscrypt and other security providers are available.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Keep Gson classes and attributes for JSON serialization/deserialization
# FileTag class uses Gson with custom deserializer (FileTagDeserializer) to handle
# dynamic JSON formats where "url" field can be either a string or JsonArray
# Without these rules, obfuscation would break the reflection-based JSON parsing
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.arcadesignpro.auroravpn.data.FileTag { *; }

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ============================================================================
# gomobile / firestack JNI boundary -- DO NOT REMOVE
# ============================================================================
# Root cause this fixes: release APKs crashed with
#   F go/Seq  : Unknown reference: 42
#   F libc    : Fatal signal 6 (SIGABRT)
#   backtrace: abort() -> go_seq_from_refnum -> cproxyintra_Bridge_Flow
# while debug APKs built from the identical commit never crashed. That
# debug-vs-release split is the whole diagnosis: it rules out application
# logic (identical in both) and points at the build pipeline.
#
# The only substantive difference between those two builds is R8.
# `minifyEnabled true` applies to the release buildType only, and
# gradle.properties sets `android.enableR8.fullMode=true`. R8 full mode
# shrinks and optimizes based on statically-reachable usage -- and it CANNOT
# see usage that originates in native code. Every call from firestack's Go
# runtime into Kotlin (Bridge.flow/inflow/preflow, Listener, Controller,
# Console, plus the DNSOpts/Mark/PreMark value objects those callbacks
# construct and return) crosses gomobile's generated JNI glue, which resolves
# classes, methods and fields dynamically at runtime. To R8's static analysis
# they look unreferenced, so it is free to strip or optimize them -- leaving
# Go's seq reference table pointing at something that no longer exists on the
# Java side. That is precisely what "Unknown reference: N" reports
# immediately before gomobile calls abort().
#
# Note: -dontobfuscate (above) prevents RENAMING but not SHRINKING or
# OPTIMIZATION, which is why having obfuscation off did not already protect
# this boundary.
#
# Keep the whole firestack surface (both directions of the boundary):
-keep class com.celzero.firestack.** { *; }
-keep interface com.celzero.firestack.** { *; }
-dontwarn com.celzero.firestack.**

# Keep every gomobile Seq internal. gomobile's reference table, proxy classes
# and native-method declarations live here and are reached only from native
# code.
-keep class go.** { *; }
-keep interface go.** { *; }
-dontwarn go.**

# Keep our own implementations of those firestack interfaces. BraveVPNService
# implements Bridge; its callback methods are invoked exclusively from Go, so
# nothing in Kotlin statically "calls" them and R8 would otherwise be free to
# remove or alter them.
-keep class com.arcadesignpro.auroravpn.service.BraveVPNService { *; }
-keep class com.arcadesignpro.auroravpn.net.go.GoVpnAdapter { *; }

# Any class implementing a firestack interface, wherever it lives.
-keep class * implements com.celzero.firestack.backend.Bridge { *; }
-keep class * implements com.celzero.firestack.backend.Listener { *; }
-keep class * implements com.celzero.firestack.backend.Controller { *; }
-keep class * implements com.celzero.firestack.backend.Console { *; }

# Native-method declarations and their declaring classes must survive intact:
# the JNI symbol name is derived from the class name + method name, so
# altering either breaks the native linkage silently at runtime.
-keepclasseswithmembernames class * {
    native <methods>;
}
