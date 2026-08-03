package de.robv.android.xposed;
public final class XposedBridge {
    public static final String TAG = "XposedBridge";
    public static boolean disableHooks = false;
    public static void hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {}
    public static void hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {}
    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member hookMethod, XC_MethodHook callback) { return null; }
}
