package de.robv.android.xposed.callbacks;
public abstract class XC_LoadPackage extends XCallback {
    public XC_LoadPackage() { super(); }
    public XC_LoadPackage(int priority) { super(priority); }
    protected abstract void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
    public static class LoadPackageParam extends XCallback.Param {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
