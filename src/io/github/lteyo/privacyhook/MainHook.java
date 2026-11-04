package io.github.lteyo.privacyhook;

import java.io.File;
import java.net.URL;
import java.net.UnknownHostException;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "PrivacyHook";

    private static final String PKG_GUARD = "com.miui.guardprovider";
    private static final String PKG_HELPER = "com.mobiletools.systemhelper";

    /** Mode file: "strict" (default) blocks all egress, "blacklist" only blocks fraud/telemetry hosts. */
    private static final String MODE_FILE = "/data/local/tmp/privacyhook.mode";

    private static final String MODE_BLACKLIST = "blacklist";

    // Exact hosts blocked in blacklist mode.
    private static final String[] BLOCK_HOSTS = {
            "dm.wo.com.cn",
            "flash.sec.miui.com",
    };

    // Suffix rules in blacklist mode; cover any subdomain under these.
    private static final String[] BLOCK_SUFFIXES = {
            ".engine.miui.com",
            ".wo.com.cn",
            ".sec.miui.com",
    };

    // Prefix rules in blacklist mode: virus engine and manager API stay reachable.
    private static final String[] WHITELIST_PREFIXES = {
            "https://virus-engine.sec.miui.com",
            "https://api.sec.miui.com/antivirus",
    };

    // Path keywords in blacklist mode: block even if the host changes, as long as the endpoint stays.
    private static final String[] BLOCK_PATH_KEYWORDS = {
            "/fraud", "fraud/app", "detect/app", "antifraud",
    };

    private static final long MODE_REFRESH_MS = 30_000;

    private static volatile long sModeReadAt = 0;
    private static volatile boolean sStrict = true;

    private static boolean isStrict() {
        long now = System.currentTimeMillis();
        if (now - sModeReadAt > MODE_REFRESH_MS) {
            sModeReadAt = now;
            try {
                String s = new String(java.nio.file.Files.readAllBytes(new File(MODE_FILE).toPath()))
                        .trim().toLowerCase();
                sStrict = !MODE_BLACKLIST.equals(s);
            } catch (Throwable ignored) {
                sStrict = true;
            }
        }
        return sStrict;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (PKG_GUARD.equals(lpp.packageName)) {
            hookNetwork(lpp, "guard");
            hookProjection(lpp);
            logMode(lpp, "guard");
        } else if (PKG_HELPER.equals(lpp.packageName)) {
            hookNetwork(lpp, "helper");
            logMode(lpp, "helper");
        }
    }

    private static void logMode(XC_LoadPackage.LoadPackageParam lpp, String who) {
        logW(TAG, "loaded into " + lpp.packageName + "/" + lpp.processName
                + " mode=" + (isStrict() ? "strict" : MODE_BLACKLIST));
    }

    /** Registers egress hooks on URL and DNS resolution within the hooked process. */
    private static void hookNetwork(XC_LoadPackage.LoadPackageParam lpp, final String who) {
        XC_MethodHook urlHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String url = urlToString(param.thisObject);
                if (url == null) return;
                XposedBridge.log("[" + TAG + "][" + who + "] " + url);
                if (shouldBlock(url)) {
                    logW(TAG, "[" + who + "] BLOCK " + url);
                    param.setThrowable(new java.io.IOException("PrivacyHook: egress blocked (" + hostOf(url) + ")"));
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(URL.class, "openConnection", urlHook);
            XposedBridge.hookAllMethods(URL.class, "openStream", urlHook);
        } catch (Throwable t) {
            logW(TAG, "hook URL failed: " + t);
        }

        try {
            XC_MethodHook dnsHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1 || param.args[0] == null) return;
                    String host = String.valueOf(param.args[0]);
                    if (shouldBlockHost(host)) {
                        logW(TAG, "[" + who + "] BLOCK-DNS " + host);
                        param.setThrowable(new UnknownHostException("PrivacyHook: " + host));
                    }
                }
            };
            XposedBridge.hookAllMethods(java.net.InetAddress.class, "getByName", dnsHook);
            XposedBridge.hookAllMethods(java.net.InetAddress.class, "getAllByName", dnsHook);
        } catch (Throwable t) {
            logW(TAG, "hook InetAddress failed: " + t);
        }
    }

    /** Fails every screen-capture request, sealing the AI call guard recording path. */
    private static void hookProjection(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            Class<?> mpm = XposedHelpers.findClass(
                    "android.media.projection.MediaProjectionManager", lpp.classLoader);
            XposedBridge.hookAllMethods(mpm, "getMediaProjection", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(XC_MethodHook.MethodHookParam param) {
                    logW(TAG, "[guard] BLOCK getMediaProjection (AI-call-guard recording)");
                    throw new SecurityException("PrivacyHook: screen capture disabled");
                }
            });
        } catch (Throwable t) {
            logW(TAG, "hook MediaProjectionManager failed: " + t);
        }
    }

    private static String urlToString(Object url) {
        if (!(url instanceof URL)) return null;
        return ((URL) url).toExternalForm();
    }

    private static String hostOf(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    private static boolean shouldBlock(String url) {
        if (isStrict()) return true;
        String lower = url.toLowerCase();
        for (String w : WHITELIST_PREFIXES) {
            if (lower.startsWith(w)) return false;
        }
        for (String kw : BLOCK_PATH_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return shouldBlockHost(hostOf(url));
    }

    private static boolean shouldBlockHost(String host) {
        if (isStrict()) return true;
        if (host == null) return false;
        String h = host.toLowerCase();
        for (String b : BLOCK_HOSTS) {
            if (h.equals(b)) return true;
        }
        for (String s : BLOCK_SUFFIXES) {
            if (h.endsWith(s)) return true;
        }
        return false;
    }

    private static void logW(String tag, String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
        android.util.Log.w(tag, msg);
    }
}
