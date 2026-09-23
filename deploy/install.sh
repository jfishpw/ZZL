#!/usr/bin/env bash
#
# 掌中灵中继服务 — 阿里云服务器一键部署脚本
#
# 用法（在服务器的 deploy/ 目录下执行）：
#   chmod +x install.sh
#   ./install.sh              # 首次部署
#   ./install.sh --update     # 更新代码后重新部署
#
# 脚本会做的事：
#   1. 检查 Docker 与 docker compose 是否就绪
#   2. 配置 Docker 镜像加速（国内拉取 node 镜像的必要步骤）
#   3. 生成 .env（含随机 JWT_SECRET）
#   4. 构建并启动容器
#   5. 做一次健康检查

set -euo pipefail

cd "$(dirname "$0")"

GREEN='\033[0;32m'
YELLOW='\033[0;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { printf "${GREEN}[*]${NC} %s\n" "$1"; }
warn()  { printf "${YELLOW}[!]${NC} %s\n" "$1"; }
fail()  { printf "${RED}[x]${NC} %s\n" "$1" >&2; exit 1; }

UPDATE_MODE=0
[[ "${1:-}" == "--update" ]] && UPDATE_MODE=1

# ---------------------------------------------------------------- 1. Docker
if ! command -v docker >/dev/null 2>&1; then
  fail "未检测到 Docker。请先安装：
  curl -fsSL https://get.docker.com | sh
  systemctl enable --now docker
安装完成后重新运行本脚本。"
fi

if ! docker compose version >/dev/null 2>&1; then
  fail "未检测到 docker compose 插件（Docker 20.10+ 自带）。
  升级 Docker 或安装：apt install -y docker-compose-plugin"
fi

if ! docker info >/dev/null 2>&1; then
  fail "无法连接 Docker 守护进程。若是首次安装，执行：systemctl start docker
  若提示权限不足，把当前用户加入 docker 组后重新登录：usermod -aG docker \$USER"
fi

info "Docker $(docker --version | awk '{print $3}' | tr -d ',') 就绪"

# ---------------------------------------------------------- 2. 镜像加速
DAEMON_JSON=/etc/docker/daemon.json
if ! grep -q 'registry-mirrors' "$DAEMON_JSON" 2>/dev/null; then
  warn "未检测到 Docker 镜像加速配置，国内拉取 node 镜像会很慢甚至超时。"
  warn "建议手动配置（阿里云控制台 → 容器镜像服务 → 镜像加速器 可获取专属地址）："
  cat <<'EOF'

    sudo mkdir -p /etc/docker
    sudo tee /etc/docker/daemon.json <<'JSON'
    {
      "registry-mirrors": ["https://<你的专属加速地址>.mirror.aliyuncs.com"]
    }
    JSON
    sudo systemctl daemon-reload
    sudo systemctl restart docker

EOF
  warn "本脚本不自动修改该文件，以免覆盖你已有的配置。继续执行下一步…"
else
  info "已配置 Docker 镜像加速"
fi

# ---------------------------------------------------------------- 3. .env
if [[ -f .env ]]; then
  info ".env 已存在，沿用现有配置"
else
  if [[ ! -f .env.example ]]; then
    fail "缺少 .env.example，请确认 server/ 与 deploy/ 两个目录都已上传"
  fi
  cp .env.example .env

  SECRET=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
  # 兼容 GNU sed 与 BSD sed 的写法
  sed -i.bak "s|^JWT_SECRET=.*|JWT_SECRET=${SECRET}|" .env && rm -f .env.bak

  info "已生成 .env 并写入随机 JWT_SECRET"
fi

[[ -f ../server/package-lock.json ]] || fail "缺少 server/package-lock.json，镜像需要它来锁定依赖版本"
[[ -f ../server/Dockerfile ]] || fail "缺少 server/Dockerfile"

# ---------------------------------------------------------------- 4. 启动
if [[ $UPDATE_MODE -eq 1 ]]; then
  info "更新模式：重建镜像并重启容器"
else
  info "首次部署：构建镜像（首次构建需下载 Node 镜像与依赖，约 1-3 分钟）"
fi

docker compose up -d --build

# ---------------------------------------------------------------- 5. 验证
PORT=$(grep -E '^APP_PORT=' .env | cut -d= -f2 | tr -d '[:space:]')
PORT=${PORT:-8111}

info "等待服务就绪…"
for i in $(seq 1 30); do
  if curl -fsS "http://127.0.0.1:${PORT}/api/health" >/dev/null 2>&1; then
    info "服务已就绪"
    break
  fi
  if [[ $i -eq 30 ]]; then
    warn "30 秒内未通过健康检查，用以下命令查看日志："
    echo "    docker compose logs -f zzl-server"
    exit 1
  fi
  sleep 1
done

echo
info "部署完成"
echo
echo "  健康检查   curl http://127.0.0.1:${PORT}/api/health"
echo "  查看日志   docker compose logs -f zzl-server"
echo "  重启服务   docker compose restart"
echo "  停止服务   docker compose down"
echo "  端到端自检 docker compose exec zzl-server node scripts/smoke.js"
echo

PUBLIC_IP=$(curl -fsS --max-time 3 https://api.ipify.org 2>/dev/null || echo "<你的公网IP>")
echo "  App 端「服务器设置」填写："
echo "    主机  ${PUBLIC_IP}"
echo "    端口  ${PORT}"
echo
warn "别忘了在阿里云控制台的【安全组】入方向放行 TCP ${PORT}，否则 App 连不上。"
