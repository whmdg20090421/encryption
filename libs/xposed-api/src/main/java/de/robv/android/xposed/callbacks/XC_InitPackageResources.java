package de.robv.android.xposed.callbacks;
public abstract class XC_InitPackageResources extends XCallback {
    public XC_InitPackageResources() { super(); }
    public XC_InitPackageResources(int priority) { super(priority); }
    protected abstract void handleInitPackageResources(InitPackageResourcesParam resparam) throws Throwable;
    public static class InitPackageResourcesParam extends XCallback.Param {
        public String packageName;
        public android.content.res.Resources res;
    }
}
