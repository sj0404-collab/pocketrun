# QuickJS reaches its bindings reflectively from native code, so the classes the
# engine resolves by name have to survive shrinking. (The AAR ships these in a
# proguard.txt that AGP does not always pick up, so they are repeated here.)
-keep,allowoptimization class com.dokar.quickjs.QuickJs { *; }
-keep,allowoptimization class com.dokar.quickjs.QuickJsException { *; }
-keep,allowoptimization class com.dokar.quickjs.binding.JsProperty { *; }
-keep,allowoptimization class com.dokar.quickjs.binding.JsFunction { *; }
-keep,allowoptimization class com.dokar.quickjs.binding.JsObject { *; }
-keep,allowoptimization class kotlin.UByteArray

-keep class com.chaquo.python.** { *; }
-keep class com.chaquo.python.android.AndroidPlatform { *; }

-keep class net.i2p.crypto.eddsa.** { *; }
-dontwarn net.i2p.crypto.eddsa.**

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
