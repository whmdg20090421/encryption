# ── 全局保留：不混淆、不裁剪，仅配合 shrinkResources 移除未使用资源 ──
# 例外：BouncyCastle 的 org.bouncycastle.pqc 包（后量子密码学）代码从未被引用，
# 显式排除以允许 R8 裁剪掉这部分无用代码（约 3.6MB，压缩后约 1.2MB）。
-dontobfuscate
-keep class !org.bouncycastle.pqc.**,** { *; }
-keepclassmembers class ** { *; }
-keepattributes *

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

# ── BouncyCastle ──
# 仅保留实际用到的 Argon2 密钥派生闭包；pqc（后量子）等未引用包允许 R8 裁剪。
-keep class !org.bouncycastle.pqc.**,org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── Shizuku ──
-keep class moe.shizuku.** { *; }
-dontwarn moe.shizuku.**

# ── WebView JS interface ──
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

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
