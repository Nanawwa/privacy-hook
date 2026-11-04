package de.robv.android.xposed;

/** Compile-only stub; not packaged into the APK. */
public abstract class XC_MethodReplacement extends XC_MethodHook {
    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;
}
