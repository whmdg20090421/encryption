# ── R8 代码裁剪（不混淆） ──
-dontobfuscate

# ── JNI native methods ──
-keepclasseswithmembernames class com.whmdg.mczj.tools.auth.NativeAuth {
    native <methods>;
}
-keep class com.whmdg.mczj.tools.auth.NativeAuth { *; }

# ── Feature 枚举名稳定 (HMAC payload 使用 enum.name) ──
-keepclassmembers enum com.whmdg.mczj.tools.auth.Feature { *; }
-keep class com.whmdg.mczj.tools.auth.Feature { *; }

# ── PermissionManager 单例 + AuthState ──
-keep class com.whmdg.mczj.tools.auth.PermissionManager { *; }
-keep class com.whmdg.mczj.tools.auth.PermissionManager$AuthState* { *; }

# ── kotlinx.serialization ──
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class * { *; }

# ── Xposed 模块入口（运行时反射调用） ──
-keep class com.whmdg.mczj.tools.xposed.模块入口 { *; }
-keep class com.whmdg.mczj.tools.xposed.** { *; }

# ── WebView JS interface ──
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── 运行时反射的类 ──
-keep class com.whmdg.mczj.tools.encryption.data.** { *; }
-keep class com.whmdg.mczj.tools.fileop.sync.** { *; }

# ── Xposed / YukiHookAPI / libxposed（运行时才存在的类） ──
-dontwarn android.app.AndroidAppHelper
-dontwarn android.content.res.XResources
-dontwarn android.content.res.XModuleResources
-dontwarn android.content.res.XResForwarder
-dontwarn com.highcapable.yukihookapi.**
-dontwarn com.highcapable.kavaref.**
-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.**
-dontwarn io.github.rosemoe.**
-dontwarn org.lsposed.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn kotlin.Cloneable*

# ── BouncyCastle（保留加密算法，裁剪未使用的） ──
-dontwarn org.bouncycastle.**

# ── Shizuku ──
-dontwarn moe.shizuku.**
