# ══════════════════════════════════════════════════════════════
# R8 压缩编译规则：允许代码裁剪，精确保留需要保护的类
# ══════════════════════════════════════════════════════════════

-dontobfuscate

# ── JNI native 方法（NativeAuth 被 authcore.so 调用） ──
-keepclasseswithmembernames class com.whmdg.mczj.tools.auth.NativeAuth {
    native <methods>;
}
-keep class com.whmdg.mczj.tools.auth.NativeAuth { *; }

# ── Feature 枚举（enum.name 用于 HMAC payload） ──
-keepclassmembers enum com.whmdg.mczj.tools.auth.Feature { *; }
-keep class com.whmdg.mczj.tools.auth.Feature { *; }

# ── PermissionManager 单例 + AuthState ──
-keep class com.whmdg.mczj.tools.auth.PermissionManager { *; }
-keep class com.whmdg.mczj.tools.auth.PermissionManager$AuthState* { *; }

# ── kotlinx.serialization（反射创建实例） ──
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class * { *; }
-keep class kotlinx.serialization.** { *; }

# ── Xposed / YukiHookAPI / libxposed（运行时框架加载） ──
-keep class com.whmdg.mczj.tools.xposed.** { *; }
-keep class com.highcapable.yukihookapi.** { *; }
-keep class com.highcapable.kavaref.** { *; }
-keep class de.robv.android.xposed.** { *; }
-keep class io.github.libxposed.** { *; }
-dontwarn android.app.AndroidAppHelper
-dontwarn android.content.res.XResources
-dontwarn android.content.res.XModuleResources
-dontwarn android.content.res.XResForwarder
-dontwarn com.highcapable.yukihookapi.**
-dontwarn com.highcapable.kavaref.**
-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.**
-dontwarn org.lsposed.**

# ── BouncyCastle（密码学库，部分类通过反射加载） ──
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── Shizuku（IPC 框架） ──
-keep class moe.shizuku.** { *; }
-dontwarn moe.shizuku.**

# ── WebView JS 接口 ──
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ── Material Icons（通过反射加载） ──
-keep class androidx.compose.material.icons.** { *; }

# ── Sora Editor ──
-keep class io.github.rosemoe.** { *; }
-dontwarn io.github.rosemoe.**
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn kotlin.Cloneable*

# ── Coil 图片加载 ──
-keep class coil3.** { *; }

# ── OkHttp / WebDAV ──
-keep class okhttp3.** { *; }
-keep class at.bitfire.dav4jvm.** { *; }
