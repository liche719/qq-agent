# 运维面板前端验证工具

> **位置**：2026-09-14 起随仓库走（`wechat-agent-java/tools/ui-verify/`），**只提交脚本**——
> 跑出来的 `*.png` 截图不入库（`.gitignore` 里忽略），要留证据就自己另存。
> 凭据一律走环境变量（`WG_PW`/`ADMIN_USERNAME`），**不要写进脚本**。

用 Playwright 驱动真实 Chromium，对**真实部署**跑一遍：登录（含错误账号/密码）、各页签、聊天式记忆视图、记住账号密码、手机端适配与视觉规范断言。

## 一、验证线上部署（最常用）

```powershell
$env:WG_PW='<面板口令>'
$env:ADMIN_USERNAME='rootlcw'
$env:SPA_BASE='https://liche.cloud'
$env:SPA_TAG='v10'
& "D:\soft\JetBrains\Python\python\python.exe" "$PSScriptRoot\verify_spa.py"
```

- 面板现在跑在**标准 443 端口**，所以地址**不带端口号**。（2026-09-12 之前是 `:8443`，已切换。）
- 也可以打 `https://120.25.170.92`（IP 直连）：功能断言一样能过，但那条路径证书"名称不匹配"，脚本对 https 一律 `ignore_https_errors`，所以**只有域名那次才顺带证明证书能用**；证书链本身以服务器上 `curl https://liche.cloud/`（不加 `-k`）返回 200 为准。
- 截图输出到 `screenshots\<SPA_TAG>-*.png`；全部通过时退出码 0，有失败项会列在最后。
- 密码等凭据只通过环境变量传入，**不要写进脚本**。

## 二、验证本机改动（改了前端还没部署时）

```powershell
# 1) 构建前端产物（或另开窗口跑 npm run dev）
cd C:\Users\33721\Desktop\wechat-agent\wechat-agent-java\web ; npm run build

# 2) 起本地代理：托管构建产物，把 /api/admin 转发到线上并注入口令
$env:WG_PW='<面板口令>' ; $env:ADMIN_USERNAME='rootlcw'
& "D:\soft\JetBrains\Python\python\python.exe" "$PSScriptRoot\spa_server.py"   # 监听 http://127.0.0.1:8899

# 3) 另开一个窗口跑验证（这次不要设 SPA_BASE）
$env:WG_PW='<面板口令>' ; $env:ADMIN_USERNAME='rootlcw' ; $env:SPA_TAG='v7-local'
& "D:\soft\JetBrains\Python\python\python.exe" "$PSScriptRoot\verify_spa.py"
```

## 三、排查「浏览器说不安全」

浏览器报"不安全"而服务器侧一切正常时，用 `check_security.py` 一条命令分清责任：

```powershell
$env:WG_PW='<面板口令>' ; & "D:\soft\JetBrains\Python\python\python.exe" "$PSScriptRoot\check_security.py"
```

它用真实 Chromium **直连**（禁用本机代理，绕开 Steam++ 之类的中间拦截）、**不忽略证书错误**，输出：浏览器的安全状态判定、有没有 `http://` 混合内容、控制台安全告警、页面表单情况，并留一张 `sec-check.png`。

这次（2026-09-12 接入 liche.cloud）就是靠它定位的：直连一切正常 → 问题出在**用户浏览器把换证书之前点过"继续访问"的那个旧标签页一直标成不安全**，关掉标签页/换无痕窗口即恢复。

## 注意

- **跑之前先确认 8899 上是「这次的」代理**：Windows 允许两个进程绑同一端口（`SO_REUSEADDR`），上一次会话留下的旧 `spa_server.py` 会继续接连接、用旧口令转发，症状是面板接口全 401（每个 401 还会累加后端的失败计数）。先 `Get-NetTCPConnection -LocalPort 8899 -State Listen | Select OwningProcess` 看启动时间，旧的就 `taskkill /PID <pid> /F`，再起新的；起完用 `curl -H "X-Agent-Admin-Key: <口令>" http://127.0.0.1:8899/api/admin/overview` 确认 200。
- 脚本会**故意输错一次密码**来验证报错提示，因此每次运行会产生 1 次失败计数（后端连续 5 次才封禁 10 分钟），不要连续狂跑。
- `spa_server.py` 会在本地模拟后端的登录接口（`POST /api/admin/session`），其余接口走线上真实数据。
- 跑完看一眼 `screenshots\` 里的截图，确认视觉没有退化；确认没问题就只保留最新一轮，把旧的删掉。
- Playwright 启动浏览器需要创建命名管道，受限沙箱下会报 `PermissionError: [WinError 5]`，需要更宽的权限。
- 依赖：`D:\soft\JetBrains\Python\python\python.exe` 已装 playwright + Chromium；Node 在 `D:\soft\Node.js\node.exe`。
