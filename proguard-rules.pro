# JNI: Rust looks up ArtiNative by class name and calls ArtiLogCallback.onLogLine.
-keep class dev.zapstore.app.transport.ArtiNative { native <methods>; }
-keep class dev.zapstore.app.transport.ArtiLogCallback { void onLogLine(java.lang.String); }

# secp256k1 / zstd / sqlite JNI entry points (also covered by their AARs).
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
# Native GetFieldID("srcPos"|"dstPos", "J") — keeping native methods is not enough.
-keep class com.github.luben.zstd.** { *; }
-keep class ai.onnxruntime.** { *; }
