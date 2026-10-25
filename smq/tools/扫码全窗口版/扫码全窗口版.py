# -*- coding: utf-8 -*-
"""
扫码全窗口版 v1.0
=================
挂在后台，在任何窗口扫码都能自动变成正确中文。

设计原则（针对上一版踩过的坑）：
  1. 钩子回调里只做「缓存 + 返回拦截」，不做任何耗时操作、不发送按键。
     重发全部放到独立线程，所以不会卡住键盘、也不会关不掉。
  2. 只有连续快速按键（扫描枪特征）才会被接管；人工打字原样放行。
  3. 中途出任何异常，把已缓存的按键原样补发回去，绝不吞内容。
  4. 只有「还原成功」才替换；还原不出来就原样放行。
"""
import ctypes
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox
from ctypes import wintypes as wt

APP = "扫码全窗口版"
HOOK_THREAD_ID = None

# ---------------------------------------------------------------------------
# 乱码还原（与手动版同一套算法，支持 CP437 / CP850 / Latin-1 / UTF-8 误判）
# ---------------------------------------------------------------------------
def _is_cjk(ch):
    return "\u4e00" <= ch <= "\u9fff" or "\u3400" <= ch <= "\u4dbf"


def _score(text):
    if not text:
        return -1e9
    bad = text.count("\ufffd") + text.count("\x00")
    cjk = sum(1 for c in text if _is_cjk(c))
    printable = sum(1 for c in text if c.isprintable() or c in "\r\n\t")
    weird = sum(1 for c in text if ord(c) >= 0x7F and not _is_cjk(c))
    return cjk * 3.0 + printable * 0.6 - bad * 8.0 - weird * 2.5


def _has_weird(text):
    return any(ord(c) >= 0x7F and not _is_cjk(c) and c not in "\r\n\t"
               for c in text)


def repair(text):
    """返回 (最佳文本, 方式名)。方式名为空表示没改动。"""
    text = (text or "").strip("\r\n")
    if not text:
        return text, ""
    cands = []
    if not _has_weird(text):
        # 已经是汉字/ASCII，只可能是「UTF-8 字节被按 GBK 显示」
        try:
            raw = text.encode("gbk")
        except (UnicodeEncodeError, LookupError):
            return text, ""
        try:
            out = raw.decode("utf-8")
        except (UnicodeDecodeError, LookupError):
            return text, ""
        if out and out != text and "\ufffd" not in out and any(_is_cjk(c) for c in out):
            return out, "gbk→utf-8"
        return text, ""

    # 字节被当成单字节字符显示：CP437 / CP850 必须排在 latin-1 前面
    for enc_in in ("cp437", "cp850", "cp1252", "latin-1"):
        try:
            raw = text.encode(enc_in)
        except (UnicodeEncodeError, LookupError):
            continue
        for enc_out in ("utf-8", "gbk", "gb18030"):
            try:
                out = raw.decode(enc_out)
            except (UnicodeDecodeError, LookupError):
                continue
            if not out or out == text or "\ufffd" in out:
                continue
            if any(_is_cjk(c) for c in out):
                cands.append((_score(out), out, f"{enc_in}→{enc_out}"))
    if not cands:
        return text, ""
    cands.sort(reverse=True)
    if cands[0][0] <= _score(text):
        return text, ""
    return cands[0][1], cands[0][2]


# ---------------------------------------------------------------------------
# 键盘钩子
# ---------------------------------------------------------------------------
user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

WH_KEYBOARD_LL = 13
WM_KEYDOWN = 0x0100
WM_SYSKEYDOWN = 0x0104
VK_RETURN = 0x0D
VK_SHIFT, VK_CONTROL, VK_MENU = 0x10, 0x11, 0x12
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004

HOOKPROC = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, ctypes.c_int, wt.WPARAM, wt.LPARAM)


class KBDLLHOOKSTRUCT(ctypes.Structure):
    _fields_ = [("vkCode", wt.DWORD), ("scanCode", wt.DWORD),
                ("flags", wt.DWORD), ("time", wt.DWORD),
                ("dwExtraInfo", ctypes.c_size_t)]


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wt.WORD), ("wScan", wt.WORD), ("dwFlags", wt.DWORD),
                ("time", wt.DWORD), ("dwExtraInfo", ctypes.c_size_t)]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wt.LONG), ("dy", wt.LONG), ("mouseData", wt.DWORD),
                ("dwFlags", wt.DWORD), ("time", wt.DWORD),
                ("dwExtraInfo", ctypes.c_size_t)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wt.DWORD), ("wParamL", wt.WORD), ("wParamH", wt.WORD)]


class _U(ctypes.Union):
    _fields_ = [("ki", KEYBDINPUT), ("mi", MOUSEINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wt.DWORD), ("u", _U)]


user32.SendInput.argtypes = [wt.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
user32.SendInput.restype = wt.UINT
user32.SetWindowsHookExW.argtypes = [ctypes.c_int, HOOKPROC, wt.HINSTANCE, wt.DWORD]
user32.SetWindowsHookExW.restype = wt.HHOOK
user32.CallNextHookEx.restype = ctypes.c_ssize_t
user32.CallNextHookEx.argtypes = [wt.HHOOK, ctypes.c_int, wt.WPARAM, wt.LPARAM]
user32.UnhookWindowsHookEx.argtypes = [wt.HHOOK]
user32.GetMessageW.argtypes = [ctypes.POINTER(wt.MSG), wt.HWND, wt.UINT, wt.UINT]
user32.GetKeyboardState.argtypes = [ctypes.POINTER(wt.BYTE)]
user32.GetForegroundWindow.restype = wt.HWND
user32.GetWindowThreadProcessId.argtypes = [wt.HWND, ctypes.POINTER(wt.DWORD)]
user32.GetWindowThreadProcessId.restype = wt.DWORD
user32.GetKeyboardLayout.argtypes = [wt.DWORD]
user32.GetKeyboardLayout.restype = wt.HKL
user32.ToUnicodeEx.argtypes = [wt.UINT, wt.UINT, ctypes.POINTER(wt.BYTE),
                               wt.LPWSTR, ctypes.c_int, wt.UINT, wt.HKL]
user32.ToUnicodeEx.restype = ctypes.c_int


def type_unicode(text, enter=False):
    """把文本以 Unicode 方式键入到当前前台窗口"""
    items = []
    for ch in text:
        cu = ord(ch)
        items.append((0, cu & 0xFFFF, KEYEVENTF_UNICODE))
        items.append((0, cu & 0xFFFF, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))
    if enter:
        items.append((VK_RETURN, 0, 0))
        items.append((VK_RETURN, 0, KEYEVENTF_KEYUP))
    if not items:
        return
    arr = (INPUT * len(items))()
    for i, (vk, scan, flags) in enumerate(items):
        arr[i].type = 1
        arr[i].ki.wVk = vk
        arr[i].ki.wScan = scan
        arr[i].ki.dwFlags = flags
        arr[i].ki.dwExtraInfo = 0
    user32.SendInput(len(items), arr, ctypes.sizeof(INPUT))


def type_scan_codes(keys):
    """把缓存的原始按键原样重发（兜底，保证不吞内容）"""
    items = []
    for vk, scan, flags in keys:
        up = bool(flags & 0x80)
        f = KEYEVENTF_KEYUP if up else 0
        if flags & 0x01:
            f |= 0x0001
        items.append((vk, scan, f))
    if not items:
        return
    arr = (INPUT * len(items))()
    for i, (vk, scan, flags) in enumerate(items):
        arr[i].type = 1
        arr[i].ki.wVk = 0
        arr[i].ki.wScan = scan
        arr[i].ki.dwFlags = flags | 0x0008  # SCANCODE
        arr[i].ki.dwExtraInfo = 0
    user32.SendInput(len(items), arr, ctypes.sizeof(INPUT))


class ScannerHook:
    """低级键盘钩子：只缓存与判定，不重发、不做耗时操作"""

    FAST = 0.018        # 相邻两键间隔小于此值 → 认为是扫描枪
    IDLE = 0.090        # 静默超过此值 → 这一段输入结束
    MIN_LEN = 5         # 少于这个字符数不当扫描处理

    def __init__(self, on_result, on_status):
        self.enabled = True
        self.injecting = False
        self.pending = []       # [(vk, scan, flags, char)]
        self.last_t = 0.0
        self.lock = threading.Lock()
        self.hook = None
        self.thread = None
        self.on_result = on_result
        self.on_status = on_status
        self._stop = False

    # ---------- 装 / 卸钩子
    def start(self):
        self.thread = threading.Thread(target=self._message_loop, daemon=True)
        self.thread.start()
        threading.Thread(target=self._watchdog, daemon=True).start()

    def _message_loop(self):
        self._proc = HOOKPROC(self._callback)
        self.hook = user32.SetWindowsHookExW(WH_KEYBOARD_LL, self._proc, None, 0)
        if not self.hook:
            self.on_status("钩子安装失败，请用管理员权限重试")
            return
        self.on_status("已就绪")
        msg = wt.MSG()
        while not self._stop:
            r = user32.GetMessageW(ctypes.byref(msg), None, 0, 0)
            if r <= 0:
                break
            user32.TranslateMessage(ctypes.byref(msg))
            user32.DispatchMessageW(ctypes.byref(msg))

    def stop(self):
        self._stop = True
        if self.hook:
            user32.UnhookWindowsHookEx(self.hook)
            self.hook = None

    # ---------- 取当前字符
    def _char(self, vk, scan):
        try:
            ks = (wt.BYTE * 256)()
            user32.GetKeyboardState(ks)
            ks[vk] = 0x80
            hwnd = user32.GetForegroundWindow()
            tid = user32.GetWindowThreadProcessId(hwnd, None)
            hkl = user32.GetKeyboardLayout(tid)
            buf = ctypes.create_unicode_buffer(8)
            n = user32.ToUnicodeEx(vk, scan, ks, buf, 8, 0x2, hkl)
            if n > 0:
                return buf.value[:n]
        except Exception:
            pass
        return ""

    # ---------- 钩子回调：只缓存
    def _callback(self, code, wparam, lparam):
        if code != 0:
            return user32.CallNextHookEx(None, code, wparam, lparam)
        try:
            if not self.enabled or self.injecting:
                return user32.CallNextHookEx(None, code, wparam, lparam)

            kb = ctypes.cast(lparam, ctypes.POINTER(KBDLLHOOKSTRUCT)).contents
            vk, scan, flags = kb.vkCode, kb.scanCode, kb.flags
            down = wparam in (WM_KEYDOWN, WM_SYSKEYDOWN)
            if flags & 0x10:            # 注入事件（本程序重发），直接放行
                return user32.CallNextHookEx(None, code, wparam, lparam)
            if vk in (VK_SHIFT, VK_CONTROL, VK_MENU) or vk in (0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5):
                return user32.CallNextHookEx(None, code, wparam, lparam)
            if not down:
                # 抬起事件：如果这个键被我们拦下了，抬起也要拦
                with self.lock:
                    held = any(k[0] == vk for k in self.pending)
                return 1 if held else user32.CallNextHookEx(None, code, wparam, lparam)

            now = time.time()
            ch = self._char(vk, scan)

            with self.lock:
                gap = now - self.last_t if self.pending else 999
                if vk == VK_RETURN:
                    if self.pending and len(self.pending) >= self.MIN_LEN:
                        keys = list(self.pending)
                        self.pending = []
                        self.last_t = 0
                        self._dispatch(keys, enter=True)
                        return 1
                    keys = list(self.pending)
                    self.pending = []
                    self.last_t = 0
                    if keys:
                        self._dispatch(keys, enter=False, passthrough_enter=True)
                    return user32.CallNextHookEx(None, code, wparam, lparam)

                if self.pending and gap > self.IDLE:
                    # 上一段是人工输入，原样补发后，本键重新开始
                    keys = list(self.pending)
                    self.pending = []
                    self._dispatch(keys, enter=False)
                    gap = 999

                self.pending.append((vk, scan, flags, ch))
                self.last_t = now
                return 1
        except Exception:
            return user32.CallNextHookEx(None, code, wparam, lparam)

    # ---------- 派发给工作线程
    def _dispatch(self, keys, enter=False, passthrough_enter=False):
        if not keys:
            return
        threading.Thread(target=self._handle, args=(keys, enter),
                         daemon=True).start()

    def _watchdog(self):
        """静默超时：把缓存的内容结掉，避免一直卡着"""
        while not self._stop:
            time.sleep(0.02)
            if not self.enabled:
                continue
            with self.lock:
                if not self.pending:
                    continue
                if time.time() - self.last_t < self.IDLE:
                    continue
                keys = list(self.pending)
                self.pending = []
                self.last_t = 0
            self._handle(keys, enter=False)

    # ---------- 真正的处理
    def _handle(self, keys, enter):
        chars = "".join(k[3] for k in keys)
        text = chars.replace("\r", "").replace("\n", "")
        is_scan = len(keys) >= self.MIN_LEN

        fixed, how = ("", "")
        if is_scan and text.strip():
            try:
                fixed, how = repair(text)
            except Exception:
                fixed, how = "", ""

        self.injecting = True
        try:
            time.sleep(0.004)
            if how and fixed and fixed != text:
                type_unicode(fixed, enter=enter)
                self.on_result(text, fixed, how)
            else:
                type_scan_codes([(k[0], k[1], k[2]) for k in keys])
                if enter:
                    type_unicode("", enter=True)
                self.on_result(text, text, "")
        except Exception as e:
            try:
                type_scan_codes([(k[0], k[1], k[2]) for k in keys])
            except Exception:
                pass
            self.on_status(f"处理异常，已原样补发：{e}")
        finally:
            time.sleep(0.002)
            self.injecting = False


# ---------------------------------------------------------------------------
# 界面
# ---------------------------------------------------------------------------
class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"{APP}  v1.0")
        self.geometry("560x330")
        self.resizable(False, False)
        self.configure(bg="#f7f7f5")
        self.attributes("-topmost", True)

        self.lbl_big = tk.Label(self, text="● 正在工作", bg="#f7f7f5",
                                fg="#137333", font=("微软雅黑", 16, "bold"))
        self.lbl_big.pack(pady=(16, 2))
        self.lbl_sub = tk.Label(
            self, bg="#f7f7f5", fg="#555", justify="center",
            font=("微软雅黑", 9),
            text="最小化后挂在后台。在任何窗口扫码，都会自动变成正确中文。")
        self.lbl_sub.pack()

        box = tk.Frame(self, bg="#ffffff", highlightbackground="#e3e3e0",
                       highlightthickness=1)
        box.pack(fill="both", expand=True, padx=14, pady=10)
        tk.Label(box, text="最近一次扫描", bg="#ffffff", fg="#888",
                 font=("微软雅黑", 8)).pack(anchor="w", padx=8, pady=(6, 0))
        self.txt_raw = tk.Text(box, height=2, font=("Consolas", 9), wrap="char",
                               bg="#ffffff", bd=0, fg="#999")
        self.txt_raw.pack(fill="x", padx=8)
        tk.Label(box, text="还原结果", bg="#ffffff", fg="#888",
                 font=("微软雅黑", 8)).pack(anchor="w", padx=8)
        self.txt_fix = tk.Text(box, height=3, font=("Consolas", 10), wrap="char",
                               bg="#ffffff", bd=0, fg="#137333")
        self.txt_fix.pack(fill="x", padx=8, pady=(0, 8))

        row = tk.Frame(self, bg="#f7f7f5")
        row.pack(fill="x", padx=14, pady=(0, 12))
        self.btn_toggle = ttk.Button(row, text="暂停", command=self.toggle)
        self.btn_toggle.pack(side="left")
        ttk.Button(row, text="退出", command=self.quit_app).pack(side="left", padx=8)
        self.lbl_status = tk.Label(row, text="启动中…", bg="#f7f7f5", fg="#666",
                                   font=("微软雅黑", 8))
        self.lbl_status.pack(side="right")

        self.hook = ScannerHook(self._on_result, self._on_status)
        self.hook.start()
        self.protocol("WM_DELETE_WINDOW", self.iconify)

    def _on_result(self, raw, fixed, how):
        def upd():
            self.txt_raw.delete("1.0", "end")
            self.txt_raw.insert("1.0", raw[:200])
            self.txt_fix.delete("1.0", "end")
            self.txt_fix.insert("1.0", fixed[:300])
            self.lbl_status.config(
                text=f"已还原（{how}）" if how else "无需转换，原样输出")
        try:
            self.after(0, upd)
        except Exception:
            pass

    def _on_status(self, text):
        try:
            self.after(0, lambda: self.lbl_status.config(text=text))
        except Exception:
            pass

    def toggle(self):
        self.hook.enabled = not self.hook.enabled
        if self.hook.enabled:
            self.lbl_big.config(text="● 正在工作", fg="#137333")
            self.btn_toggle.config(text="暂停")
            self.lbl_status.config(text="已恢复")
        else:
            self.lbl_big.config(text="○ 已暂停", fg="#999")
            self.btn_toggle.config(text="继续")
            self.lbl_status.config(text="暂停中，按键全部原样放行")

    def quit_app(self):
        try:
            self.hook.enabled = False
            self.hook.stop()
        except Exception:
            pass
        self.destroy()


if __name__ == "__main__":
    app = App()
    app.mainloop()
