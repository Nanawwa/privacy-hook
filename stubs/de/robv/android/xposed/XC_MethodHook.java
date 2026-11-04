package de.robv.android.xposed;

/** Compile-only stub; not packaged into the APK. */
public abstract class XC_MethodHook {
    public XC_MethodHook() {}
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static abstract class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object getResult() { throw new UnsupportedOperationException(); }
        public void setResult(Object result) { throw new UnsupportedOperationException(); }
        public Throwable getThrowable() { throw new UnsupportedOperationException(); }
        public void setThrowable(Throwable throwable) { throw new UnsupportedOperationException(); }
        public boolean hasThrowable() { throw new UnsupportedOperationException(); }
    }
}
