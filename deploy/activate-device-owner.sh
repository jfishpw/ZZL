#!/usr/bin/env bash
#
# 掌中灵 — Device Owner 一键激活脚本
#
# 作用：把被控端设为「设备所有者」，从而获得厂商级的防卸载强度 ——
#       卸载入口会被系统彻底移除，孩子也无法在设置里取消激活。
#
# 用法：
#   1. 在被控端设备上安装并**打开**掌中灵被控端（首次启动后不要配对也行，
#      但必须先安装完成）
#   2. 用数据线把设备连到电脑，开启「USB 调试」
#   3. 在电脑上执行：bash activate-device-owner.sh
#
# ⚠️ 三个必须知道的限制（都是 Android 系统的硬性约束，不是本脚本的问题）：
#   1. 设备上**不能已存在其他 Device Owner**（例如其他 MDM 应用或之前激活过）。
#      如果之前激活过，需要先恢复出厂设置。
#   2. 设备上**不能添加过任何账号**（Google 账号、厂商账号等）。
#      系统在「设置 → 账号」里检测到账号会拒绝激活。
#      如果已经加过，需要先在设置里移除账号。
#   3. 激活后**无法通过 App 自行取消**。要退出只能靠离线超级密码，
#      或恢复出厂设置。这是 Device Owner 的固有代价。
#
# 建议：先确认家长已设置好三级离线密码再执行本脚本。

set -euo pipefail

# 接收器的**全限定类名**。它由 namespace（com.zzl.guardian）决定，与 applicationId 无关，
# 因此 debug 包（com.zzl.guardian.child.debug）和 release 包（com.zzl.guardian.child）
# 用的是同一个类名。
#
# ⚠️ 这里必须用全限定名，不能写成 applicationId 的相对形式（如 `.child.admin.X`）——
# 因为 applicationId（com.zzl.guardian.child[.debug]）**不是**该类名
# （com.zzl.guardian.child.admin.X）的前缀，相对写法会被解析成
# com.zzl.guardian.child.child.admin.X，那是个不存在的类，dpm 会报
# "Unknown admin: ComponentInfo{...}"。
RECEIVER_CLASS="com.zzl.guardian.child.admin.GuardDeviceAdminReceiver"

info()  { printf '\033[36m[信息]\033[0m %s\n' "$*"; }
ok()    { printf '\033[32m[成功]\033[0m %s\n' "$*"; }
warn()  { printf '\033[33m[警告]\033[0m %s\n' "$*"; }
fail()  { printf '\033[31m[失败]\033[0m %s\n' "$*"; }

# ---------- 0. 检查 adb ----------

if ! command -v adb >/dev/null 2>&1; then
  fail "找不到 adb 命令。"
  echo "  请先安装 Android SDK Platform Tools，并把它加入 PATH。"
  echo "  下载地址：https://developer.android.com/studio/releases/platform-tools"
  exit 1
fi

# ---------- 1. 检查设备连接 ----------

info "检查设备连接…"
DEVICES="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
COUNT="$(printf '%s\n' "$DEVICES" | grep -c . || true)"

if [ "$COUNT" -eq 0 ]; then
  fail "没有检测到已授权的设备。"
  echo "  请确认："
  echo "   · 数据线已连接，且设备上选择了「传输文件」模式"
  echo "   · 已开启「开发者选项 → USB 调试」"
  echo "   · 设备屏幕上弹出的「允许 USB 调试」已点允许"
  exit 1
fi

if [ "$COUNT" -gt 1 ]; then
  warn "检测到多台设备，将使用第一台：$(printf '%s' "$DEVICES" | head -1)"
  echo "  如需指定设备，请设置环境变量 ANDROID_SERIAL"
fi

TARGET="${ANDROID_SERIAL:-$(printf '%s' "$DEVICES" | head -1)}"
info "目标设备：$TARGET"

# ---------- 2. 探测已安装的包名 ----------
#
# debug 包的 applicationId 带 `.debug` 后缀，release 包不带。
# 与其让家长自己判断装了哪个，不如两个都试一遍。

info "探测被控端包名…"
PACKAGE=""
for candidate in "com.zzl.guardian.child" "com.zzl.guardian.child.debug"; do
  if adb -s "$TARGET" shell pm list packages | grep -q "^package:${candidate}$"; then
    PACKAGE="$candidate"
    break
  fi
done

if [ -z "$PACKAGE" ]; then
  fail "设备上未安装被控端。"
  echo "  已尝试这两个包名：com.zzl.guardian.child（release）、com.zzl.guardian.child.debug（debug）"
  echo "  请先安装 app-child-debug.apk 或 app-child-release.apk，并至少打开一次。"
  echo "  安装后可用 adb shell pm list packages | grep guardian 确认包名。"
  exit 1
fi
ok "已安装：$PACKAGE"

RECEIVER="${PACKAGE}/${RECEIVER_CLASS}"

# ---------- 3. 检查是否已有 Device Owner ----------

info "检查现有 Device Owner…"
OWNER_LINE=""
# dpm list-owners 在新版本上可直接列出，老版本回退到 dumpsys
OWNER_LINE="$(adb -s "$TARGET" shell dpm list-owners 2>/dev/null | grep -i 'ComponentInfo' | head -3 || true)"
if [ -z "$OWNER_LINE" ]; then
  OWNER_LINE="$(adb -s "$TARGET" shell dumpsys device_policy 2>/dev/null | grep -i 'Device Owner' | head -3 || true)"
fi

if printf '%s' "$OWNER_LINE" | grep -q "$PACKAGE"; then
  ok "本应用已经是 Device Owner，无需重复激活。"
  exit 0
fi
if [ -n "$OWNER_LINE" ]; then
  warn "设备上已存在 Device Owner："
  printf '%s\n' "$OWNER_LINE"
  echo ""
  echo "  系统不允许同时存在两个 Device Owner。"
  echo "  要继续，需要先恢复出厂设置（会清空设备数据）。"
  printf '  确认已了解并继续？(yes/no) '
  read -r ANSWER
  [ "$ANSWER" = "yes" ] || { info "已取消"; exit 0; }
fi

# ---------- 4. 检查账号（系统会因此拒绝激活） ----------
#
# 用「警告 + 确认」而不是硬退出：dumpsys account 会把系统内置账号
# 一并列出，直接判失败会误伤本来能激活的设备。

info "检查设备上是否添加过账号…"
ACCOUNTS="$(adb -s "$TARGET" shell dumpsys account 2>/dev/null | grep -c 'Account {' || true)"
if [ "${ACCOUNTS:-0}" -gt 0 ]; then
  warn "设备上检测到 $ACCOUNTS 条账号记录。"
  echo "  Android 在设备存在**用户账号**时会拒绝设置 Device Owner。"
  echo "  请先在「设置 → 账号」里移除全部账号（系统内置账号无法移除，见下方说明）。"
  echo ""
  printf '  如果上面已经确认没有用户账号，可继续尝试？(yes/no) '
  read -r ANSWER
  [ "$ANSWER" = "yes" ] || { info "已取消。请移除账号后重试。"; exit 0; }
else
  ok "未检测到账号"
fi

# ---------- 5. 执行激活 ----------

LOG="$(mktemp 2>/dev/null || echo /tmp/zzl-dpm.$$.log)"

info "正在设置为设备所有者…"
info "组件：$RECEIVER"
if adb -s "$TARGET" shell dpm set-device-owner "$RECEIVER" 2>&1 | tee "$LOG" | grep -q 'Success'; then
  ok "已成功设置为设备所有者"
else
  fail "激活失败。以下是 adb 的原始输出："
  echo "----------------------------------------"
  cat "$LOG"
  echo "----------------------------------------"
  echo ""
  echo "常见原因："
  echo "  · Not allowed to set the device owner because there are already some accounts"
  echo "      → 设备上有账号，先在设置里移除"
  echo "  · Not allowed to set the device owner because it is already set"
  echo "      → 已有其他 Device Owner，需要恢复出厂设置"
  echo "  · Trying to set the device owner, but the user is not the primary user"
  echo "      → 当前不是主用户，请切回主用户重试"
  echo "  · Unknown admin: ComponentInfo{...}"
  echo "      → 接收器类名不对。期望的类是 $RECEIVER_CLASS"
  exit 1
fi

# ---------- 6. 验证 ----------

info "验证激活结果…"
if adb -s "$TARGET" shell dpm list-owners 2>/dev/null | grep -q "$PACKAGE" \
   || adb -s "$TARGET" shell dumpsys device_policy 2>/dev/null | grep -q "$PACKAGE"; then
  ok "验证通过：$PACKAGE 已是本机的 Device Owner"
  echo ""
  echo "接下来："
  echo "  1. 在设备上打开掌中灵，确认「防卸载加固」显示为「设备所有者模式」"
  echo "  2. 在控制端的「设备加固」里设置三级离线密码（务必设置！）"
  echo "  3. 在设备上尝试卸载掌中灵，应当无法卸载"
  echo ""
  echo "⚠️ 请务必记住离线超级密码。"
  echo "   Device Owner 激活后，忘记密码且完全离线时只能恢复出厂设置。"
else
  warn "adb 报告成功，但未能从设备策略中确认。"
  echo "  请在设备上打开掌中灵，查看「防卸载加固」显示的模式。"
fi
