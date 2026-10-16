# Framework mirrors, Binder proxies, and JNI callbacks are discovered by name.
# These rules apply only to the opt-in experimental application.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.tegufy.blackbox.** { *; }
-keep class top.niunaijun.blackreflection.** { *; }
-keep class black.** { *; }
-keep class android.** { *; }
-keep class com.android.** { *; }
-keep class me.weishu.reflection.** { *; }
