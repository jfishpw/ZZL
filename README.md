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
    ├── gradle-run.cjs       用指定 JDK 调用本地 Gradle（自动探测 wrapper 缓存）
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
# 在仓库根目录执行（JDK 路径换成你的 JDK 17 安装位置）
node scripts/gradle-run.cjs "<JDK 17 路径>" :app:testChildDebugUnitTest
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
> 排除 `data/` 是硬性要求 —— 那是运行期的数据库与截屏图片，属于隐私数据。

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
| compileSdk | **34** | 如需升级，先在 SDK Manager 装 "Android 15 (API 35)" 再改 `app/build.gradle.kts` 一行 |
| minSdk | 26 | Android 8.0 |
| Android SDK | platform 34 + build-tools 34.0.0 | |

若本机只有 JDK 25，可执行以下脚本自动下载一个 Temurin 17 到 `~/.jdks/temurin-17`：

```bash
node scripts/setup-jdk.cjs
```

### 命令行构建（无需 Android Studio）

本仓库提供 Node 包装脚本，绕开 Gradle wrapper 直接调用本地缓存的 Gradle：

```bash
# 编译两个角色的 debug 包
node scripts/gradle-run.cjs "<JDK 17 路径>" :app:assembleDebug

# 只编译被控端
node scripts/gradle-run.cjs "<JDK 17 路径>" :app:assembleChildDebug

# JVM 单元测试
node scripts/gradle-run.cjs "<JDK 17 路径>" :app:testChildDebugUnitTest
```

> JDK 路径可先跑 `node scripts/setup-jdk.cjs` 自动安装到 `~/.jdks/temurin-17`。
> 若项目路径包含非 ASCII 字符导致测试 worker 报 `ClassNotFoundException`，
> 可用 `GRADLE_CWD=<ASCII路径>` 指向 android/ 的目录联接后再跑测试。

产物位置：

```
android/app/build/outputs/apk/parent/debug/app-parent-debug.apk    (~20 MB)
android/app/build/outputs/apk/child/debug/app-child-debug.apk      (~20 MB)
```

> 日常构建用 `gradle-run.cjs`；**打 release 包请用 `scripts/build-release.cjs`** ——
> 它会自动定位 JDK 与 Gradle，并内置了中间产物被占用时的清理重试。

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

### 侧载安装的注意事项

1. **允许安装未知应用**（Android 8.0+ 按来源授权）
2. **Play Protect 风险提示** —— 管控类 App 申请无障碍与读取应用列表权限，属正常现象，选「仍然安装」
3. **Android 13+ 受限设置** —— 从浏览器安装的 App 默认**无法开启无障碍服务**。需进入
   `设置 → 应用 → 掌中灵 → 右上角菜单 → 允许受限设置`，这是装机时最容易卡住的一步
4. **厂商 ROM**（小米 / 华为 / 荣耀 / OPPO / vivo）还需额外设置自启动与后台运行白名单

---

## 四、后续可做

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
