package de.robv.android.xposed;

import java.util.Set;

/** Compile-only stub; not packaged into the APK. */
public final class XposedBridge {
    public static void log(String text) { throw new UnsupportedOperationException(); }
    public static void log(Throwable t) { throw new UnsupportedOperationException(); }
    public static Set<Object> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
        throw new UnsupportedOperationException();
    }
}
