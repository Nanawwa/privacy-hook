# Privacy Hook

一个 LSPosed 模块，拦住 HyperOS 两个系统组件的对外数据上报：

- **系统安全组件**（`com.miui.guardprovider`）：反诈和 AI 通话预警的载体，会向 `flash.sec.miui.com/detect/app` 上报应用清单，向 `detection-aigc.engine.miui.com` 上传通话录音和视频片段做云端检测，还有一个完整的录屏链路（MediaProjection → 临时 mp4 → 加密上传）。
- **SystemHelper**（`com.mobiletools.systemhelper`）：SIM 激活相关组件，向联通 `dm.wo.com.cn` 上报设备档案，载荷里包含 IMEI/IMSI/ICCID、基站信息、应用使用统计和流量。

实测环境：小米 15 Ultra，HyperOS 4.0.0.16 国行，KernelSU + Zygisk LSPosed 2.2.1。

## 它做什么

- 只在勾选的两个进程内 hook `URL.openConnection` / `openStream` 和 `InetAddress.getByName` / `getAllByName`，设备上其他任何应用、任何网络请求都不受影响。
- 每一条出网尝试都写进 logcat（tag `PrivacyHook`），组件换了新端点会立刻出现在日志里。
- `MediaProjectionManager.getMediaProjection` 直接替换为抛 SecurityException，录屏链路从源头封死。
- 两档模式，切换不用重启：

| 模式 | 行为 |
|---|---|
| `strict`（默认） | 这两个进程的所有出网一律拒绝 |
| `blacklist` | 只拦反诈/上报域名（`*.engine.miui.com`、`*.sec.miui.com`、`*.wo.com.cn`，以及 `detect/app` 这类路径关键字）；病毒引擎和管家 API 保持可用 |

## 构建

没有用 Gradle，就是 build-tools 里的几个命令：

```bash
# 先把 stubs 编译成 build/stubs.jar（只是编译期引用，不打进 APK，运行时由 LSPosed 提供真身）
javac -source 17 -target 17 -encoding UTF-8 -d build/obj \
  stubs/de/robv/android/xposed/*.java stubs/de/robv/android/xposed/callbacks/*.java
jar cf build/stubs.jar -C build/obj de

# 编译模块源码（<android.jar> 换成你 SDK 里的路径）
javac -source 17 -target 17 -encoding UTF-8 \
  -classpath 'build/stubs.jar;<android.jar>' \
  -d build/obj src/io/github/lteyo/privacyhook/*.java

# dex、打包资源、塞 dex、对齐、签名
d8 --release --min-api 31 --lib <android.jar> --output build/dex build/obj/io/github/lteyo/privacyhook/*.class
aapt2 compile --dir res -o build/res.zip
aapt2 link -o build/base.apk -I <android.jar> --manifest AndroidManifest.xml -A assets build/res.zip
# 把 build/dex/classes.dex 加进 base.apk，然后 zipalign、apksigner sign
```

不想编译的话，[Release](https://github.com/Nanawwa/privacy-hook/releases) 里有构建好的 APK。

## 安装

1. 安装 APK，在 LSPosed 里启用模块。作用域声明为 `com.miui.guardprovider` 和 `com.mobiletools.systemhelper`，确认两个都勾上。
2. SystemHelper 可以顺手整个禁掉（hook 已经断网，这步是让进程彻底不跑，可随时恢复）：

   ```bash
   adb shell su -c 'pm disable-user --user 0 com.mobiletools.systemhelper'
   # 恢复：
   adb shell su -c 'pm enable com.mobiletools.systemhelper'
   ```
3. 杀掉目标进程或重启，下次拉起时模块就会加载：

   ```bash
   adb shell su -c 'killall com.miui.guardprovider com.miui.guardprovider:ai'
   ```

## 切换模式

桌面的 **Privacy Hook** 图标点开就是开关（写 `/data/local/tmp/privacyhook.mode`，第一次会要 root 授权），或者命令行：

```bash
adb shell su -c 'echo strict > /data/local/tmp/privacyhook.mode'     # 全拦
adb shell su -c 'echo blacklist > /data/local/tmp/privacyhook.mode'  # 只拦反诈/上报
```

进程每 30 秒读一次开关文件，改完等半分钟就生效，也可以手动 killall 立即生效。

## 看日志

```bash
adb shell logcat | grep PrivacyHook
```

不管哪个模式，组件想访问的每个 URL 都在日志里。OTA 之后怀疑组件换了端点，看这里就行。

## 已知局限

- 只拦 Java 层出网。目标库里有 native socket 的可能绕过，不过没有在 so 里找到上报用的端点字符串。
- 大版本 OTA 后建议去 LSPosed 管理器确认模块还活着（Zygisk 可能暂时失配）。
- 卸载 APK 即完全移除，不对系统做任何其他改动。
