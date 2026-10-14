-dontwarn org.mozilla.javascript.**
-keep class org.mozilla.javascript.** { *; }

-keep class com.chaquo.python.** { *; }
-keep class com.chaquo.python.android.AndroidPlatform { *; }

-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
