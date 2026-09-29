# 部署通道安全（2026-09-14 · 部署私钥降权）

> 一句话：**CI 那把私钥即使泄露，也拿不到 shell、写不进任何内容、推不了自己造的镜像**——
> 它只能触发一个固定的、需要 HMAC 验签的服务端包装脚本。

## 1. 威胁模型

改造前：`/root/.ssh/authorized_keys` 里那把 `github-actions-wechat-agent` **没有任何选项**，
谁拿到私钥谁就是 root 交互式 shell（= 整台机器 + 数据库 + 证书 + 其它凭据）。

改造后要守住的三件事：
1. 拿不到交互式 shell / 传不了任意文件 / 不能转发端口；
2. 光有私钥**不能**让服务器跑起来一个攻击者自造的镜像（host 网络 + root，等价于拿到 root）；
3. 部署流程本身不能变复杂到"改坏了没人会修"。

## 2. 组成

| 组件 | 位置 | 说明 |
|---|---|---|
| 强制命令 | `/root/.ssh/authorized_keys` | `restrict,command="/usr/local/bin/wechat-deploy" ssh-ed25519 …` |
| 包装脚本 | `/usr/local/bin/wechat-deploy`（755 root:root） | 仓库留档：`deploy/server/wechat-deploy` |
| 验签密钥 | GitHub Secrets `DEPLOY_TAR_SECRET` + 服务器 `/etc/wechat-deploy.secret`（600） | 两端各一份，**换要一起换** |
| 落地目录 | `/var/lib/wechat-deploy/incoming`（700） | 镜像包不再放 `/tmp` |
| 审计日志 | `/var/log/wechat-deploy.log`（600） | 每次调用一行，含拒绝原因与原始命令 |
| 备份 | `/root/.ssh/authorized_keys.bak-20260914` | 兜底还原用 |

`restrict` = `no-pty,no-port-forwarding,no-agent-forwarding,no-X11-forwarding,no-user-rc`。

## 3. 子命令（其它一律拒绝）

```
wechat-deploy upload-image    <git-sha> <sha256> <hmac>   # stdin = 镜像包
wechat-deploy upload-compose  <sha256> <hmac>             # stdin = docker-compose.remote.yml
wechat-deploy upload-settings <sha256> <hmac>             # stdin = docker/searxng/settings.yml
wechat-deploy deploy          <git-sha>
wechat-deploy smoke-admin                                 # stdin = KEY=...
wechat-deploy alert-notify                                # stdin = KEY=... / MSG=...
```

调用方式：`ssh … "wechat-deploy upload-image <sha> <sha256> <hmac>" < 文件`（scp 已不可用）。

**HMAC** = `openssl dgst -sha256 -hmac <secret>` 对**文件 sha256 的十六进制字符串**做签名；
服务端先验签、再校验 `sha256sum` 与声明一致，才落盘。所以：
- HMAC 错 → 拒绝；
- 内容与声明不符 → 拒绝；
- 光有私钥、没有密钥 → 一个字节都写不进去。

**额外校验**：`upload-compose` 会先 `docker compose config -q`（在 `/opt/wechat-agent-infra` 里跑，
保证相对挂载路径解析正确），非法 compose 不进线上；`upload-settings` 拒绝任何带值的 `secret_key`
（"密钥不进仓库"这条约束由服务端兜底）。

**deploy** 里做的（原来写在 CI 的那串 shell）：`docker load` → 只重建 agent →
重启 searxng（配置是挂载进去的、不热加载）→ `docker image prune -f` →
**按 image id 保留「当前容器镜像 + 次新镜像」的全部 tag** → 删除镜像包 → 打印容器与镜像状态。

**一个会让人误判的现象（2026-09-20 巡检时差点当成 bug）**：`docker images | grep wechat-agent`
有时会列出**三行** tag。原因是清理逻辑按 **image id** 排除，而**两次内容完全相同的构建会产生同一个
image id、只是各带一个 tag**（实测 `wechat-agent:6c16fd2…` 与 `wechat-agent:b682f77b…` 的
`{{.ID}}` 都是 `sha256:33b2025e…`）。所以那是**同一个镜像的别名 tag，不是多一代**：
磁盘 0 开销、回滚用哪个 tag 都一样。真要消掉，`docker rmi -f <多余tag>`（镜像仍被另一个 tag 引用，不会删）。
判断方法：`docker images --no-trunc --format '{{.Repository}}:{{.Tag}} -> {{.ID}}' --filter 'reference=wechat-agent:*'`。
顺带：`docker images --format` 支持 `{{.CreatedAt}}` 与 `{{.CreatedSince}}`（本机 docker 26.1.3 实测都可用）。

## 4. 运维

- **改远端部署逻辑**：改 `deploy/server/wechat-deploy` → 用 base64 装到服务器
  （`[Convert]::ToBase64String([IO.File]::ReadAllBytes($f))` → `ssh … "echo '<b64>' | base64 -d > /usr/local/bin/wechat-deploy && chmod 755 … && bash -n …"`）
  → 先手动 `wechat-deploy deploy <上一个 sha>` 之外的子命令自测 → 再 push 走一次真实流水线。
  **别用 `Get-Content -Raw | ssh` 推含中文的脚本**（ANSI 往返会把引号吃掉，见 AGENTS 坑 12）。
- **轮换验签密钥**：两端一起改（GitHub Secrets + 服务器文件），顺序随意，改完跑一次部署验证。
- **兜底**：服务器**密码登录仍然开着**。私钥限制把自己关在门外时，用密码 SSH 进去
  `cp /root/.ssh/authorized_keys.bak-20260914 /root/.ssh/authorized_keys` 即可回退。
- **手工部署**（排障用）：直接在服务器上 `/usr/local/bin/wechat-deploy deploy <sha>`
  （脚本在 `SSH_ORIGINAL_COMMAND` 为空时会读自己的参数）。

## 5. 实测记录（2026-09-14）

负向（用临时测试密钥，`restrict,command=…` 同配置，测完删除）：

| 用例 | 结果 |
|---|---|
| `ssh … id` | 只打印用法、退出 1，**拿不到 shell** |
| `ssh … "bash -s"` | 同上 |
| `ssh -L 19999:127.0.0.1:443 -N`（真去连隧道） | 隧道连不通（`curl` 000） |
| `ssh -t …` | `PTY allocation request failed on channel 0` |
| `scp` 传文件 | 退出 1、远端拒绝 |
| 错 HMAC 上传 | 拒绝 |
| 声明的 sha256 与实际内容不符 | 拒绝 |
| 非法 compose（YAML 坏） | 拒绝，线上文件不变 |
| settings 里带 `secret_key` 值 | 拒绝 |

正向：
- `smoke-admin`（口令走 stdin）经真实 SSH 强制命令跑出 `withkey=200 login=200`；
- 两次真实流水线成功：一次在收紧前（`9a5cc5a` 前的 `243a305`）、一次在**收紧之后**（workflow_dispatch `34810452736`），
  部署后自检通过、镜像 tag 正确、`/tmp` 无残留、面板 200 / 无口令 401 / 0 ERROR。

## 6. 已知边界（没做，也不打算做）

- **没加 `from=`**：GitHub 托管 runner 的出口 IP 是 6980 条 CIDR（5436 IPv4 + 1544 IPv6）且会变，
  写进 authorized_keys 既臃肿又会"变一次就静默部署失败"；而 HMAC 已经堵死了"泄露私钥 → 推自己的镜像"这条路，
  `from=` 收益很小。真要做，得配一个每天拉 `api.github.com/meta` 刷新的定时任务。
- **`DEPLOY_USER` 仍是 root**：应用要写 `/opt/wechat-agent-infra`、要调 docker，
  而 docker 组成员本来就等价于 root；再加一层 `sudo NOPASSWD` 只是多一个会坏的零件。
  真正的收窄在"这把钥匙只能跑一个脚本"，已经拿到。
- 拿到私钥的人仍能：**重放**一次已授权过的上传（需要那份文件的原始字节，而它用完即删）、
  触发一次 `deploy`（要让服务器跑起来还得先有包）。要彻底堵死得给镜像包做签名，属于下一步。

## 7. CI 上传变慢时的快路径（手动部署，2026-09-29 实测）

**现象**：2026-09-20 之前的部署整条流水线 **4 分钟**；从 09-22 起，
`Upload image and deployment files` 这一步要 **1 小时 50 分**（三次都是），整条流水线 2 小时。
这一步是从 GitHub 美国 runner 把 `docker save` 出来的镜像传到阿里云，**这条路径不由我们控制**。

**实测对比**：本机 → 服务器 **2.85 MB/s**（20 MB 上传 7 秒）；CI 那条路折算约 **60~75 KB/s**，差约 40 倍。

**快路径**（2026-09-29 用过三次，每次约 1 分钟）：

```powershell
cd web; npm run build          # 前端先构建（产物进 src/main/resources/static）
cd ..; mvn -q -DskipTests package
# 再把 jar（约 100 MB，30 秒）和新的 docker-compose.remote.yml 传上去
```

服务器侧的关键三行——**用当前镜像做底、只换 jar**，不用在服务器上装 Maven/Node，也不用重新拉基础镜像：

```bash
printf 'FROM %s\nCOPY app.jar /app/app.jar\n' "$(docker inspect wechat-agent-java -f '{{.Config.Image}}')" > Dockerfile
docker build -t "wechat-agent:manual-<短sha>" .
AGENT_IMAGE="wechat-agent:manual-<短sha>" docker compose -f docker-compose.remote.yml up -d --no-build agent
```

**它绕过了什么**：HMAC 验签的上传、服务端 smoke check、镜像 tag 的两代保留策略。
所以这是"提速/应急"手段而不是常用手段：**用完自己补三项验证**（首页 200、无口令 401、日志 0 ERROR），
并且**一定要同步 `docker-compose.remote.yml`**（坑 36：compose 只透传 `environment:` 里列出的变量，
不传新版就会出现"代码是新的、配置是旧的"）。

**两个坑**：

1. **探活别漏 `-k`**：证书是给 `liche.cloud` 签的，`curl -s https://127.0.0.1/...` 必然证书不匹配、
   `%{http_code}` 恒为 `000`，看着像"应用没起来"，其实早好了。我第一次因此白等了 150 秒的探活循环。
2. **正在跑的 CI 流水线要取消**：它构建的是**更早的提交**，等它两小时后落地会把手动部署的改动盖回去。
   `gh run cancel <id>` 之后要确认它真的没在部署。

