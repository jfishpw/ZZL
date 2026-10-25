# -*- coding: utf-8 -*-
"""
扫码自动转中文  v1.0
===================================================================
给最终用户用的极简版：全后台工作，任意窗口扫码，自动把 GBK 乱码换成中文。
零设置（只有一个开关），不需要选目标窗口。

原理
----
  标签二维码里是 GBK 字节。扫描枪在键盘模式下只能一个字符一个字符地"打"，
  于是中文变成了 »¦Í¬ 这类西欧字符。本程序用全局键盘钩子把这串按键**截住**
  （不让它落到输入框里），自己按 GBK 还原成中文，再用 Unicode 方式键入，
  最后补一个回车。输入框从头到尾只看到"中文 + 回车"。

  因为不同型号的枪打出来的按键方式不一样，首次运行会请用户扫一次做标定：
  程序同时记录"按键序列"和"文本框里的结果"，据此确认自己能准确还原，
  并测出扫描枪的击键间隔（用来和人工打字区分开）。

安全设计
--------
  · 只有连续快速击键、并且能还原出中文，才会拦截并替换；否则原样放行。
  · 任何异常（无法还原、中途按下 Esc/方向键等）都会把已经拦下的按键原样补发回去。
  · 不写注册表、不联网、不改系统设置。
"""

import ctypes
import ctypes.wintypes as wt
import json
import os
import threading
import time
import tkinter as tk
from tkinter import ttk

APP_NAME = "扫码自动转中文"
CONFIG_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), APP_NAME)
CONFIG_PATH = os.path.join(CONFIG_DIR, "config.json")

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

# ---------------------------------------------------------------- 常量
WH_KEYBOARD_LL = 13
WM_KEYDOWN, WM_KEYUP = 0x0100, 0x0101
WM_SYSKEYDOWN, WM_SYSKEYUP = 0x0104, 0x0105

KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
KEYEVENTF_SCANCODE = 0x0008
INPUT_KEYBOARD = 1

VK_SHIFT, VK_CONTROL, VK_MENU = 0x10, 0x11, 0x12
VK_LSHIFT, VK_RSHIFT = 0xA0, 0xA1
VK_LCONTROL, VK_RCONTROL = 0xA2, 0xA3
VK_LMENU, VK_RMENU = 0xA4, 0xA5
VK_RETURN = 0x0D
VK_TAB, VK_ESCAPE, VK_BACK = 0x09, 0x1B, 0x08

SENTINEL = 0x5343414E  # 我们注入事件的标记，避免被自己的钩子二次处理

# ---------------------------------------------------------------- 编码还原


def _is_cjk(ch):
    return "\u4e00" <= ch <= "\u9fff"


def _has_high_single_byte(text):
    return any(0x80 <= ord(c) <= 0x17F for c in text)


def _suspect_chars(text):
    """找出"不可能是正常标签内容"的字符：私用区、控制符、替换符。
    出现这些说明字节在传输/合成途中出错了，不是编码问题。"""
    bad = []
    for c in text:
        o = ord(c)
        if 0xE000 <= o <= 0xF8FF:      # 私用区（枪固件映射表的残留）
            bad.append(c)
        elif o < 0x20 and c not in "\t\n\r":
            bad.append(c)
        elif o == 0xFFFD:              # 替换符
            bad.append(c)
    return bad


def _try_decode(raw, encs):
    for e in encs:
        try:
            return raw.decode(e)
        except (UnicodeDecodeError, LookupError):
            continue
    return None


def repair(text):
    """把乱码串还原成中文，返回 (结果, 说明)；无法还原时说明为 ''。"""
    text = (text or "").strip("\r\n")
    if not text:
        return text, ""
    if _has_high_single_byte(text):
        for enc_in in ("latin-1", "cp1252"):
            try:
                raw = text.encode(enc_in)
            except (UnicodeEncodeError, LookupError):
                continue
            for enc_out in ("utf-8", "gbk", "gb18030"):
                out = _try_decode(raw, (enc_out,))
                if out and out != text and "\ufffd" not in out and any(_is_cjk(c) for c in out):
                    return out, f"{enc_in}→{enc_out}"
    else:
        try:
            raw = text.encode("gbk")
        except (UnicodeEncodeError, LookupError):
            raw = None
        if raw:
            out = _try_decode(raw, ("utf-8",))
            if out and out != text and any(_is_cjk(c) for c in out):
                return out, "gbk→utf-8"
    return text, ""

# ---------------------------------------------------------------- 输入注入


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [("wVk", wt.WORD), ("wScan", wt.WORD), ("dwFlags", wt.DWORD),
                ("time", wt.DWORD), ("dwExtraInfo", ctypes.c_void_p)]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [("dx", wt.LONG), ("dy", wt.LONG), ("mouseData", wt.DWORD),
                ("dwFlags", wt.DWORD), ("time", wt.DWORD), ("dwExtraInfo", ctypes.c_void_p)]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wt.DWORD), ("wParamL", wt.WORD), ("wParamH", wt.WORD)]


class _U(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wt.DWORD), ("u", _U)]


def _send(events):
    if not events:
        return
    arr = (INPUT * len(events))(*events)
    user32.SendInput(len(events), arr, ctypes.sizeof(INPUT))


def _mk(vk=0, sc=0, flags=0):
    e = INPUT(type=INPUT_KEYBOARD)
    e.ki = KEYBDINPUT(vk, sc, flags, 0, SENTINEL)
    return e


def type_text(text, enter=False):
    """Unicode 方式键入（不受输入法、键盘布局影响）。"""
    evs = []
    for ch in text:
        code = ord(ch)
        if code > 0xFFFF:
            code -= 0x10000
            codes = [0xD800 + (code >> 10), 0xDC00 + (code & 0x3FF)]
        else:
            codes = [code]
        for c in codes:
            evs.append(_mk(sc=c, flags=KEYEVENTF_UNICODE))
            evs.append(_mk(sc=c, flags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))
        if len(evs) >= 160:
            _send(evs)
            evs = []
            time.sleep(0.012)
    if enter:
        evs.append(_mk(vk=VK_RETURN))
        evs.append(_mk(vk=VK_RETURN, flags=KEYEVENTF_KEYUP))
    _send(evs)


def replay_events(events):
    """把拦下来的原始按键按扫描码原样补发（用于放弃拦截时）。"""
    evs = []
    for ev in events:
        if ev.get("packet"):
            evs.append(_mk(sc=ev["ch"], flags=KEYEVENTF_UNICODE))
            evs.append(_mk(sc=ev["ch"], flags=KEYEVENTF_UNICODE | KEYEVENTF_KEYUP))
            continue
        flags = KEYEVENTF_SCANCODE | (KEYEVENTF_EXTENDEDKEY if ev.get("ext") else 0)
        if ev.get("up"):
            flags |= KEYEVENTF_KEYUP
        evs.append(_mk(sc=ev["sc"], flags=flags))
    _send(evs)

# ---------------------------------------------------------------- 键盘钩子


class KBDLLHOOKSTRUCT(ctypes.Structure):
    _fields_ = [("vkCode", wt.DWORD), ("scanCode", wt.DWORD), ("flags", wt.DWORD),
                ("time", wt.DWORD), ("dwExtraInfo", ctypes.c_void_p)]


HOOKPROC = ctypes.WINFUNCTYPE(ctypes.c_ssize_t, ctypes.c_int, wt.WPARAM, wt.LPARAM)

user32.SetWindowsHookExW.argtypes = [ctypes.c_int, HOOKPROC, wt.HINSTANCE, wt.DWORD]
user32.SetWindowsHookExW.restype = ctypes.c_void_p
user32.CallNextHookEx.argtypes = [ctypes.c_void_p, ctypes.c_int, wt.WPARAM, wt.LPARAM]
user32.CallNextHookEx.restype = ctypes.c_ssize_t
user32.GetMessageW.argtypes = [ctypes.POINTER(wt.MSG), wt.HWND, wt.UINT, wt.UINT]
user32.ToUnicodeEx.argtypes = [wt.UINT, wt.UINT, ctypes.POINTER(ctypes.c_ubyte),
                               wt.LPWSTR, ctypes.c_int, wt.UINT, ctypes.c_void_p]
user32.ToUnicodeEx.restype = ctypes.c_int
user32.GetKeyboardLayout.argtypes = [wt.DWORD]
user32.GetKeyboardLayout.restype = ctypes.c_void_p
user32.GetForegroundWindow.restype = wt.HWND
user32.GetWindowThreadProcessId.argtypes = [wt.HWND, ctypes.POINTER(wt.DWORD)]


class ScannerHook:
    def __init__(self, on_scan, on_status):
        self.on_scan = on_scan           # callback(mojibake, enter_used, events)
        self.on_status = on_status
        self.calibrating = False
        self.enabled = True
        self.records = None              # 标定期间收集的按键事件
        self.threshold = 0.045           # 判定为"扫描"的击键间隔（秒）
        self.min_len = 4

        self._events = []                # 当前突发
        self._scanning = False           # 已确认是扫描，正在拦截
        self._pending = False            # 首个按键暂扣中
        self._t_last = 0.0
        self._last_final = 0.0
        self._lock = threading.RLock()
        self._state = (ctypes.c_ubyte * 256)()
        self._hkl = None
        self._hook = None
        self._thread = None
        self._proc = HOOKPROC(self._callback)

    # ---------- 生命周期
    def start(self):
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()
        threading.Thread(target=self._flusher, daemon=True).start()

    def _run(self):
        self._hook = user32.SetWindowsHookExW(WH_KEYBOARD_LL, self._proc, None, 0)
        if not self._hook:
            self.on_status("钩子安装失败（可能被杀毒软件拦截）")
            return
        msg = wt.MSG()
        while user32.GetMessageW(ctypes.byref(msg), None, 0, 0) > 0:
            user32.TranslateMessage(ctypes.byref(msg))
            user32.DispatchMessageW(ctypes.byref(msg))

    # ---------- 定时器：释放暂扣的首键 / 结束无回车的扫描
    def _flusher(self):
        while True:
            time.sleep(0.012)
            with self._lock:
                if self._pending and not self._scanning:
                    if time.perf_counter() - self._t_last > self.threshold * 1.6:
                        evs, self._events = self._events, []
                        self._pending = False
                        if evs:
                            replay_events(evs)
                elif self._scanning:
                    if time.perf_counter() - self._t_last > 0.35:
                        self._finalize(enter_used=False)

    # ---------- 字符推导
    def _layout(self):
        try:
            hwnd = user32.GetForegroundWindow()
            pid = wt.DWORD(0)
            tid = user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
            hkl = user32.GetKeyboardLayout(tid)
            return hkl or None
        except Exception:
            return None

    def _char_of(self, vk, sc, packet=False):
        if packet:
            return chr(sc & 0xFFFF) if sc else ""
        buf = ctypes.create_unicode_buffer(8)
        n = user32.ToUnicodeEx(vk, sc, self._state, buf, 8, 0, self._hkl)
        return buf.value if n > 0 else ""

    def _track_state(self, vk, down):
        bit = 0x80 if down else 0
        if vk in (VK_SHIFT, VK_LSHIFT, VK_RSHIFT):
            self._state[VK_SHIFT] = bit
            if vk != VK_SHIFT:
                self._state[vk] = bit
        elif vk in (VK_CONTROL, VK_LCONTROL, VK_RCONTROL):
            self._state[VK_CONTROL] = bit
            if vk != VK_CONTROL:
                self._state[vk] = bit
        elif vk in (VK_MENU, VK_LMENU, VK_RMENU):
            self._state[VK_MENU] = bit
            if vk != VK_MENU:
                self._state[vk] = bit

    @staticmethod
    def _is_modifier(vk):
        return vk in (VK_SHIFT, VK_LSHIFT, VK_RSHIFT, VK_CONTROL, VK_LCONTROL,
                      VK_RCONTROL, VK_MENU, VK_LMENU, VK_RMENU, 0x5B, 0x5C)

    # ---------- 钩子回调
    def _callback(self, code, wparam, lparam):
        if code != 0:
            return user32.CallNextHookEx(None, code, wparam, lparam)
        kb = ctypes.cast(lparam, ctypes.POINTER(KBDLLHOOKSTRUCT)).contents
        if kb.dwExtraInfo == SENTINEL:
            return user32.CallNextHookEx(None, code, wparam, lparam)

        down = wparam in (WM_KEYDOWN, WM_SYSKEYDOWN)
        vk, sc = kb.vkCode, kb.scanCode
        ext = bool(kb.flags & 0x01)
        packet = (vk == 0 or vk == 0xE7)   # VK_PACKET：字符直接放在 scanCode 里

        # 只有按下事件参与采集，抬起事件只用于状态跟踪
        if not down:
            if self._is_modifier(vk) and not packet:
                with self._lock:
                    self._track_state(vk, False)
            return user32.CallNextHookEx(None, code, wparam, lparam)

        if packet is False and self._is_modifier(vk):
            with self._lock:
                self._track_state(vk, True)
                if self.calibrating and self.records is not None:
                    self.records.append({"vk": vk, "sc": sc, "ext": ext, "mod": True,
                                         "t": time.perf_counter()})
            return user32.CallNextHookEx(None, code, wparam, lparam)

        now = time.perf_counter()
        self._hkl = self._layout() or self._hkl
        ch = self._char_of(vk, sc, packet)

        # 标定模式：只记录，不拦截
        if self.calibrating and self.records is not None:
            if vk == VK_RETURN:
                self.records.append({"enter": True, "t": now})
            else:
                self.records.append({"vk": vk, "sc": sc, "ext": ext, "ch": ch,
                                     "packet": packet, "t": now})
            return user32.CallNextHookEx(None, code, wparam, lparam)

        if not self.enabled:
            return user32.CallNextHookEx(None, code, wparam, lparam)

        with self._lock:
            # ---- 回车：扫描结束的标志
            if vk == VK_RETURN:
                if self._events and (self._scanning or len(self._events) >= self.min_len):
                    self._finalize(enter_used=True)
                    return 1
                # 刚结束的扫描紧跟的重复回车/换行（CR+LF）一并吃掉
                if now - self._last_final < 0.12:
                    return 1
                # 非扫描：清空状态，正常放行
                self._events = []
                self._scanning = self._pending = False
                return user32.CallNextHookEx(None, code, wparam, lparam)

            # ---- 其它控制键：中止当前突发
            if not ch or (vk in (VK_TAB, VK_ESCAPE, VK_BACK)) or (ch and ch < " " and ch not in "\r\t"):
                if self._events:
                    evs, self._events = self._events, []
                    self._scanning = self._pending = False
                    replay_events(evs)
                return user32.CallNextHookEx(None, code, wparam, lparam)

            ev = {"vk": vk, "sc": sc, "ext": ext, "ch": ch, "packet": packet}
            if not self._events:
                self._events = [ev]
                self._pending = True
                self._t_last = now
                return 1                      # 暂扣第一个键
            gap = now - self._t_last
            if gap <= self.threshold or self._scanning:
                self._events.append(ev)
                self._t_last = now
                self._pending = False
                if len(self._events) >= 3:
                    self._scanning = True
                return 1                      # 拦截
            # 间隔太大：不是扫描，把之前扣下的补发，当前键放行
            evs, self._events = self._events, []
            self._scanning = self._pending = False
            replay_events(evs)
            return user32.CallNextHookEx(None, code, wparam, lparam)

    # ---------- 结算
    def _finalize(self, enter_used):
        events = self._events
        self._events = []
        self._scanning = self._pending = False
        self._last_final = time.perf_counter()
        if not events:
            return
        mojibake = "".join(e.get("ch", "") for e in events)
        fixed, how = repair(mojibake)
        if not how:
            # 还原不出中文 —— 原样补发，绝不吃掉用户输入
            replay_events(events)
            if enter_used:
                _send([_mk(vk=VK_RETURN), _mk(vk=VK_RETURN, flags=KEYEVENTF_KEYUP)])
            self.on_status("这一条不是 GBK 中文，已原样放行")
            return
        self.on_scan(mojibake, fixed, how, enter_used, len(events))


# ---------------------------------------------------------------- 界面


class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(APP_NAME)
        self.geometry("560x330")
        self.resizable(False, False)
        try:
            self.tk.call("tk", "scaling", 1.3)
        except Exception:
            pass
        self.configure(bg="#f7f7f5")
        self.cfg = self._load_cfg()
        self.hook = ScannerHook(self._on_scan, self._set_status)
        if self.cfg:
            self.hook.threshold = self.cfg.get("threshold", 0.045)
        self.count = 0
        self._build()
        self.hook.start()
        if self.cfg and self.cfg.get("ready"):
            self.after(300, self._enter_work_mode)
        else:
            self.after(300, self._enter_calibration)

    # ---------- 配置
    @staticmethod
    def _load_cfg():
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return None

    @staticmethod
    def _save_cfg(data):
        try:
            os.makedirs(CONFIG_DIR, exist_ok=True)
            with open(CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
        except Exception:
            pass

    # ---------- UI
    def _build(self):
        self.frame = tk.Frame(self, bg="#f7f7f5")
        self.frame.pack(fill="both", expand=True, padx=18, pady=14)

        self.lbl_big = tk.Label(self.frame, text="", font=("微软雅黑", 15, "bold"),
                                bg="#f7f7f5", fg="#0b5c28", anchor="w")
        self.lbl_big.pack(fill="x")

        self.lbl_sub = tk.Label(self.frame, text="", font=("微软雅黑", 10),
                                bg="#f7f7f5", fg="#444", justify="left", anchor="w",
                                wraplength=510)
        self.lbl_sub.pack(fill="x", pady=(6, 8))

        # 标定区
        self.box_cal = tk.Frame(self.frame, bg="#f7f7f5")
        self.box_cal.pack(fill="x")
        tk.Label(self.box_cal, text="请对着下面这个框扫一次标签：", bg="#f7f7f5",
                 font=("微软雅黑", 10)).pack(anchor="w")
        self.txt_cal = tk.Text(self.box_cal, height=3, font=("Consolas", 12))
        self.txt_cal.pack(fill="x", pady=4)
        self.txt_cal.bind("<Return>", self._cal_return)

        # 工作区
        self.box_work = tk.Frame(self.frame, bg="#f7f7f5")

        self.lbl_last = tk.Label(self.box_work, text="最近一条：—", bg="#f7f7f5",
                                 font=("微软雅黑", 10), fg="#333", justify="left",
                                 anchor="w", wraplength=510)
        self.lbl_last.pack(fill="x", pady=(0, 10))

        bar = tk.Frame(self.box_work, bg="#f7f7f5")
        bar.pack(fill="x", pady=(4, 0))
        self.btn_toggle = tk.Button(bar, text="暂停", width=10, command=self._toggle,
                                    font=("微软雅黑", 10))
        self.btn_toggle.pack(side="left")
        tk.Button(bar, text="退出", width=10, command=self._quit,
                  font=("微软雅黑", 10)).pack(side="left", padx=8)
        self.lbl_count = tk.Label(bar, text="已转换 0 条", bg="#f7f7f5",
                                  font=("微软雅黑", 10), fg="#666")
        self.lbl_count.pack(side="right")

        self.lbl_status = tk.Label(self.frame, text="", bg="#f7f7f5", fg="#888",
                                   font=("微软雅黑", 9), anchor="w")
        self.lbl_status.pack(fill="x", side="bottom", pady=(8, 0))
        self.protocol("WM_DELETE_WINDOW", self._minimize)

    # ---------- 标定
    def _enter_calibration(self):
        self.hook.calibrating = True
        self.hook.records = []
        self.lbl_big.config(text="① 初始化（只需一次）")
        self.lbl_sub.config(
            text="光标放在下面的框里，对着标签扫一次。\n"
                 "程序会记录这一扫的按键方式，确认能准确还原成中文。")
        self.box_cal.pack(fill="x")
        self.box_work.pack_forget()
        self.txt_cal.focus_set()
        self._set_status("等待扫码…")

    def _cal_return(self, _e=None):
        self.after(120, self._do_calibrate)
        return "break"

    def _do_calibrate(self):
        records = self.hook.records or []
        self.hook.records = []          # 只看这一次的按键，避免重扫时累积
        text = self.txt_cal.get("1.0", "end-1c").replace("\r", "").replace("\n", "")
        self.txt_cal.delete("1.0", "end")

        # 事件间隔（用于区分人工打字）
        stamps = [r["t"] for r in records if not r.get("mod") and not r.get("enter")]
        gaps = [b - a for a, b in zip(stamps, stamps[1:]) if b - a > 0]
        median = sorted(gaps)[len(gaps) // 2] if gaps else 0.02

        chars = "".join(r.get("ch", "") for r in records
                        if not r.get("mod") and not r.get("enter") and r.get("ch"))
        reconstructed = (chars == text)

        try:
            os.makedirs(CONFIG_DIR, exist_ok=True)
            with open(os.path.join(CONFIG_DIR, "cal_debug.txt"), "w", encoding="utf-8") as f:
                f.write(f"text(len={len(text)})={text!r}\n")
                f.write(f"chars(len={len(chars)})={chars!r}\n")
                f.write(f"events={len(records)} median_gap={median*1000:.1f}ms\n")
        except Exception:
            pass

        # 以「文本框实际收到的内容」为准：经 VK_PACKET 送来的汉字在钩子里拿不到
        if len(text) >= len(chars) or any(_is_cjk(c) for c in text):
            source = text
        else:
            source = chars
        if not source:
            source = text or chars

        if not source.strip():
            self._set_status("没读到内容，请把光标放进上面的框里再扫一次")
            return

        fixed, how = repair(source)
        thr = max(0.02, min(median * 2.5, 0.09))

        if how:
            self.cfg = {"ready": True, "threshold": thr, "recon": reconstructed,
                        "how": how, "sample": source[:80]}
            self._save_cfg(self.cfg)
            self.hook.calibrating = False
            self.hook.threshold = thr
            self._enter_work_mode()
            extra = "" if reconstructed else "（文本框会吞掉部分字符，已按按键流标定，不影响使用）"
            self._set_status(f"初始化完成：击键间隔约 {median*1000:.0f} ms，还原方式 {how}{extra}")
            self.lbl_last.config(text=f"标定样本：{fixed[:60]}")
            return

        # 已经是中文了 —— 检查里面有没有混进"错的字"
        if any(_is_cjk(c) for c in source):
            sus = _suspect_chars(source)
            try:
                with open(os.path.join(CONFIG_DIR, "cal_debug.txt"), "a", encoding="utf-8") as f:
                    f.write("suspect=" + repr([hex(ord(c)) for c in sus]) + "\n")
            except Exception:
                pass
            if sus:
                names = "、".join(f"U+{ord(c):04X}" for c in sus[:5])
                self._set_status(
                    f"⚠ 扫到中文了，但有 {len(sus)} 个字符是错的（{names}）。"
                    f"击键间隔只有 {median*1000:.1f} ms，是传得太快让 Windows 合成出错，"
                    "不是编码问题。请先扫「传输速度设置码」里的「低」，再回来扫一次。")
                return
            self.cfg = {"ready": True, "threshold": thr, "recon": reconstructed,
                        "note": "native"}
            self._save_cfg(self.cfg)
            self.hook.calibrating = False
            self.hook.threshold = thr
            self._enter_work_mode()
            self._set_status("你的扫描枪本来就能输出正确中文，无需转换，程序已在后台待命")
            return

        self._set_status("⚠ 没读到可识别的内容，请确认扫的是车辆通行证二维码，再扫一次")

    # ---------- 工作模式
    def _enter_work_mode(self):
        self.box_cal.pack_forget()
        self.box_work.pack(fill="x")
        self.lbl_big.config(text="● 正在工作", fg="#0b5c28")
        self.lbl_sub.config(
            text="在任何窗口（记事本、网页、业务系统）直接扫码即可，\n"
                 "程序会在后台把乱码替换成中文，回车由扫描枪自己带。\n"
                 "本窗口可以最小化，不影响使用。")
        self._set_status("后台待命中…")

    def _on_scan(self, raw, fixed, how, enter_used, n):
        # 钩子里不能直接 SendInput，交给独立线程
        def work():
            time.sleep(0.01)
            type_text(fixed, enter=enter_used)
            self.after(0, self._after_fix, raw, fixed, how)
        threading.Thread(target=work, daemon=True).start()

    def _after_fix(self, raw, fixed, how):
        self.count += 1
        self.lbl_count.config(text=f"已转换 {self.count} 条")
        self.lbl_last.config(text=f"最近一条：{fixed}")

    def _toggle(self):
        self.hook.enabled = not self.hook.enabled
        self.btn_toggle.config(text="继续" if not self.hook.enabled else "暂停")
        self.lbl_big.config(text="○ 已暂停" if not self.hook.enabled else "● 正在工作",
                            fg="#a33" if not self.hook.enabled else "#0b5c28")
        self._set_status("已暂停，扫码不再转换" if not self.hook.enabled else "后台待命中…")

    def _minimize(self):
        self.iconify()

    def _quit(self):
        try:
            user32.UnhookWindowsHookEx(self.hook._hook)
        except Exception:
            pass
        self.destroy()

    def _set_status(self, msg):
        try:
            self.lbl_status.config(text=msg)
        except Exception:
            pass


def main():
    app = App()
    app.mainloop()


if __name__ == "__main__":
    main()
