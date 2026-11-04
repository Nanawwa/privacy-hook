# Privacy Hook

LSPosed module that stops two HyperOS system components from phoning home: the
"system security component" (`com.miui.guardprovider`, the anti-fraud / AI call
guard stack behind `flash.sec.miui.com`, `*.engine.miui.com` and screen
recording) and the carrier device-registration helper
(`com.mobiletools.systemhelper`, which reports IMEI/IMSI/ICCID, cell info, app
usage and traffic to `dm.wo.com.cn`).

Tested on Xiaomi 15 Ultra, HyperOS 4.0.0.16 (Android 17), KernelSU + Zygisk
LSPosed 2.2.1.

## What it does

- Hooks `URL.openConnection` / `openStream` and `InetAddress.getByName` /
  `getAllByName` inside the two scoped processes only. Nothing else on the
  device is touched.
- Logs every egress attempt to logcat under tag `PrivacyHook`, so new or
  relocated endpoints show up immediately.
- Replaces `MediaProjectionManager.getMediaProjection` with a hard
  `SecurityException`, sealing the AI call guard screen-recording path.
- Two switchable modes, no reboot needed:

| mode | behavior |
|---|---|
| `strict` (default) | block **all** network egress from the two processes |
| `blacklist` | block only fraud/telemetry hosts (`*.engine.miui.com`, `*.sec.miui.com`, `*.wo.com.cn`, path keywords like `detect/app`); virus engine and manager API stay reachable |

## Build

No Gradle. Plain `javac` + `d8` + `aapt2` from Android build-tools:

```bash
# compile against stubs (not packaged; LSPosed provides the real classes at runtime)
javac -source 17 -target 17 -encoding UTF-8 \
  -classpath 'build/stubs.jar;<android.jar>' \
  -d build/obj src/io/github/lteyo/privacyhook/*.java

# stubs.jar is built from mod/stubs once:
#   javac stubs/de/robv/android/xposed/*.java stubs/.../callbacks/*.java -d build/obj
#   jar cf build/stubs.jar -C build/obj de

# dex, package resources, add dex, zipalign, sign
d8 --release --min-api 31 --lib <android.jar> --output build/dex build/obj/io/github/lteyo/privacyhook/*.class
aapt2 compile --dir res -o build/res.zip
aapt2 link -o build/base.apk -I <android.jar> --manifest AndroidManifest.xml -A assets build/res.zip
# then add build/dex/classes.dex into base.apk, zipalign, apksigner sign
```

## Install

1. Build or grab `PrivacyHook.apk`, install it.
2. Enable the module in LSPosed; scope is
   `com.miui.guardprovider` + `com.mobiletools.systemhelper` (declared in the
   manifest, confirm both are checked).
3. Optionally disable the carrier helper outright (its egress is already
   blocked by the hook; this removes the process entirely):

   ```bash
   adb shell su -c 'pm disable-user --user 0 com.mobiletools.systemhelper'
   # revert any time:
   adb shell su -c 'pm enable com.mobiletools.systemhelper'
   ```
4. Kill the target processes or reboot; the module loads on next spawn:
   ```bash
   adb shell su -c 'killall com.miui.guardprovider com.miui.guardprovider:ai'
   ```

## Switching modes

Open the **Privacy Hook** launcher icon (buttons write
`/data/local/tmp/privacyhook.mode` via `su`; grant root on first use), or:

```bash
adb shell su -c 'echo strict > /data/local/tmp/privacyhook.mode'     # block all
adb shell su -c 'echo blacklist > /data/local/tmp/privacyhook.mode'  # fraud/telemetry only
```

The hooked processes re-read the file every 30 seconds, so changes apply
without a reboot.

## Auditing

```bash
adb shell logcat | grep PrivacyHook
```

Every URL the components try to reach is logged, whatever the mode. If an OTA
introduces a new endpoint you will see it here instead of missing it.

## Notes

- Blocks only Java-layer egress. Native sockets would bypass it, though no
  native reporting endpoints were found in the target libraries.
- HyperOS OTA may replace these components; re-check that the module is still
  active after a major update (Zygisk can fail to load until updated).
- Uninstall the app to remove everything; no other system changes are made.
