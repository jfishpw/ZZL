FROM node:22-bookworm-slim

# 国内服务器从官方 npm 源安装很慢，默认走阿里云镜像；
# 如需改回官方源：docker compose build --build-arg NPM_REGISTRY=https://registry.npmjs.org
ARG NPM_REGISTRY=https://registry.npmmirror.com

# ---------------------------------------------------------------- 时区（★ 正确性相关）
#
# 额度日的归日点是由 `grants.js` / `usage.js` 用**进程本地时区**算出来的，
# 被控端的 `DayKeys` 同样用设备本地时区。容器默认是 UTC，
# 若不做处理会出现：平板按北京时间把 9/19 早上 8 点的使用记录标成 dayKey=2026-09-19，
# 而服务器此刻的 todayKey() 还停留在 2026-09-18 ——
# 家长打开"使用报告"看到的是「今日用量 0」，加时授权也会落在错误的日子上。
#
# 注意：glibc 读取 TZ 需要 /usr/share/zoneinfo，缺少时 TZ 变量会被静默忽略。
# 这里先探测再按需安装，基础镜像已自带 tzdata 时完全跳过，不产生任何网络请求。
RUN if [ ! -e /usr/share/zoneinfo/Asia/Shanghai ]; then \
      apt-get update \
      && apt-get install -y --no-install-recommends tzdata \
      && rm -rf /var/lib/apt/lists/*; \
    fi \
 && test -e /usr/share/zoneinfo/Asia/Shanghai \
 && echo "tzdata 就绪"

ENV TZ=Asia/Shanghai

WORKDIR /app

# 先装依赖，充分利用 Docker 层缓存：只要 package.json 没变就不会重装
COPY package.json package-lock.json ./
RUN npm config set registry "$NPM_REGISTRY" \
 && npm ci --omit=dev --no-audit --no-fund \
 && npm config delete registry

COPY src ./src
COPY scripts ./scripts

ENV NODE_ENV=production
ENV APP_HOST=0.0.0.0
ENV APP_PORT=8080
ENV DB_PATH=/data/app.db
ENV UPLOAD_DIR=/data/uploads

# ⚠️ 必须显式设置：config.js 对 SCREENSHOT_DIR 的兜底值是相对路径 `data/screenshots`，
# 会落到 /app/data/screenshots —— 那里不在挂载卷 /data 内，
# 容器一重建（docker compose up --build）历史截屏就全部消失。
ENV SCREENSHOT_DIR=/data/screenshots

VOLUME ["/data"]
EXPOSE 8080

# node:sqlite 在 Node 22.13+ 才默认可用；低于该版本会直接报模块不存在，
# 这里先做一次显式检查，让失败原因一眼可见
RUN node -e "require('node:sqlite'); console.log('node:sqlite 可用')"

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:8080/api/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "--disable-warning=ExperimentalWarning", "src/index.js"]
