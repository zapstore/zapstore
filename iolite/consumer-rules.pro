# JNI libraries Iolite loads by class / native name.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class fr.acinq.secp256k1.** { *; }
# Native GetFieldID("srcPos"|"dstPos", "J") on the stream classes.
-keep class com.github.luben.zstd.** { *; }
