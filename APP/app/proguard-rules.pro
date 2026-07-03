# ── 全局保留：不混淆、不裁剪，仅配合 shrinkResources 移除未使用资源 ──
-dontobfuscate
-keep class ** { *; }
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
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── Shizuku ──
-keep class moe.shizuku.** { *; }
-dontwarn moe.shizuku.**

# ── WebView JS interface ──
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
