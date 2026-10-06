# ============================================================
#  掌中灵守护检查脚本 (zzl_guard.ps1)
#  功能: 检查掌中灵「无障碍」和「防卸载」状态，关了可一键打开
#        附加: 授予 WRITE_SECURE_SETTINGS 权限 / 一键卸载掌中灵
#  设备: 联想平板 TB372FC (ZUI16)  包名: com.zzl.guardian.child
#  用法: 双击 掌中灵检查.bat            → 全部检查 + 菜单
#        powershell -File zzl_guard.ps1 grant     → 直接授权
#        powershell -File zzl_guard.ps1 uninstall → 直接卸载
# ============================================================

param([string]$Action = "")

$pkg   = "com.zzl.guardian.child"
$svc   = "$pkg/com.zzl.guardian.child.service.GuardAccessibilityService"
$admin = "$pkg/.admin.GuardDeviceAdminReceiver"

# 关键：adb 输出是 UTF-8，中文 Windows 的 PowerShell 默认按 GBK 解码会乱码，
# 导致 UI 自动化找不到中文控件，必须先切换控制台编码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

Write-Host ""
Write-Host "======== 掌中灵守护工具 ========"

# ---------- 1. 定位 adb ----------
$adbPaths = @(
    (Join-Path $PSScriptRoot "adb_tools\platform-tools\adb.exe"),
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
)
$adb = $null
foreach ($p in $adbPaths) { if (Test-Path $p) { $adb = $p; break } }
if (-not $adb) {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $adb = $cmd.Source }
}
if (-not $adb) {
    Write-Host "[X] 未找到 adb，请确认 adb_tools 目录未被移动或删除" -ForegroundColor Red
    exit 1
}

# ---------- 2. 设备连接检查 ----------
$devOut   = (& $adb devices | Out-String)
$devLines = @(($devOut -split "`r?`n") | Where-Object { $_ -match "^\S+\s+(device|unauthorized|offline)" })
if ($devLines.Count -eq 0) {
    Write-Host "[X] 未检测到平板，请检查：" -ForegroundColor Red
    Write-Host "    1. 用数据线连接（纯充电线不行），换电脑后置USB口直插"
    Write-Host "    2. 平板下拉通知栏，USB 连接方式选「传输文件(MTP)」"
    Write-Host "    3. 平板已开启 USB 调试、屏幕已解锁"
    exit 1
}
$state = ($devLines[0] -split "\s+")[1]
if ($state -ne "device") {
    Write-Host "[X] 平板状态异常: $state" -ForegroundColor Red
    if ($state -eq "unauthorized") {
        Write-Host "    请解锁平板，在弹窗「允许 USB 调试吗」中勾选一律允许并点允许"
    }
    exit 1
}
Write-Host "[OK] 平板已连接: $($devLines[0])" -ForegroundColor Green

# ---------- 通用工具函数 ----------
# 唤醒屏幕并收起下拉的通知面板/锁屏
function Wake-Screen {
    # USB 连接期间屏幕常亮，防止 UI 自动化中途熄屏失败
    & $adb shell svc power stayon true 2>$null | Out-Null
    & $adb shell input keyevent KEYCODE_WAKEUP | Out-Null
    Start-Sleep -Milliseconds 500
    & $adb shell input keyevent 82 | Out-Null
    & $adb shell wm dismiss-keyguard 2>$null | Out-Null
    Start-Sleep -Milliseconds 800
    & $adb shell cmd statusbar collapse 2>$null | Out-Null
    Start-Sleep -Milliseconds 500
    # 若仍被面板遮挡再收一次
    $focus = (& $adb shell dumpsys window | Out-String)
    if ($focus -match "mCurrentFocus=.*NotificationShade") {
        & $adb shell input swipe 920 2800 920 900 250 2>$null | Out-Null
        Start-Sleep -Milliseconds 800
        & $adb shell cmd statusbar collapse 2>$null | Out-Null
        Start-Sleep -Milliseconds 500
    }
}

# 恢复屏幕休眠策略（自动化结束后调用）
function Restore-Screen {
    & $adb shell svc power stayon default 2>$null | Out-Null
}

# 抓取当前屏幕 UI，返回指定文字节点的中心坐标；找不到返回 $null
function Get-UiPoint([string]$text) {
    & $adb shell uiautomator dump /sdcard/ui_dump.xml 2>$null | Out-Null
    Start-Sleep -Milliseconds 400
    $xml = (& $adb shell cat /sdcard/ui_dump.xml | Out-String)
    # 转义正则特殊字符
    $esc = [regex]::Escape($text)
    if ($xml -match ('<node[^>]*text="' + $esc + '"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')) {
        $x = ([int]$Matches[1] + [int]$Matches[3]) / 2
        $y = ([int]$Matches[2] + [int]$Matches[4]) / 2
        return @([int]$x, [int]$y)
    }
    return $null
}

# 点击屏幕上指定文字的中心点；成功返回 $true
function Tap-Text([string]$text) {
    $pt = Get-UiPoint $text
    if ($pt) {
        & $adb shell input tap $pt[0] $pt[1] | Out-Null
        Start-Sleep -Milliseconds 1500
        return $true
    }
    return $false
}

# ---------- 功能: 授予 WRITE_SECURE_SETTINGS ----------
function Grant-SecureSettings {
    Write-Host ""
    Write-Host "---- 授予 WRITE_SECURE_SETTINGS 权限 ----"
    $installed = (& $adb shell pm list packages $pkg | Out-String)
    if ($installed -notmatch "com\.zzl\.guardian\.child") {
        Write-Host "[X] 掌中灵未安装，无需授权" -ForegroundColor Red
        return
    }
    & $adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS 2>&1 | Out-Null
    $chk = (& $adb shell dumpsys package $pkg | Out-String)
    if ($chk -match "WRITE_SECURE_SETTINGS: granted=true") {
        Write-Host "[OK] WRITE_SECURE_SETTINGS 已授予" -ForegroundColor Green
    } elseif ($chk -match "WRITE_SECURE_SETTINGS: granted=false") {
        Write-Host "[X] 授权失败：该版本未在清单中声明此权限（需重装支持的新版本）" -ForegroundColor Red
    } else {
        Write-Host "[!] 未检测到该权限声明，可能是旧版本掌中灵" -ForegroundColor Yellow
    }
}

# ---------- 功能: 卸载掌中灵 ----------
function Uninstall-Zzl {
    Write-Host ""
    Write-Host "---- 卸载掌中灵 ----"
    $installed = (& $adb shell pm list packages $pkg | Out-String)
    if ($installed -notmatch "com\.zzl\.guardian\.child") {
        Write-Host "[OK] 掌中灵未安装，无需卸载" -ForegroundColor Green
        return
    }
    Write-Host "[警告] 即将卸载掌中灵，本地数据（登录/配对/策略）将全部丢失！" -ForegroundColor Yellow
    $ans = Read-Host "       确定要卸载吗? (Y/N)"
    if ($ans -notmatch "^[Yy]" -and $ans -ne "是") {
        Write-Host "已取消卸载"
        return
    }

    Wake-Screen

    # 第一步：若设备管理器已激活，先通过设置 UI 停用（adb 无法直接停用）
    try {
    $polOut = (& $adb shell dumpsys device_policy | Out-String)
    if ($polOut -match "com\.zzl\.guardian\.child/\.admin\.") {
        Write-Host "[..] 防卸载已激活，正在通过平板设置自动停用（请勿操作平板）..."
        Wake-Screen
        & $adb shell am start -a android.settings.SETTINGS | Out-Null
        Start-Sleep -Seconds 2
        # 设置应用可能从子页面恢复（如 WLAN 页），找不到目标就按返回键回主页再重试
        $retries = 0
        while (-not (Get-UiPoint "安全和紧急情况") -and $retries -lt 3) {
            & $adb shell input keyevent KEYCODE_BACK | Out-Null
            Start-Sleep -Milliseconds 1000
            & $adb shell am start -a android.settings.SETTINGS | Out-Null
            Start-Sleep -Seconds 1
            $retries++
        }

        $steps = @("安全和紧急情况", "更多安全设置", "设备管理应用", "掌中灵", "停用此设备管理应用", "确定")
        foreach ($s in $steps) {
            if (Tap-Text $s) {
                Write-Host "     已点击: $s"
            } else {
                Write-Host "[X] 自动化点击中断：屏幕上找不到「$s」" -ForegroundColor Red
                Write-Host "    可能原因：屏幕被遮挡/弹窗/未唤醒，或设置界面结构变化"
                Write-Host "    请手动在平板上完成：设置 → 安全和紧急情况 → 更多安全设置 → 设备管理应用 → 停用掌中灵，然后重新运行本脚本"
                return
            }
        }
        Start-Sleep -Seconds 1
        $polRe = (& $adb shell dumpsys device_policy | Out-String)
        if ($polRe -match "com\.zzl\.guardian\.child/\.admin\.") {
            Write-Host "[X] 停用未生效，请手动完成后再试" -ForegroundColor Red
            return
        }
        Write-Host "[OK] 防卸载已停用" -ForegroundColor Green
    } else {
        Write-Host "[..] 防卸载未激活，可直接卸载"
    }

    # 第二步：卸载
    $r = (& $adb shell pm uninstall $pkg 2>&1 | Out-String)
    if ($r -match "Success") {
        Write-Host "[OK] 掌中灵已卸载" -ForegroundColor Green
    } else {
        Write-Host "[X] 卸载失败: $($r.Trim())" -ForegroundColor Red
        return
    }

    # 第三步：清理无障碍残留配置
    & $adb shell settings delete secure enabled_accessibility_services 2>$null | Out-Null
    & $adb shell settings put secure accessibility_enabled 0 2>$null | Out-Null
    Write-Host "[OK] 无障碍残留配置已清理" -ForegroundColor Green

    # 第四步：验证
    $gone = (& $adb shell pm list packages $pkg | Out-String)
    if ($gone -notmatch "com\.zzl\.guardian\.child") {
        Write-Host "[OK] 验证通过：平板上已无掌中灵" -ForegroundColor Green
    } else {
        Write-Host "[!] 包仍存在，请检查" -ForegroundColor Yellow
    }
    } finally {
        Restore-Screen
    }
}

# ---------- 主流程 ----------
if ($Action -eq "grant") {
    Grant-SecureSettings
    exit 0
}
if ($Action -eq "uninstall") {
    Uninstall-Zzl
    exit 0
}

# ---------- 3. 无障碍检查 ----------
$a11yOut = (& $adb shell dumpsys accessibility | Out-String)
if ($a11yOut -match "Enabled services:\{[^}]*com\.zzl\.guardian\.child") {
    Write-Host "[OK] 掌中灵无障碍: 已开启" -ForegroundColor Green
} else {
    Write-Host "[!] 掌中灵无障碍: 已关闭" -ForegroundColor Yellow
    if ($Action -ne "check") {
        $ans = Read-Host "     是否现在打开? (Y/N)"
        if ($ans -match "^[Yy]" -or $ans -eq "是") {
            & $adb shell appops set $pkg ACCESS_RESTRICTED_SETTINGS allow
            & $adb shell settings put secure enabled_accessibility_services $svc
            & $adb shell settings put secure accessibility_enabled 1
            Start-Sleep -Seconds 2
            $re = (& $adb shell dumpsys accessibility | Out-String)
            if ($re -match "Enabled services:\{[^}]*com\.zzl\.guardian\.child") {
                Write-Host "     [OK] 无障碍已打开并绑定成功" -ForegroundColor Green
            } else {
                Write-Host "     [X] 打开失败，请重新运行一次或找 WorkBuddy 排查" -ForegroundColor Red
            }
        }
    }
}

# ---------- 4. 防卸载(设备管理器)检查 ----------
$polOut = (& $adb shell dumpsys device_policy | Out-String)
if ($polOut -match "com\.zzl\.guardian\.child/\.admin\.") {
    Write-Host "[OK] 掌中灵防卸载(设备管理器): 已激活" -ForegroundColor Green
} else {
    Write-Host "[!] 掌中灵防卸载(设备管理器): 未激活" -ForegroundColor Yellow
    if ($Action -ne "check") {
        $ans2 = Read-Host "     是否现在激活? (Y/N)"
        if ($ans2 -match "^[Yy]" -or $ans2 -eq "是") {
            $r = (& $adb shell dpm set-active-admin $admin 2>&1 | Out-String)
            if ($r -match "Success|成功") {
                Write-Host "     [OK] 防卸载已激活" -ForegroundColor Green
            } else {
                Write-Host "     [X] 激活失败: $r" -ForegroundColor Red
            }
        }
    }
}

# ---------- 5. 功能菜单 ----------
if ($Action -eq "check") { exit 0 }

Write-Host ""
Write-Host "-------- 附加功能 --------"
Write-Host "  [1] 授予 WRITE_SECURE_SETTINGS 权限（让掌中灵能自己维持系统设置）"
Write-Host "  [2] 卸载掌中灵（自动停用防卸载 → 卸载 → 清理残留）"
Write-Host "  [0] 退出"
$choice = Read-Host "请选择 (0/1/2，直接回车退出)"
switch ($choice) {
    "1" { Grant-SecureSettings }
    "2" { Uninstall-Zzl }
    default { }
}

Write-Host ""
Write-Host "======== 完成 ========"
