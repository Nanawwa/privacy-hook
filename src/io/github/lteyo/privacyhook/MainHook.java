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

    private static final String MODE_STRICT = "strict";

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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        boolean guard = PKG_GUARD.equals(lpp.packageName);
        if (!guard && !PKG_HELPER.equals(lpp.packageName)) return;

        String who = guard ? "guard" : "helper";
        hookNetwork(who);
        if (guard) hookProjection();
        Log.w("loaded into " + lpp.packageName + "/" + lpp.processName
                + " mode=" + Mode.current());
    }

    /** Registers egress hooks on URL and DNS resolution within the hooked process. */
    private static void hookNetwork(final String who) {
        XC_MethodHook urlHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String url = urlToString(param.thisObject);
                if (url == null) return;
                XposedBridge.log("[" + TAG + "][" + who + "] " + url);
                if (Mode.shouldBlock(url)) {
                    Log.w("[" + who + "] BLOCK " + url);
                    param.setThrowable(new java.io.IOException(
                            "PrivacyHook: egress blocked (" + hostOf(url) + ")"));
                }
            }
        };
        try {
            XposedBridge.hookAllMethods(URL.class, "openConnection", urlHook);
            XposedBridge.hookAllMethods(URL.class, "openStream", urlHook);
        } catch (Throwable t) {
            Log.w("hook URL failed: " + t);
        }

        try {
            XC_MethodHook dnsHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length < 1 || param.args[0] == null) return;
                    String host = String.valueOf(param.args[0]);
                    if (Mode.shouldBlockHost(host)) {
                        Log.w("[" + who + "] BLOCK-DNS " + host);
                        param.setThrowable(new UnknownHostException("PrivacyHook: " + host));
                    }
                }
            };
            XposedBridge.hookAllMethods(java.net.InetAddress.class, "getByName", dnsHook);
            XposedBridge.hookAllMethods(java.net.InetAddress.class, "getAllByName", dnsHook);
        } catch (Throwable t) {
            Log.w("hook InetAddress failed: " + t);
        }
    }

    /** Fails every screen-capture request, sealing the AI call guard recording path. */
    private static void hookProjection() {
        try {
            Class<?> mpm = XposedHelpers.findClass(
                    "android.media.projection.MediaProjectionManager", null);
            XposedBridge.hookAllMethods(mpm, "getMediaProjection", new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(XC_MethodHook.MethodHookParam param) {
                    Log.w("[guard] BLOCK getMediaProjection (AI-call-guard recording)");
                    throw new SecurityException("PrivacyHook: screen capture disabled");
                }
            });
        } catch (Throwable t) {
            Log.w("hook MediaProjectionManager failed: " + t);
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

    /** Shared decision logic and the mode file; both the hook and the switch app use it. */
    static final class Mode {
        static final String PATH = "/data/local/tmp/privacyhook.mode";
        static final String BLACKLIST = "blacklist";

        private static final long REFRESH_MS = 30_000;

        private static volatile long sReadAt;
        private static volatile boolean sStrict = true;

        static String current() {
            return isStrict() ? MODE_STRICT : BLACKLIST;
        }

        static boolean isStrict() {
            long now = System.currentTimeMillis();
            if (now - sReadAt > REFRESH_MS) {
                sReadAt = now;
                try {
                    String s = new String(java.nio.file.Files.readAllBytes(new File(PATH).toPath()))
                            .trim().toLowerCase();
                    sStrict = !BLACKLIST.equals(s);
                } catch (Throwable ignored) {
                    sStrict = true;
                }
            }
            return sStrict;
        }

        static boolean shouldBlock(String url) {
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

        static boolean shouldBlockHost(String host) {
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
    }

    /** Doubles writes to Xposed log and logcat so audits survive without a debugger. */
    static final class Log {
        static void w(String msg) {
            XposedBridge.log("[" + TAG + "] " + msg);
            android.util.Log.w(TAG, msg);
        }
    }
}
