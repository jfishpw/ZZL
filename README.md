# 掌中灵 — 儿童平板监管系统

免 root 的安卓儿童设备管控系统。控制端（家长手机）设定规则、查看记录、临时放行；被控端（儿童平板）强制执行，无法主动卸载或退出。

> 完整需求与技术方案见 [`docs/需求规格与技术方案.md`](docs/需求规格与技术方案.md)，面向家长的图文手册见 [`docs/安装使用手册.html`](docs/安装使用手册.html)

## 功能总览

- **规则引擎（本地执行，断网照常管控）**：上学日/周末总时长、黑/白名单、逐应用单日上限（按"从现在起还能用 N 分钟"的净用量判定）、多允许时段（支持跨零点）、生效星期
- **实时拦截**：全屏遮罩 + 密码内联；名单类应用**零等待拦截**，普通切换经 3 秒核实防误拦
- **临时授权**：总时长加时、单应用放行、临时总解封、加时申请与折扣审批（孩子申请 → 家长按比例批准）
- **离线指令**：锁定/截屏等指令落库 + TTL，设备上线补执行，幂等台账
- **三级离线密码**：断网自救通道（L1 加时 / L2 放行应用 / L3 退出管控），PBKDF2 只存哈希、本地云端双写、错误限流
- **防卸载双模式**：Device Admin（可被取消激活但会暴露）/ Device Owner（强制，需一次 ADB）
- **防关无障碍三层**：Device Owner 冻结系统无障碍开关（条件式防死锁）、本地周期巡检不依赖网络、常驻通知告警
- **按需截屏**：Android 11+ 完全静默；图标隐藏、审计日志（断网先落本地补传）
- **使用报告**：额度进度、趋势、应用排行、会话时间线、拦截记录、破解尝试
- **锁定前 5 分钟预警**：总时长/单应用/时段三类，各每天至多提醒一次

## 快速开始

```bash
# 1. 服务器：一键部署（见 docs/部署指南.md）
node scripts/pack-deploy.cjs && scp zzl-deploy.tar.gz root@<服务器IP>:/opt/
ssh root@<服务器IP> "mkdir -p /opt/zzl && cd /opt/zzl && tar -xzf /opt/zzl-deploy.tar.gz && cd deploy && ./install.sh"

# 2. 构建 APK（或直接取用 dist/ 下的成品）
node scripts/build-release.cjs

# 3. 双端安装：控制端装家长手机 → 注册 → 生成配对码；
#    被控端装儿童平板 → 输配对码 → 按引导开启权限
```

---

## 目录结构

```
.
├── docs/                    需求规格与技术方案、部署指南、安装使用手册
├── server/                  中继服务（Node.js + Fastify + 内置 SQLite）
│   ├── src/
│   │   ├── index.js         入口：前置检查、路由注册、WebSocket、离线巡检与过期清理
│   │   ├── preflight.js     启动前置检查（JWT 密钥强度、时区正确性）
│   │   ├── config.js        环境变量配置（含全部 TTL 与阈值）
│   │   ├── db.js            数据库适配层（唯一的 SQLite 依赖点）
│   │   ├── auth.js          密码哈希、配对码、UUID
│   │   ├── ws.js            WebSocket 实时通道
│   │   ├── commands.js      指令队列：落库、TTL、补发、回执
│   │   ├── grants.js        临时授权：创建、撤销、设备状态对账
│   │   ├── pins.js          离线密码备份的数据访问层
│   │   ├── screenshots.js   截屏图片落盘与清理
│   │   ├── audit.js         审计日志
│   │   ├── ratelimit.js     登录与配对码防爆破
│   │   └── routes/          health / auth / pair / devices / policy / usage / apps
│   │                        / commands / grants / timeRequests / pins
│   │                        / screenshots / audit
│   └── scripts/             envcheck、preflighttest、dbtest、migrationtest、statetest
│                            、smoke、smoke-m4、smoke-m5、smoke-m6
├── deploy/                  Docker 部署与激活脚本
│   ├── docker-compose.yml
│   ├── install.sh                   服务器一键部署
│   ├── activate-device-owner.sh     ADB 激活为 Device Owner（可选，最强模式）
│   └── .env.example                 全部可用环境变量（与 config.js 逐项对齐）
├── android/                 Android 工程（一套代码，两个角色）
│   ├── keystore/            release 签名密钥（不进版本库，务必备份）
│   └── app/src/
│       ├── main/            共享：数据层、网络层、密码哈希、服务器设置界面
│       ├── parent/          控制端：登录、设备列表、规则/应用管控/使用报告/限制工具
│       │                    /加时审批/设备加固/截屏/审计日志
│       ├── child/           被控端：配对、权限引导、无障碍、管控引擎、指令执行、
│       │                    拦截遮罩、离线密码、设备管理、保活、截屏、图标隐藏
│       └── testChild/       JVM 单元测试（规则判定、授权推导、密码强度与限流、哈希）
└── scripts/                 开发辅助脚本
    ├── build-release.cjs    构建签名 release APK（含 Windows 缓存锁规避）
    ├── verify-apk.cjs       校验 APK 签名与清单
    ├── gen-keystore.cjs     生成/查看 release 签名密钥
    ├── pack-deploy.cjs      打包服务器部署包
    ├── check-env.cjs        检查依赖与 registry 连通性
    ├── gradle-run.cjs       用指定 JDK 调用 Gradle（从 ASCII 联接构建）
    └── setup-jdk.cjs        下载 JDK 17
```

---

## 一、后端

### 技术选型说明

| 项 | 选择 | 理由 |
|---|---|---|
| 运行时 | Node.js ≥ 22.13 | 需要内置 `node:sqlite` |
| 框架 | Fastify 5 | 轻量、性能好 |
| 数据库 | **Node 内置 `node:sqlite`** | **零原生依赖**，服务器上无需编译工具链，镜像更小 |
| 实时通道 | ws | WebSocket 双向推送 |
| 鉴权 | JWT（`@fastify/jwt`） | 控制端与设备端使用不同 role 的令牌 |
| 密码哈希 | `node:crypto` scrypt / PBKDF2 | 不引入 bcrypt 等原生依赖 |

> **为什么不用 better-sqlite3 / MySQL**：本场景设备数在个位数、请求量极低，内置 SQLite 完全够用，且省掉一整套原生编译与运维负担。
> 所有数据库访问都收敛在 `server/src/db.js` 一个文件里，日后要换成 better-sqlite3 或 PostgreSQL，只需替换该文件。

### 本地启动

```bash
cd server
npm install

# 必须设置 JWT 密钥（生产环境不设会拒绝启动）
JWT_SECRET=$(openssl rand -hex 32) npm start
```

启动后：

```
掌中灵中继服务已启动 — http://0.0.0.0:8080
WebSocket 端点 — ws://0.0.0.0:8080/ws
数据库文件 — .../server/data/app.db
```

### 测试

```bash
cd server
npm run env:check      # 环境变量契约自检（8 项）
npm run preflight:test # 启动前置检查自检（28 项）
npm run db:test        # 数据库与密码学自检（28 项）
npm run migration:test # 老库升级自检（32 项，独立库，可与服务并存）
npm run state:test     # 指令队列与授权：TTL、补发顺序、额度日、幂等（36 项）
npm run smoke          # M1–M3 端到端冒烟（需服务已启动）
npm run smoke:m4       # M4 端到端冒烟（需服务已启动）
npm run smoke:m5       # M5 端到端冒烟（需服务已启动）
npm run smoke:m6       # M6 端到端冒烟（需服务已启动）

npm test               # 依次跑完上面九套（合计 400+ 项）
```

Android 侧的单元测试是纯 JVM 的，**不需要设备也不需要服务**：

```bash
# 在仓库根目录执行
node scripts/gradle-run.cjs "C:/Users/Administrator/.jdks/temurin-17" :app:testChildDebugUnitTest
```

`smoke` 覆盖的链路：
健康检查 → 注册 → 登录 → 错误密码拒绝 → 无令牌拒绝 → 生成配对码 → 被控端认领 → 重复认领拒绝 → 设备列表 → 心跳上报 → 前台应用更新 → 策略下发 → 使用记录上报与幂等 → 名单与逐应用规则 → 原子替换 → 应用清单 → WebSocket 双向（ready / 设备上线 / 状态实时推送 / 权限丢失告警）

`smoke:m4` 覆盖的链路：
在线指令下发与回执 → 离线下发与上线补发 → 状态对账 → 授权创建/撤销/过期 → 加时申请与折扣批准 → 使用报告与拦截记录 → 权限边界（跨角色、跨设备）

`smoke:m5` 覆盖的链路：
密码备份上传与拉取 → 云端版本号冲突（**旧的不能覆盖新的**）→ 远程重置/清除某一级 → 离线重置的延后生效提示 → 破解尝试上报与幂等去重 → 家长查看破解记录 → 加固状态（模式、保活四维、卸载阻止）随心跳与设备状态对账下发 → 跨设备隔离

`smoke:m6` 覆盖的链路：
截屏指令下发与 TTL → 图片二进制上传（**逐字节校验，证明没被转码或截断**）→ 家长查看与 `viewedAt` → **越权三重拦截**（跨账号 403 / 同账号别的设备 404 / 无令牌 401）→ 上传约束（超限、空内容、角色边界）→ 指令回执带截屏 ID → 图标隐藏与状态对账 → 审计日志（截屏请求/上传/查看、图标开关全部留痕）→ **被控端事件补传与幂等去重** → 按级别过滤 → 摘要统计 → 删除与清理 → 按设备隔离 → 截屏就绪的实时推送

`migration:test` 覆盖的链路（**老库升级不坏**）：
造一个 M5 时代的老库（含 `screenshots.file_path NOT NULL` 与没有 `client_key` 的 `audit_logs`）→ 用当前代码执行迁移 → 验证表已重建、老数据保住、**新代码能写入不带 `file_path` 的行**、索引没在重建中丢失、`command_id` 唯一约束生效、被控端事件可写入且幂等 → 重复迁移无副作用

Android 单元测试（`app/src/testChild`，纯 JVM，无需设备，共 **115 项**）：
| 测试类 | 覆盖 |
|---|---|
| `RuleJudgeTest`（31） | 五条判定与优先级、跨零点时段与剩余分钟、星期掩码、白名单为空、名单快速预判 |
| `PinPolicyTest`（25） | 密码强度（弱口令/重复/递增/日期/跨级重复）、限流状态机（阈值、锁定期、重置窗口） |
| `ImageScalingTest`（14） | 截图缩放（按长边、不放大、极端长条不出现 0 像素）、质量钳制、超限重压决策 |
| `GuardOverridesTest`（13） | 授权→覆盖项推导：归日点剔除、过期剔除、解锁取最远到期、负数钳制 |
| `GuardRulesTest`（18） | 额度日归日点、周末/工作日额度、时间解析、时段剩余时间（预警用） |
| `PinCryptoTest`（10） | PBKDF2 一致性与跨端一致性、盐随机性、迭代次数参与计算、脏数据不抛异常 |
| `ChildIdentityTest`（4） | 设备身份派生与脏数据防护 |

### 环境变量

见 [`deploy/.env.example`](deploy/.env.example)。关键项：

| 变量 | 默认 | 说明 |
|---|---|---|
| `APP_PORT` | 8111 | 宿主机对外端口（App 端须与此一致；容器内固定 8080） |
| `JWT_SECRET` | — | **必填**，生产环境缺失将拒绝启动 |
| `DB_PATH` | `/data/app.db` | 容器内数据库路径 |
| `HEARTBEAT_TIMEOUT_MS` | 90000 | 超过此时长未心跳则判离线 |
| `TZ` | `Asia/Shanghai` | **建议显式设置**：额度日、审批时限都按服务器本地时区计算，不一致会导致归日点偏移 |
| `COMMAND_TTL_SCREENSHOT_SECONDS` | 60 | 截屏指令有效期 |
| `COMMAND_TTL_CLEAR_LOCK_MINUTES` | 360 | 解除锁定指令有效期（防止"意外解锁"很久之后才被补执行） |
| `GRANT_TTL_TOTAL_ADD_MINUTES` | 720 | 加时授权默认有效期 |
| `GRANT_TTL_APP_ALLOW_MINUTES` | 120 | 单应用放行默认有效期 |
| `TIME_REQUEST_TTL_MINUTES` | 30 | 加时申请未审批自动作废 |
| `TIME_REQUEST_MIN_INTERVAL_MINUTES` | 10 | 两次申请的最小间隔 |
| `TIME_REQUEST_MAX_PER_HOUR` | 2 | 每小时最多申请次数 |

---

## 二、部署到阿里云

**完整步骤见 [`docs/部署指南.md`](docs/部署指南.md)**（含安全组配置、镜像加速、备份与排错手册）。

速览：

```bash
# 1. 服务器上安装 Docker（带阿里云镜像）
curl -fsSL https://get.docker.com | sh -s -- --mirror Aliyun

# 2. 配置 Docker 镜像加速（不配会拉不动 node 镜像，见指南第二步）

# 3. 重新打包并上传部署包（改了后端代码就要重打一次）
node scripts/pack-deploy.cjs
scp zzl-deploy.tar.gz root@203.0.113.10:/opt/
ssh root@203.0.113.10 "mkdir -p /opt/zzl && cd /opt/zzl && tar -xzf /opt/zzl-deploy.tar.gz"

# 4. 一键部署（会自动生成 .env 与随机 JWT_SECRET）
cd /opt/zzl/deploy && chmod +x install.sh && ./install.sh

# 5. 部署后自检
docker compose exec zzl-server node scripts/alltests.mjs   # 期望全部通过（400+ 项）
```

⚠️ **必须做**：阿里云控制台 → 安全组 → 入方向 → 放行 **TCP 8111**（默认对外端口；若改过 `APP_PORT` 则放行对应端口）。
不做这一步，服务正常但 App 永远连不上。

> `pack-deploy.cjs` 只打包 `server/` 与 `deploy/`，并会解压清单核对
> **不含 `node_modules` / `data` / `.env` / 日志**。
> 排除 `data/` 是硬性要求 —— 那是本机的数据库与截屏图片，属于隐私数据。

---

## 三、Android 工程

### 导入

用 Android Studio（Ladybug 或更新版本）打开 `android/` 目录。

首次同步时，Android Studio 会自动下载 Gradle 8.13 并生成 `gradle/wrapper/gradle-wrapper.jar`（该二进制文件未纳入版本库属于正常现象）。

### 编译环境要求

| 项 | 要求 | 说明 |
|---|---|---|
| JDK | **17 或 21** | ⚠️ AGP 8.7.2 **不支持 JDK 25**，会在构建时报 `What went wrong: 25.0.1` |
| Gradle | 8.13 | 已写入 wrapper 配置 |
| compileSdk | **34** | 本机 SDK 未安装 API 35；如需升级，先在 SDK Manager 装 "Android 15 (API 35)" 再改 `app/build.gradle.kts` 一行 |
| minSdk | 26 | Android 8.0 |
| Android SDK | platform 34 + build-tools 34.0.0 | |

若本机只有 JDK 25，可执行以下脚本自动下载一个 Temurin 17 到 `~/.jdks/temurin-17`：

```bash
node scripts/setup-jdk.cjs
```

### ⚠️ 非 ASCII 路径限制（本机必读）

本项目位于 `D:\pwg\监管软件`，**路径含中文**。AGP 在 Windows 上默认拒绝这种路径，会报：

```
Your project path contains non-ASCII characters.
This will most likely cause the build to fail on Windows.
```

已在 `android/gradle.properties` 中开启 `android.overridePathCheck=true` 绕过检查。

#### ★ 两个任务对路径的要求正好相反

这是本项目最容易踩的一点，务必分清：

| 任务 | 必须使用的路径 | 原因 |
|---|---|---|
| **打包 release**（`assembleRelease`） | **真实路径** `D:\pwg\监管软件\android` | 命令沙箱不解析目录联接，联接路径会被判定越界并拒绝写入 |
| **单元测试**（`testChildDebugUnitTest`） | **ASCII 联接** `D:\pwg\zzl-build` | 中文路径会让 fork 出的测试 worker 解析不到 classpath |

**单元测试为什么必须走联接**：在中文路径下跑测试，会出现一种很迷惑的现象 ——
测试类**确实编译出来了**（`build/tmp/kotlin-classes/childDebugUnitTest/` 下能看到 .class），
Gradle 也正确列出了测试类名，但 worker 里 `Class.forName` 抛 `ClassNotFoundException`。
问题出在传给测试 worker 的 classpath 上，中文路径在其中被错误解码。
给测试 JVM 加 `-Dfile.encoding` / `-Dsun.jnu.encoding` 都无效 —— 故障发生在
classpath 解析阶段，早于这些属性生效。**只能靠 ASCII 路径解决。**

```bash
# 单元测试（必须用联接）
GRADLE_CWD="D:/pwg/zzl-build" node scripts/gradle-run.cjs "<jdk>" :app:testChildDebugUnitTest

# 打包 release（必须用真实路径，build-release.cjs 已是默认）
node scripts/build-release.cjs
```

> ⚠️ 联接路径需要允许访问工作目录之外的写入。若在受限沙箱环境下运行，
> 联接那条命令可能被拦截 —— 此时应改用允许访问该路径的方式执行，
> 而不是把联接当成"不可用"。

#### 联接的创建与重建

后缀 `zzl-build` → `android/`。从 bash 调用 `mklink` 会被安全策略拦截，用 Node 创建：

```bash
node -e "require('fs').symlinkSync('D:\\\\pwg\\\\监管软件\\\\android','D:/pwg/zzl-build','junction')"
```

### 命令行构建（无需 Android Studio）

本仓库提供 Node 包装脚本，绕开 Gradle wrapper 直接调用本地缓存的 Gradle：

```bash
# 编译两个角色的 debug 包（默认从真实路径构建）
node scripts/gradle-run.cjs "C:/Users/Administrator/.jdks/temurin-17" :app:assembleDebug

# 只编译被控端
node scripts/gradle-run.cjs "C:/Users/Administrator/.jdks/temurin-17" :app:assembleChildDebug

# JVM 单元测试 —— 注意必须用 ASCII 联接路径，见上一节
GRADLE_CWD="D:/pwg/zzl-build" node scripts/gradle-run.cjs "C:/Users/Administrator/.jdks/temurin-17" :app:testChildDebugUnitTest
```

产物位置：

```
android/app/build/outputs/apk/parent/debug/app-parent-debug.apk    (~20 MB)
android/app/build/outputs/apk/child/debug/app-child-debug.apk      (~20 MB)
```

> 日常构建用 `gradle-run.cjs`；**打 release 包请用 `scripts/build-release.cjs`** ——
> 它默认从真实路径构建，并处理了中间产物被占用的问题
> （见下文「构建报拒绝访问的两个根因」）。

### 双角色构建

同一套代码库，通过 product flavor 产出两个独立 App：

| Build Variant | 产物 | applicationId |
|---|---|---|
| `parentDebug` / `parentRelease` | 控制端（家长手机） | `com.zzl.guardian.parent` |
| `childDebug` / `childRelease` | 被控端（儿童平板） | `com.zzl.guardian.child` |

角色专属代码分别位于 `app/src/parent` 与 `app/src/child`；共享的数据层、网络层、服务器设置界面在 `app/src/main`。

在 Android Studio 左下角 **Build Variants** 面板切换即可。

命令行构建：

```bash
cd android
./gradlew :app:assembleParentDebug
./gradlew :app:assembleChildDebug
```

### 服务器地址配置（三层覆盖）

**代码中不存在任何写死的地址或端口**，优先级从高到低：

| 层级 | 位置 | 是否需要重新打包 |
|---|---|---|
| ① 页面手动填写 | 控制端登录页 / 被控端配对页 → 「服务器设置」 | 否 |
| ② DataStore 运行期值 | 上一步保存后自动持久化 | 否 |
| ③ 编译期默认值 | `android/local.properties`（本机私有，优先）→ `android/gradle.properties`（代码库内置，已脱敏为示例地址） | 是 |

修改默认值（**推荐写进 `local.properties`** —— 它已在 `.gitignore` 中，不会随代码库泄露服务器地址）：

```properties
# android/local.properties
SERVER_HOST=你的服务器IP或域名
SERVER_PORT=8111
SERVER_SCHEME=http
```

`android/gradle.properties` 里保留的是 RFC 5737 文档专用示例地址 `203.0.113.10`，仅作占位；
两处都配置时以 `local.properties` 为准。若两处都没配，装出来的 App 需要在「服务器设置」里手填一次地址。

**主机与端口是两个独立输入框**，保存前会先请求 `/api/health` 做连通性探测，失败时明确提示原因（无法解析地址 / 连接超时 / 拒绝连接）。切换成功后旧令牌自动作废，强制重新登录或重新配对。

### Release 签名

APK 必须签名才能安装 release 包，但**使用自签名证书即可，不需要付费、不需要上架商店**。

本项目已生成好签名密钥，一条命令即可完成：

```bash
node scripts/gen-keystore.cjs          # 已存在则跳过，保护现有密钥
node scripts/gen-keystore.cjs --show   # 查看当前配置（不回显口令）
```

它会做三件事：

1. 用 JDK 17 的 `keytool` 在 `android/keystore/zhangzhongling.jks` 生成密钥
   （RSA 2048 位，有效期 10000 天）
2. 生成 32 位随机口令，写入 `android/local.properties`（不进版本库）
3. 用 `keytool -list` 自检一遍，确认密钥可读

| 项 | 值 |
|---|---|
| keystore | `android/keystore/zhangzhongling.jks` |
| 别名 | `zhangzhongling` |
| 签名方案 | v1（JAR）+ v2 + v3 全部启用 |
| 配置位置 | `android/local.properties` 的 `RELEASE_STORE_*` 四项 |

> ⚠️ **keystore 与口令必须一起备份到安全位置。**
> Android 用签名证书判断「新版本是不是同一个应用」——
> 两者任一丢失，后续版本都将**无法覆盖安装**，只能先卸载（会丢失设备配对与全部管控设置）再装新包。
> 备份清单见 `android/keystore/README.txt`。

### 构建 release 包

```bash
node scripts/build-release.cjs            # 默认不混淆
node scripts/build-release.cjs --minify   # 开启 R8 混淆 + 资源压缩
node scripts/verify-apk.cjs               # 校验签名与清单
```

产物：

```
android/app/build/outputs/apk/child/release/app-child-release.apk
android/app/build/outputs/apk/parent/release/app-parent-release.apk
```

`verify-apk.cjs` 会用 SDK 自带的 `apksigner` 与 `aapt2` 逐项核对：签名有效性、
v1/v2/v3 方案、证书主体（**确认不是 debug 证书**）、`applicationId`、
版本号、未标记 debuggable、被控端关键权限齐全、有启动入口。

#### 关于混淆（`--minify`）——默认关闭

`app/proguard-rules.pro` 中的规则已写全并**实跑通过**，但默认不开启。理由是风险与收益不对称：

- **收益**：抬高反编译门槛。但本 App 以侧载方式安装、不进应用商店，
  真实威胁是「孩子取消设备管理器激活」而不是「有人逆向 APK」——混淆对前者毫无帮助。
- **风险**：序列化、Retrofit 接口、WorkManager 反射实例化等失效**都在运行时**，
  编译期一律发现不了。

由于本项目在开发阶段无法做真机验证，不适合用唯一一次真机机会去赌一个低收益特性。
若决定开启，规则中已重点保护两处真实风险点：

| 风险点 | 被混淆的后果 |
|---|---|
| `GuardKeepaliveWorker`（WorkManager 用 `Class.forName` 反射实例化） | **静默失效**：保活任务从此不再执行，家长只会看到设备「离线」，毫无异常提示 |
| `GuardEntryPoint`（无障碍/前台服务由系统实例化，只能经 EntryPointAccessors 取依赖） | 依赖注入失败，管控服务起不来 |

开启后请务必在真机上验证六条链路：配对、策略下发、拦截、保活、离线密码、截屏。

### ⚠️ 构建报「拒绝访问」的两个根因（先分清再动手）

构建 release 时可能遇到下面这类失败，**两个根因的表象一模一样，处置却相反**：

```
# 形态 1
Execution failed for task ':app:mergeChildReleaseJavaResource'.
> java.io.FileNotFoundException: ...\incremental\childRelease-mergeJavaRes\zip-cache\<hash>= (拒绝访问。)

# 形态 2 —— 换个任务、换个路径，本质相同
Execution failed for task ':app:dexBuilderChildRelease'.
> Caused by: java.nio.file.AccessDeniedException:
    ...\intermediates\project_dex_archive\childRelease\...jar
```

#### 根因 A：命令沙箱拦截（先查这个）

**证据**：日志里能搜到沙箱自己的报告：

```
[sandbox] 命令被沙箱拦截，以下操作被拒绝：
  - D:\pwg\zzl-build\app\build\...\zip-cache\<hash>= (读/写 · 拒绝)
```

**原因**：**沙箱不解析目录联接**。它取命令行里的路径字符串，
判定 `D:\pwg\zzl-build\...` 落在工作目录之外并拒绝 ——
哪怕它实际指向的就是工作目录内的 `android/`。

**处置**：改用真实路径构建（默认已是如此，见上文「非 ASCII 路径限制」）。
**这种情况重试没有意义**，`build-release.cjs` 检测到 `sandbox` 字样会直接给出结论，不再空转。

#### 根因 B：中间产物被其它构建占用

**原因**：同时跑两个 Gradle 构建 —— 哪怕先起的那个"看起来"已结束（进程退出 ≠ 句柄全部释放）。

**处置**：`build-release.cjs` 内置了规避 —— **先直接构建，失败且判定为访问冲突时**
才停守护进程 + 清中间产物并重试一次。

> 为什么不是构建前先清？这些目录动辄上万个文件，递归删除是分钟级操作，
> 还会因少量被占用文件反复重试（实测单这一步就能耗掉十几分钟），
> 而多数情况下原有中间产物是好的。
>
> ⚠️ **不要在别处同时跑 Gradle。** 会重现同一错误。

#### 两个实现细节（都踩过）

- **`gradle --stop` 必须带超时**。实测它在守护进程注册表异常时会**无限挂起**，
  表现为日志停在"停止 Gradle 守护进程…"十几分钟不动、看起来像编译卡死。
  这一步只是优化，超时应当跳过。
- **清理中间产物必须看两层目录**。AGP 的顶层目录名**不含变体名**，变体在第二层
  （`intermediates/project_dex_archive/childRelease/...`），只有 `incremental/` 是例外。
  只看一层会一个都匹配不到 —— 脚本报"无需清除"而实际什么都没删，
  这种静默空操作比报错更难查。

#### 耗时参考

| 场景 | 耗时 |
|---|---|
| 正常增量重建 | 2–3 分钟 |
| 精确清某变体中间产物后重建 | 5–10 分钟 |
| 从零构建 release（含 `lintVitalRelease`） | 25–30 分钟 |

> release 明显比 debug 慢，主要因为会额外跑 `lintVitalRelease`。
> 排错时可用 `node scripts/build-release.cjs :app:assembleChildRelease` 只编一个角色，把等待时间减半。

### 侧载安装的注意事项

1. **允许安装未知应用**（Android 8.0+ 按来源授权）
2. **Play Protect 风险提示** —— 管控类 App 申请无障碍与读取应用列表权限，属正常现象，选「仍然安装」
3. **Android 13+ 受限设置** —— 从浏览器安装的 App 默认**无法开启无障碍服务**。需进入
   `设置 → 应用 → 掌中灵 → 右上角菜单 → 允许受限设置`，这是装机时最容易卡住的一步
4. **厂商 ROM**（小米 / 华为 / 荣耀 / OPPO / vivo）还需额外设置自启动与后台运行白名单

---

## 四、当前进度

### ✅ M1 — 项目骨架与通信打通（已完成）

**后端**
- Fastify + 内置 SQLite（17 张表全部就位）+ JWT 鉴权
- 账号注册 / 登录、配对码生成 / 认领
- 设备列表 / 详情 / 重命名 / 解绑（删除记录会同步通知被控端清除会话；
  同一设备按 `childUuid` 复用记录 —— 已删除就新增，还在就更新，重绑不产生重复条目）
- 心跳上报、在线状态维护（含超时兜底巡检）
- WebSocket 双向通道：控制端按账号分房间，被控端按设备连接
- 离线巡检、优雅退出

**Android**
- Gradle 双角色工程骨架（flavor 分角色，共享 main 源集）
- 服务器地址三层可配置 + 连通性探测
- 网络层（Retrofit + OkHttp + kotlinx.serialization），baseUrl 运行时动态替换
- 控制端：注册 / 登录 / 设备列表 / 生成配对码 / 服务器设置
- 被控端：配对码绑定 / 权限自检清单 / 心跳与长连接 / 服务器设置

**验收**：控制端能看到被控端上线，并实时收到状态与权限告警。

### ✅ M2 — 被控端基础管控（已完成）

**后端**
- 策略读写：控制端整份更新（version 自增）+ WebSocket 实时下发 + 被控端主动拉取兜底
- 使用记录：批量上报（按 `clientKey` 幂等去重）、日汇总、会话明细查询

**被控端**
- **无障碍服务** 实时感知前台应用；服务连接时补查一次，避免漏计已在前台的应用
- **时长统计** 按会话记录：熄屏/锁屏暂停计时，按策略里的 `resetHour` 归日
- **规则引擎** 完全本地判定，断网照常管控（M2 实现总时长，逐应用与黑白名单留待 M3）
- **全屏遮盖拦截**：`TYPE_APPLICATION_OVERLAY` 挂载，屏蔽返回键，配合强制返回桌面
- **常驻前台服务**：通知显示剩余额度、心跳上报、策略同步、记录定时上传
- **Room 本地库**：策略缓存、使用会话、拦截日志；进程被杀后自动补齐未收尾会话
- **权限引导** 可一键跳转各系统设置，含 Android 13+ 受限设置与厂商 ROM 提示
- 开机自启（仅已配对设备）

**控制端**
- 点击设备卡片进入**管控规则**编辑：上学日/周末每日总时长、额度重置时间、启用开关
- 保存即下发；设备离线时提示「将在其上线后自动生效」

**验收**：把上学日总时长设为 1 分钟，被控端到点弹出全屏拦截页并自动返回桌面。

### ✅ M3 — 完整规则引擎（已完成）

**后端**
- **策略包**一次性下发（基础策略 + 黑白名单 + 逐应用规则），避免"只同步一半"的中间态
- 名单与逐应用规则的整份替换接口；被控端上报已安装应用清单、控制端查询与请求刷新
- 时段校验含跨零点（`22:00 - 07:00`）、星期掩码、重复规则与非法值一律拒绝

**被控端**
- **五条判定全部落地**，顺序固定：黑名单命中 → 白名单未命中 → 总时长耗尽 → 单应用超额 → 不在允许时段
- 规则包在本地已解析成不可变结构，判定是纯内存计算，每秒巡检零数据库开销
- **逐应用「用时不计入当日总时长」**（`exempt_total`）：该应用的用时不计入当日已用总量，
  总时长耗尽后仍可打开；它自己的单日上限与允许时段照常生效，因此不会变成无限使用
- 采集并上报「有启动图标」的应用（系统组件不入清单，避免名单变成噪声）
- 状态页显示规则版本、名单数量、逐应用规则条数

**控制端**
- 设备卡片拆为两个入口：**时长规则** 与 **应用管控**
- 应用管控页：黑名单/白名单切换、应用勾选、逐应用规则编辑（单日上限、多时段、生效星期、启用开关、用时不计入当日总时长）

### ✅ M4 — 离线指令、临时授权、加时审批与使用报告（已完成）

**两套机制，边界刻意划清**

| | 指令队列（commands） | 临时授权（grants） |
|---|---|---|
| 语义 | 一次性**动作** | 持续**状态** |
| 内容 | 立即锁定、解除锁定、截屏、请求重报应用清单 | 加时、单应用放行、临时总解封 |
| 下发 | 先落库再推送；设备上线拉取补执行 | 整份对账 + 实时推送 |
| 失效 | 每条带 TTL，过期置 `expired` 并在控制端回显 | `expire_at` 自然淘汰 |
| 回执 | 有（按 `commandId` 幂等，已终态不被覆盖） | 无（状态本身就是真相） |

> 为什么授权不做成指令：授权有有效期，逐条下发会出现「补发的授权到达时早已过期」这种语义错误；
> 而整份对账天然不会 —— 设备上线时拿到的就是"此刻仍生效的那些"。

**后端**
- 指令队列：`src/commands.js`，TTL 取自 `config.js`（截屏 60 秒、解除锁定 6 小时、锁定永久）
- 设备状态对账接口 `GET /api/device-state`：锁定状态 + 全部生效授权 + 单调递增的 `stateVersion`
  （旧状态不会覆盖新状态，避免乱序推送导致"撤销了却又生效"）
- 加时申请闭环 `src/routes/timeRequests.js`：孩子提交 → 家长**打折批准** → 复用授权链路生效
  - 频率限制用持久化记录判断（间隔 ≥10 分钟、每小时 ≤2 次），重启服务不会失效
  - 重复提交同一申请会被复用而不是堆积，且不消耗申请额度
- 使用报告接口：总览（含额度与剩余）、近 N 日趋势、应用排行、会话时间线、拦截记录
- 拦截记录上报（`clientKey` 幂等去重）；登录与配对码认领补上限流

**被控端**
- **唯一决策入口** `GuardEngine.applyDecision`：前台切换、熄屏亮屏、秒级巡检、策略/授权变更全部汇入它，
  从结构上排除"某条路径漏判"
- **覆盖项与策略分离**：`GuardOverrides`（锁定、解封、加时、放行）与 `GuardPolicy`（静态规则）分离，
  判定顺序为：立即锁定 → 临时解封 → 管控开关 → 黑白名单 → 当日总时长 → 逐应用规则
- **加时只在它所属的额度日生效**，跨过归日点自动失效，昨天的加时不会泄漏到今天
- **单应用放行仍受当日总时长约束** —— 放行一个应用不等于给孩子无限额度
- 拦截页新增「申请加时」按钮，并显示申请状态（已发送 / 已批准 / 未批准 / 网络失败）
- 家长「立即锁定」压过一切（含管控总开关），且不给申请入口，避免功能变成讨价还价
- 指令执行台账：同一指令重复投递（长连接 + 补发）只执行一次
- 每 2 分钟完整对账一次：状态整份覆盖 + 补拉指令，任何丢失的推送都会自愈

**控制端**
- 设备卡片扩展为四个入口：**时长规则**、**应用管控**、**使用报告**、**限制工具**
- 限制工具：立即锁定 / 加时（+15/+30/+60）/ 单应用放行 / 临时总解封，并列出生效中的授权可随时撤销
- 使用报告：今日额度进度、近 7/14/30 日趋势柱状图（叠额度参考线）、应用排行、拦截记录、时间线
- 加时申请：顶栏角标 + 列表横幅提示，支持按比例打折批准（1/4、1/2、3/4）
- 管控规则新增「允许孩子申请加时」开关

### ✅ M5 — 双模式防卸载、三级离线密码与保活（已完成）

**双模式能力对比**（控制端与被控端界面都会展示，家长看得见差距）

| 能力 | Device Admin | Device Owner |
|---|---|---|
| 阻止卸载 | 可（孩子可先取消激活绕过） | **强制**，且无法取消激活 |
| 隐藏应用图标 | 可 | 可 |
| 禁止恢复出厂 | 不可 | 可（**默认不禁用**，保留家长忘密码的后路） |
| 首次激活 | 设备上点确认 | **需一次 ADB**（`deploy/activate-device-owner.sh`） |

Device Admin 模式的兜底：`onDisabled` 回调立刻拉起服务重新评估 + 上报家长 + 本地落库，
让"取消激活"无法悄悄进行。做不到阻止，但能做到暴露。

**三级离线密码（断网自救通道）**

| 级别 | 可执行 |
|---|---|
| L1 日常 | 总时长加时（+15/+30/+60） |
| L2 管理 | 单应用放行、解除某应用时段限制 |
| L3 超级 | 退出管控、解除设备管理器、卸载本应用 |

- **密码框内联在拦截页上**，验证通过后同页展开操作（加时/放行应用/退出管控）；
  旧版三个隐藏入口（连点/长按/拨号暗码）已移除
- PBKDF2-HMAC-SHA256（12 万次迭代）+ 每级独立 16 字节随机盐，**只存哈希**
- **每次改密码必换新盐** —— 复用旧盐会让"改了密码"在哈希层面毫无变化
- 本地 + 云端双写：本地保断网可用，云端保换机可恢复；冲突用单调递增 `version` 解决，
  **只有云端版本更高才覆盖本地**
- 错误限流：5 次锁 10 分钟、10 次锁 1 小时；失败尝试本地落库并上报，家长能看到破解行为

**保活三层（叠加，不是三选一）**
1. 前台服务（`START_STICKY` + 常驻通知显示剩余额度）
2. 精确闹钟（`SCHEDULE_EXACT_ALARM`，每 15 分钟自我唤醒）
3. WorkManager 周期任务（最小 15 分钟，系统统一调度）

精确闹钟在部分 ROM 会被限制、WorkManager 在部分 ROM 会被延迟，一起用才稳。
另有开机自启、`onTaskRemoved` 重启、设备管理器 `onDisabled` 回调三条补充路径。

**厂商白名单引导**：识别小米 / 华为 / 荣耀 / OPPO / vivo / 三星 / 一加 / 魅族，
给出各自的自启动与后台设置路径；保活健康度按"前台服务 / 电池优化豁免 / 精确闹钟 / 厂商白名单"四维展示。

**控制端**：设备卡片新增「设备加固」面板 —— 查看当前模式与能力矩阵、远程重置/清除各级密码、
查看孩子的破解尝试记录、Device Owner 激活指引。

> ⚠️ **审计修复的两个关键陷阱**（都是编译期查不出来的）
> 1. **退出管控后"管控复活"**：退出时若只清策略与密码、不清配对会话，
>    保活组件下次唤醒会把服务拉起来并重新同步策略 —— 家长"退出"了，第二天限制又回来。
>    修法：配对会话是管控存在与否的**权威标志**，退出必须 `clearChildSession()`。
> 2. **Device Owner 下卸载自堵**：`setUninstallBlocked(true)` 是系统级强制，
>    而 Device Owner 又无法在系统界面取消激活 —— 卸载路径被自己的加固永久封死。
>    修法：卸载与退出流程都先调 `unblockUninstall()`。

### ✅ M6 — 按需截屏、图标隐藏、审计日志（已完成）

**按需截屏（不是定时、不是实时镜像）**

| 方式 | 可用版本 | 用户可见性 |
|---|---|---|
| `AccessibilityService.takeScreenshot()` | **Android 11 (API 30) 起** | **完全静默**，无任何系统提示 |
| `MediaProjection` | Android 5 起 | 必须弹系统录屏授权框 |

> ⚠️ **Android 10 及以下做不到"远程静默截一张"。** 这是系统限制，不是软件缺陷。
> 低版本上被控端会**如实返回失败原因**，而不退到 MediaProjection ——
> 那条路会当着孩子的面弹出"是否允许录制屏幕"，而他点取消后照样拿不到图。
> 既暴露意图又失败，比一句明确的不支持糟糕得多。

- 图片长边限制 1920（够看清在用哪个应用）、JPEG 质量 70；超服务端上限时按比例降质**重压一次**
- 列表接口**不返回图片本体**（几十条 base64 会让响应体膨胀到几 MB）
- 图片以二进制流下载，**按设备隔离**：`GET /api/devices/{id}/screenshots/{shotId}/image`
  —— 只按截图 id 查会让家长 A 猜数字拿到家长 B 孩子的屏幕内容
- 家长每查看一次都写审计日志（隐私可追溯）；图片有**张数 + 天数双重上限**，设备解绑时连文件一起清
- 截屏一到就通过 WebSocket 推给家长，不必轮询
- **采集失败单独记 `screenshot.failed`（warn 级）**，与成功的 `screenshot.upload` 分开 ——
  否则家长在审计里看到"设备上传截屏"、截图列表却是空的，会把"根本没采集成功"
  （无障碍被关 / 系统版本低）误读成"传了但控制端不显示"

**隐藏桌面图标**

| 模式 | 机制 | 强度 |
|---|---|---|
| Device Owner | `setApplicationHidden` | 系统级：应用在「设置 → 应用」里也看不到 |
| 其它 | 禁用 launcher 组件 | **仅隐藏桌面图标**：应用列表里仍能找到并打开 |

- 图标隐藏是**设备状态**而非一次性指令（带 `stateVersion` 随对账下发），
  否则孩子重启一次平板图标就回来了
- 只禁 launcher 组件，**不用 `setApplicationEnabledSetting` 禁用整个应用** ——
  后者会把前台服务与无障碍一起干掉，等于自毁管控能力
- 三条恢复路径：控制端远程恢复、L3 离线密码（退出管控时自动恢复）、拨号盘暗码

**审计日志**

- 两类来源混排、按时间倒序：`server`（家长操作）与 `child`（设备上报的本地事件）
- 被控端在**断网期间先落本地库**，联网后批量补传（`clientKey` 幂等去重）
  —— 这类事件宁可晚报、不可漏报
- **只有 warn 级标红**：截屏、登录是"记录"不是"告警"，都标红会让真正需要注意的
  （孩子关了无障碍、在试密码）被淹没
- 覆盖：策略变更 / 指令下发 / 授权 / 密码重置与尝试 / 权限丢失 / 图标隐藏 / 截屏上传与查看 / 设备解绑

### ✅ M7 — 打包签名与交付（已完成）

**签名与产物**

- 生成 release 签名密钥（`android/keystore/zhangzhongling.jks`，RSA 2048 / 10000 天），
  32 位随机口令写入 `local.properties`；配套 `gen-keystore.cjs --show` 可在不回显口令的前提下查看配置
- 双角色 release APK 构建成功，`verify-apk.cjs` 逐项校验：**v1/v2/v3 三种签名方案齐全**、
  证书主体正确（非 debug 证书）、`applicationId` 正确、未标记 debuggable、
  被控端 6 项关键权限齐全、有启动入口
- `proguard-rules.pro` 补全并实跑验证，构建开关为 `-PenableMinify=true`（默认关，理由见上文）

**交付物**

`dist/` 目录是给家长直接取用的成品（已重命名，一眼能看出装到哪台设备）：

| 交付物 | 大小 | 说明 |
|---|---|---|
| `dist/zzl-child-v1.0.0-release.apk` | 12.72 MB | **被控端**，装儿童平板 |
| `dist/zzl-parent-v1.0.0-release.apk` | 12.72 MB | **控制端**，装家长手机 |
| `zzl-deploy.tar.gz` | 102 KB | 服务器部署包（仅 server/ + deploy/） |
| `docs/安装使用手册.html` | 41 KB | 面向家长的完整图文手册，可直接打印为 PDF |
| `docs/部署指南.md` | — | 阿里云部署全流程 |
| `android/keystore/` | — | 签名密钥（**务必备份**） |

> 构建原始产物在 `android/app/build/outputs/apk/<角色>/release/`，
> `dist/` 是复制过去的交付副本。改了代码重新构建后，记得同步复制。
>
> 两个 APK 都经 `scripts/verify-apk.cjs` 校验通过（25 项）：
> v1/v2/v3 签名有效、证书非调试证书、包名与版本号正确、未标记 debuggable、有启动入口。

**★ 本阶段审计发现并修复的 3 个缺陷（都是「不报错但行为错」的类型）**

1. **`.env` 留空 `JWT_SECRET` 会得到一个「没有鉴权」的服务**（最严重）

   `config.js` 写的是 `process.env.JWT_SECRET ?? 'dev-secret-…'`，
   而 `??` **只对 null / undefined 生效**。`.env` 里 `JWT_SECRET=`（留空）拿到的是**空字符串**，
   于是原有的 `startsWith('dev-secret')` 判断也不成立 —— 服务照常启动。

   后果不是「弱鉴权」而是「没有鉴权」：任何人都能用空串签出合法的家长令牌，直接接管全部设备。
   而 `.env.example` 里那一行正是留空的（等着被 install.sh 填写）。

   **修法**：新增 `src/preflight.js`，把密钥检查升级为「非空 + 生产环境 ≥32 字符」，
   并显式拒绝空串。

2. **容器时区为 UTC，导致「额度日」整天错位**

   额度日的归日点由**进程本地时区**决定：服务端 `grants.js#dayKeyAt` 用 `getFullYear/getHours`，
   被控端 `DayKeys` 用设备本地时区。容器默认 UTC，与被控端（北京时间）差 8 小时，
   后果不是「差一点」而是整天错位 —— 平板把 9/19 早 8 点的记录标成 `dayKey=2026-09-19`，
   而服务器此刻的 `todayKey()` 还是 `2026-09-18`；家长打开使用报告看到「今日用量 0 分钟」，
   加时授权也会落在错误的日子上。

   更隐蔽的是：glibc 读 `TZ` 依赖 `/usr/share/zoneinfo`，
   **镜像里缺 tzdata 时 `TZ` 会被静默忽略**，看起来设了、实际仍按 UTC 跑。

   **修法**：Dockerfile 探测并按需安装 tzdata（基础镜像已自带时则不产生任何网络请求）+ 固定 `ENV TZ`；
   启动时用 `Intl`（走 Node 内置 ICU，不依赖 tzdata）算出应有偏移，与进程实际偏移比对，
   不一致即拒绝启动并明确指出原因。

3. **启动错误提示会被 `process.exit()` 吞掉**

   `console.error` 在 stdout/stderr 为**管道**时（Docker 日志、`| grep`、CI）走的是异步写，
   紧随其后的 `process.exit()` 不等缓冲区刷新 —— 用户看到的是「退出码 1、零输出、不知道为什么」。

   这个问题是在验证第 2 项修复时实测暴露的：时区检查的报错确实被完整丢掉了。

   **修法**：新增 `writeStderr()` 用 `fs.writeSync(2, …)` 同步写；
   并新增一条单测，专门让子进程「写 5 行后立即退出」，验证内容确实到达父进程。

**★ 新增两套自检（+36 项）**

- `env:check`（8 项）—— **环境变量契约校验**。这个文件诞生前，`.env.example` 里有三个变量名与
  `config.js` 对不上（`COMMAND_TTL_SCREENSHOT` / `COMMAND_TTL_UNLOCK` / `COMMAND_TTL_GRANT`），
  还有一批变量压根没写进去。这类漂移的后果是**用户改了配置、服务照常启动、行为毫无变化、什么错都不报**。
  脚本双向比对「代码读取的」与「文档声明的」，并校验数值型变量可解析、关键变量合法。
- `preflight:test`（28 项）—— 逐分支固化启动检查，含一条「输出不被截断」的工程约束。

**交付包自检**

`pack-deploy.cjs` 打完包后会解压清单核对：**不得含 `node_modules` / `data` / `.env` / 日志**，
且 10 个关键文件必须齐全（少一个服务器上就会部署失败）。

> `data/` 被排除是硬性要求 —— 那里是本机的数据库与截屏图片，属于隐私数据，绝不外传。

### ✅ 真机实测与加固（M7 后持续迭代，当前版本 0.2.0）

M7 交付后进入真机验收，每一批反馈都完成「定位根因 → 修复 → 回归构建」闭环。代表性的问题与修法：

**计时与统计（最隐蔽的一类）**

- **SystemUI 窗口事件切断会话**：音量条/通知栏/截屏浮层会被无障碍当成「用户切走了」，
  把正在计时的会话关掉且再无事件恢复 —— 表现为限额迟迟不触发、报告时间远低于实际、
  时间线全是 0 秒碎片。修法：SystemUI 与输入法事件在无障碍层整个丢弃；
  安装器/权限弹窗等「应用内流程」窗口同样处理
- **候选-核实两段式前台切换**：无障碍事件只作候选，3 秒后用窗口树核实「前台真的变了」才提交，
  权限弹窗/侧边栏/悬浮球等瞬态窗口无法再把会话切碎
- **名单类应用零等待拦截**：名单判定不依赖用量，可零成本预判 —— 黑名单/白名单外的应用打开
  立即拦截（3 秒确认期对孩子而言是"还能玩一会儿"）；拦截后 3 秒核实，假事件自动回滚自愈
- **单应用限额基线**：家长设「单日上限 2 分钟」指的是"从现在起还能用 2 分钟"，
  不是"今天总共再用 2 分钟" —— 限额首次生效（或修改值）时记录当下已用量作为基线，判定用净用量

**离线密码与拦截页**

- 密码框必须显式 `PasswordTransformationMethod`（部分 ROM 明文回显；`setSingleLine` 会重置变换）
- 「没有设置密码」误报：降级查找要从全部三级里找最近已设级，只查相邻级会漏
- 验证无响应：验证协程逐层补超时兜底，任何一层断了都不至于让界面永远卡在「验证中」
- 家长远程锁定期间拦截页只保留「退出管控」—— 锁定压过一切授权，加时/放行按钮此时无效
- 退出管控的清理必须跑在独立协程作用域：跑在服务自己的 scope 里，`stopService` 会把
  清理协程连同自己一起取消，状态清一半、二次点击必失败

**防绕过加固**

- **防关无障碍三层**：Device Owner 下条件冻结系统无障碍开关（`no_config_accessibility`，
  仅无障碍正常时冻结，意外关闭自动解冻防死锁）→ 本地周期巡检权限丢失（不依赖网络心跳）→
  常驻通知变告警文案
- 状态对账必须保留未过期的本地授权（整份覆盖会让"放行 30 分钟"几分钟就失效）；
  本地授权用递减负数 id 避免互相覆盖
- 锁定前 5 分钟 Toast 预警（总时长/单应用/允许时段，含跨零点时段，各类每天至多一次，
  加时后剩余回升会自动重新武装）

**控制端体验**

- 名单勾选 + 单日上限并设时给出红字提示（名单优先，上限不生效）
- 星期选择加动态摘要（全不选 = 红字警告"规则不会在任何一天生效"）
- 被控端密码门禁（打开 App 需任一级密码）、桌面图标隐藏入口
- `/api/health` 返回服务端版本号，家长可核对部署是否更新

### ⏭️ 后续可做

| 方向 | 内容 |
|---|---|
| 真机验收 | 持续进行中；按 `docs/安装使用手册.html` 补全双端装机与厂商 ROM 实测 |
| HTTPS | 接入域名与证书（`deploy/docker-compose.yml` 中已预留 Caddy 配置，注释掉待启用） |
| Docker 实测 | 在干净服务器上按部署指南从零走一遍 |
| 应用商店 | 如需上架，需先提交无障碍与设备管理权限的用途说明 |

---

## 五、开发辅助

```bash
# 检查依赖安装状态与 npm registry 连通性
node scripts/check-env.cjs
```

> 注：本机 bash 环境缺少 `ls` / `tail` 等常用命令，因此辅助脚本统一用 Node 编写。
