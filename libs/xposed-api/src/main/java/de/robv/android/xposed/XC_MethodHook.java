package de.robv.android.xposed;
public abstract class XC_MethodHook {
    public XC_MethodHook() {}
    public XC_MethodHook(int priority) {}
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        public Object getResult() { return result; }
        public void setResult(Object result) { this.result = result; }
        public Throwable getThrowable() { return throwable; }
        public boolean hasThrowable() { return throwable != null; }
        public java.lang.reflect.Method getMethod() { return null; }
    }
    public static class Unhook {
        public java.lang.reflect.Member getHookedMethod() { return null; }
        public void unhook() {}
    }
}
