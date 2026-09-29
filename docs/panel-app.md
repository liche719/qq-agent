# 手机 App：运维面板的一层壳（2026-09-29）

**一句话**：一个只有 15 KB 的 Android App，打开就直接进 `https://liche.cloud/`，
省得每次在手机浏览器里敲地址。真机（vivo V2362A / Android）已装并验证。

## 代码在哪

```
C:\Users\33721\Desktop\wechat-agent\panel-app\      ← 独立工程，**不在 java 仓库里**
    settings.gradle / build.gradle / gradle.properties
    app/src/main/AndroidManifest.xml
    app/src/main/java/cloud/liche/panel/MainActivity.java
    app/src/main/res/{values,mipmap-anydpi-v26,drawable}/
```

**为什么放在 java 仓库外面**：仓库里任何改动都会触发 `deploy-remote.yml`（除了 `paths-ignore`
覆盖的 `**.md`/`docs/**`）。这个壳和线上服务没有依赖关系，放进仓库等于每次改图标都白跑一次部署。
真要入库，得同时把 `panel-app/**` 加进 workflow 的 `paths-ignore`。

产物：`C:\Users\33721\Desktop\wechat-agent\liche-panel-1.0.apk`（16 KB，debug 自签）。

## 它做了什么

- 一个全屏 `WebView` 打开 `/`；开了 JS、DOM storage（面板的登录态与主题都存在网页存储里）。
- **本站**（`liche.cloud` 及其子域）的跳转留在壳里；**外链**（备案号跳的 `beian.miit.gov.cn` 等）
  交给系统浏览器——不然用户会被困在一个没有地址栏的窗口里。
- 返回键 = 网页后退，退到底才退出 App。
- 主文档加载失败时显示自制「重试」页（`onReceivedError` 只对 `isForMainFrame` 生效，
  图片/接口报错不该挡住整页）。
- 图标是**纯矢量**的自适应图标（`ic_launcher_background` / `_foreground` / `_monochrome` 三层，
  没有任何 png）：蓝色斜渐变底 + 白色对话气泡 + 蓝色「心跳」折线（机器人 + 运维监控），
  形状都收在 72dp 安全区里，圆形/方圆遮罩都不会切到。
  应用名 **QQ机器人运维**（`strings.xml` 的 `app_name`）。
  改完想看效果**不用装到手机**：`panel-app/tools/IconPreview.java` 是照同一套坐标用 Java2D
  渲预览的小工具（`java tools/IconPreview.java tools/icon-preview.png`），
  左边画圆形遮罩、右边画方圆遮罩，旁边那张 `icon-preview.png` 就是它的输出。

**刻意零第三方依赖**（连 AndroidX 都没有）：一个 WebView 壳用不上；而且零依赖才能在
"Google Maven 连不上、只有本地 Gradle 缓存"的机器上构建（下面有踩坑记录）。

## 怎么构建

```powershell
cd C:\Users\33721\Desktop\wechat-agent\panel-app
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat" assembleDebug
```

- Gradle 用本地缓存里的 **8.9**（`~/.gradle/wrapper/dists/`），AGP **8.7.3**（同样在缓存里）。
- `local.properties` 写死 `sdk.dir=C:\Android\Sdk`（compileSdk 35 / build-tools 35.0.0 都已装）。
- `build.gradle` 用**老式 buildscript classpath** 而不是 `plugins {}` 插件 DSL：
  插件 DSL 要先解析 `com.android.application.gradle.plugin` 这个标记坐标，而它**没进过本地缓存**。
- **仓库顺序是照这台机器的网络排的**：`maven.google.com` 在这里连不上，`repo1.maven.org` 和
  阿里云镜像通，所以阿里云放前面、官方放最后兜底。
- `--offline` 会在 `:classpath` 上失败（缺 `kotlin-stdlib`/`kotlin-reflect:1.9.20`）——
  第一次构建要**联网**从 Maven Central 补这两个包，补完以后就能离线了。

## 怎么装 / 怎么更新

无线调试（手机：设置 → 开发者选项 → 无线调试）：

```powershell
adb pair <IP>:<配对端口> <6位配对码>     # 弹窗要开着，码有时效，过期了端口会关
adb connect <IP>:<36155>                 # 36155 是弹窗上面那行的"IP 地址和端口"
adb install -r liche-panel-1.0.apk
```

数据线直接 `adb install -r` 也行。**签名是 debug key**（`~/.android/debug.keystore`）：
不能上架、换电脑重编会换签名（那时必须先卸载再装），个人自用足够。

## 验证记录（2026-09-29）

`adb install` 成功 → `am start` 后 `topResumedActivity=cloud.liche.panel/.MainActivity`、
进程存活、无 `AndroidRuntime` 崩溃、WebView provider 正常（`com.google.android.webview 138`）。
真机截图确认面板完整渲染：「应用 正常 / QQ 通道 正常 / 用户数 3 / 异常任务 0」，全屏无地址栏。

**坑（vivo 上截 WebView 全黑）**：`adb shell screencap -p /sdcard/x.png` 再 `pull` 出来的是
**一张纯黑图**（logcat 里刷 `GPUAUX GuiExtAuxCheckAuxPath: Null anb`，GPU AUX 图层读不出来）。
换 `cmd /c "adb exec-out screencap -p > x.png"`（cmd 的重定向是二进制安全的）就正常了。
**别因为截图全黑就以为 App 坏了**——用 `uiautomator dump` 看视图树更可靠：
错误页在场时会出现「打不开面板 / 重试」的 TextView。

## 没做的（有意）

- **没做 PWA**：其实加个 manifest + 图标就能让 Chrome「添加到主屏幕」得到同样的效果，
  还不用装 APK、自动更新。用户要的是 APK，所以先只做 APK；两个可以并存。
- 没做下拉刷新（要 AndroidX 的 SwipeRefreshLayout，就破了"零依赖"这条）。面板自己每 10 秒拉一次。
- 没做文件下载/上传（面板没有这些入口；真点了下载会把链接丢给系统浏览器）。
