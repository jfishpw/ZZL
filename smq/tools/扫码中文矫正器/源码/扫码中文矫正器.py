# -*- coding: utf-8 -*-
"""
扫码中文矫正器  v1.0
------------------------------------------------------------------
用途：二维码里存的是 GBK 编码的中文（如车辆通行证标签），扫码枪按 USB 键盘
      模式输出后，落到电脑上变成乱码。本工具不修改、不重新生成二维码，
      只在接收端把字节"按 GBK 解释"，或把已经产生的乱码反解回中文。

两种工作模式：
  1) 键盘模式：扫码枪照旧用 USB 键盘模式，扫进本工具窗口，自动还原中文，
     再一键回填到原来的业务软件（剪切板 / 模拟粘贴 / 模拟键入）。
  2) 串口模式：把扫码枪设成 USB 虚拟串口(CDC/VCP)，本工具直接读原始字节，
     按 GBK 解码 —— 100% 不丢字节，最可靠。

作者说明：单文件、无第三方强依赖（串口模式需 pyserial，生成测试码需 qrcode）。
"""

import ctypes
import ctypes.wintypes as wt
import os
import queue
import sys
import threading
import time
import unicodedata
import tkinter as tk
from tkinter import ttk, messagebox

APP_TITLE = "扫码中文矫正器 v1.0 —— 不改二维码，也能扫出正常中文"

# ----------------------------------------------------------------------------
# 一、乱码还原算法
# ----------------------------------------------------------------------------
ENCODINGS_OUT = ("gbk", "gb18030", "utf-8", "big5")
ENCODINGS_IN = ("latin-1", "cp1252", "cp437", "gbk", "utf-8")


def _is_cjk(ch: str) -> bool:
    return "\u4e00" <= ch <= "\u9fff" or "\u3400" <= ch <= "\u4dbf"


def _score(text: str) -> float:
    """给候选文本打分：中文字越多、可打印字符越多、替换符/怪符号越少，分越高。"""
    if not text:
        return -1e9
    bad = text.count("\ufffd") + text.count("\x00")
    cjk = sum(1 for c in text if _is_cjk(c))
    printable = sum(1 for c in text if c.isprintable() or c in "\r\n\t")
    # 非 ASCII 非汉字的字符（° ⌐ ┐ ┌ ¬ √ ↑ → Ñ » ¦ 等）是乱码的典型特征
    weird = sum(1 for c in text if ord(c) >= 0x7F and not _is_cjk(c))
    return cjk * 3.0 + printable * 0.6 - bad * 8.0 - weird * 2.5


def _has_high_single_byte(text: str) -> bool:
    """文本里是否含 U+0080~U+017F 区间的字符 —— 这类字符几乎只出现在
    "GBK/UTF-8 字节被按 Latin-1/cp1252 显示"的乱码里。"""
    return any(0x80 <= ord(c) <= 0x17F for c in text)


def _has_weird(text: str) -> bool:
    """文本里是否含「非 ASCII 且非汉字」的字符。
    覆盖范围比 _has_high_single_byte 广，能识别 CP437/CP850 那类
    制表符乱码（⌐ ┐ ┌ ¬ √ ↑ →），这些字符都在 U+2000 以上。"""
    return any(ord(c) >= 0x7F and not _is_cjk(c) and c not in "\r\n\t"
               for c in text)


# 富文本/输入框"自动排版"会把 ASCII 换成弯引号、破折号，这些字符不在
# CP437 码表里，会让整串编码失败。编码前先还原成原始 ASCII。
_SMART_MAP = {
    "\u201c": '"', "\u201d": '"', "\u201e": '"', "\u2033": '"',
    "\u2018": "'", "\u2019": "'", "\u201a": "'", "\u2032": "'",
    "\u2013": "-", "\u2014": "-", "\u2212": "-", "\u2015": "-",
    "\u2026": "...", "\u00a0": " ",
}


def _encode_lossy(text: str, enc: str):
    """尽量把文本编回字节。编不了的字符先试智能标点替换、再试 NFKC 归一化，
    仍不行就跳过。返回 (字节, 被跳过的字符数)。"""
    out = bytearray()
    skipped = 0
    for ch in text:
        for cand in (ch, _SMART_MAP.get(ch), unicodedata.normalize("NFKC", ch)):
            if not cand:
                continue
            try:
                out += cand.encode(enc)
                break
            except (UnicodeEncodeError, LookupError):
                continue
        else:
            skipped += 1
    return bytes(out), skipped


def _try(raw: bytes, encs):
    for e in encs:
        try:
            return raw.decode(e), e
        except (UnicodeDecodeError, LookupError):
            continue
    return None, None


def repair(text: str):
    """返回 (最佳文本, 方式名, 候选列表[(方式, 文本)])"""
    text = (text or "").strip("\r\n").strip()
    cands = [("原样（未改）", text)]
    if not text:
        return text, "原样（未改）", cands

    base_cjk = sum(1 for c in text if _is_cjk(c))

    def consider(name, out):
        if not out or out == text or "\ufffd" in out:
            return
        cands.append((name, out))

    if not _has_weird(text):
        # 场景：文本已经是汉字，但可能是"UTF-8 字节被按 GBK 显示"（如 鍚岃垷）
        try:
            raw = text.encode("gbk")
        except (UnicodeEncodeError, LookupError):
            return text, "原样（未改）", cands
        out, _e = _try(raw, ("utf-8",))
        if out and out != text and "\ufffd" not in out:
            cands.append(("gbk 取回字节 → 按 utf-8 解码", out))
            return out, cands[-1][0], cands
        return text, "原样（未改）", cands

    # 场景：字节被当成单字节字符显示。先还原字节，再按 UTF-8 / GBK 试解。
    # 顺序很关键：CP437 / CP850 必须排在前面。那类电脑（英文区域设置 +
    # OEM 代码页 437）会把 GBK 双字节拆成 ⌐ ┐ ┌ ¬ √ ↑ → 这样的制表符，
    # 只有 cp437/cp850 才能把这些字符编回原始字节。
    for enc_in in ("cp437", "cp850", "cp1252", "latin-1"):
        raw, skipped = _encode_lossy(text, enc_in)
        if not raw:
            continue
        tag = f"（跳过{skipped}字）" if skipped else ""
        # UTF-8 的校验很强，能解开基本就不会错，优先
        out, _e = _try(raw, ("utf-8",))
        consider(f"{enc_in} 取回字节 → 按 utf-8 解码{tag}", out)
        out, _e = _try(raw, ("gbk", "gb18030", "big5"))
        consider(f"{enc_in} 取回字节 → 按 gbk 解码{tag}", out)

    # 多个编码都能解开时，选「中文最多、怪符号最少」的那个
    best = None
    for name, out in cands[1:]:
        if not any(_is_cjk(c) for c in out):
            continue
        sc = _score(out)
        if best is None or sc > best[0]:
            best = (sc, out, name)
    if best and best[0] > _score(text):
        return best[1], best[2], cands
    return text, "原样（未改）", cands


def looks_lossy(text: str) -> bool:
    """判断乱码是否已经丢字节（出现 � 或 ?），这种不可逆，必须走串口模式。"""
    return ("\ufffd" in text) or text.count("?") >= 2


# ----------------------------------------------------------------------------
# 二、Windows 输入模拟（把正确中文回填到业务软件）
# ----------------------------------------------------------------------------
user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

KEYEVENTF_EXTENDEDKEY = 0x0001
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
INPUT_KEYBOARD = 1


class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wt.WORD),
        ("wScan", wt.WORD),
        ("dwFlags", wt.DWORD),
        ("time", wt.DWORD),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    ]


class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", wt.LONG),
        ("dy", wt.LONG),
        ("mouseData", wt.DWORD),
        ("dwFlags", wt.DWORD),
        ("time", wt.DWORD),
        ("dwExtraInfo", ctypes.POINTER(ctypes.c_ulong)),
    ]


class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [("uMsg", wt.DWORD), ("wParamL", wt.WORD), ("wParamH", wt.WORD)]


class _INPUTUNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]


class INPUT(ctypes.Structure):
    _anonymous_ = ("u",)
    _fields_ = [("type", wt.DWORD), ("u", _INPUTUNION)]


def _send(events):
    if not events:
        return 0
    arr = (INPUT * len(events))(*events)
    return user32.SendInput(len(events), arr, ctypes.sizeof(INPUT))


def _key(vk, up=False):
    ev = INPUT(type=INPUT_KEYBOARD)
    ev.ki = KEYBDINPUT(vk, 0, KEYEVENTF_KEYUP if up else 0, 0, None)
    return ev


def _uni(code, up=False):
    ev = INPUT(type=INPUT_KEYBOARD)
    ev.ki = KEYBDINPUT(0, code, KEYEVENTF_UNICODE | (KEYEVENTF_KEYUP if up else 0), 0, None)
    return ev


def type_unicode(text: str):
    """以 Unicode 方式逐字键入（不受键盘布局/输入法影响）。"""
    events = []
    for ch in text:
        code = ord(ch)
        if code > 0xFFFF:
            code -= 0x10000
            codes = [0xD800 + (code >> 10), 0xDC00 + (code & 0x3FF)]
        else:
            codes = [code]
        for c in codes:
            events.append(_uni(c, False))
            events.append(_uni(c, True))
        if len(events) >= 200:
            _send(events)
            events = []
            time.sleep(0.01)
    _send(events)


def send_ctrl_v(with_enter=False):
    seq = [_key(0x11), _key(0x56), _key(0x56, True), _key(0x11, True)]
    if with_enter:
        seq += [_key(0x0D), _key(0x0D, True)]
    _send(seq)


def foreground_hwnd():
    return user32.GetForegroundWindow()


def hwnd_title(hwnd):
    n = user32.GetWindowTextLengthW(hwnd)
    buf = ctypes.create_unicode_buffer(n + 1)
    user32.GetWindowTextW(hwnd, buf, n + 1)
    return buf.value


def focus_window(hwnd):
    if not hwnd:
        return False
    if user32.IsIconic(hwnd):
        user32.ShowWindow(hwnd, 9)  # SW_RESTORE
    user32.SetForegroundWindow(hwnd)
    time.sleep(0.12)
    return True


def list_windows():
    """枚举可见的顶层窗口（标题, 句柄），供"绑定目标窗口"下拉框用。"""
    result = []
    WNDENUMPROC = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)

    def _cb(hwnd, _):
        if user32.IsWindowVisible(hwnd):
            title = hwnd_title(hwnd)
            if title.strip():
                result.append((title.strip(), hwnd))
        return True

    user32.EnumWindows(WNDENUMPROC(_cb), 0)
    seen, uniq = set(), []
    for t, h in result:
        if t not in seen:
            seen.add(t)
            uniq.append((t, h))
    return uniq


# ----------------------------------------------------------------------------
# 三、界面
# ----------------------------------------------------------------------------
class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(APP_TITLE)
        self.geometry("880x640")
        self.minsize(760, 560)
        try:
            self.tk.call("tk", "scaling", 1.35)
        except Exception:
            pass

        self.raw_text = ""
        self.fixed_text = ""
        self.target_hwnd = None
        self.ser = None
        self.ser_thread = None
        self.ser_stop = threading.Event()
        self.ser_q = queue.Queue()

        self._build_ui()
        self.after(150, self._auto_focus_box)

    # ---------------- UI 骨架 ----------------
    def _build_ui(self):
        nb = ttk.Notebook(self)
        nb.pack(fill="both", expand=True, padx=8, pady=8)

        self.tab_kb = ttk.Frame(nb)
        self.tab_ser = ttk.Frame(nb)
        self.tab_help = ttk.Frame(nb)
        nb.add(self.tab_kb, text="  键盘模式（推荐先试）  ")
        nb.add(self.tab_ser, text="  串口模式（最可靠）  ")
        nb.add(self.tab_help, text="  排查说明  ")
        self.nb = nb

        self._build_kb_tab()
        self._build_ser_tab()
        self._build_help_tab()

        self.status = tk.StringVar(value="就绪：把光标放在下面的输入框里，直接扫码（末尾的 Enter 会自动触发修复）。")
        ttk.Label(self, textvariable=self.status, anchor="w", foreground="#555").pack(
            fill="x", padx=12, pady=(0, 8)
        )

    def _build_kb_tab(self):
        f = self.tab_kb
        top = ttk.Frame(f)
        top.pack(fill="x", padx=10, pady=(10, 4))
        ttk.Label(top, text="① 扫码 / 粘贴乱码到下面（清空后重新扫）", font=("微软雅黑", 10, "bold")).pack(anchor="w")

        self.txt_raw = tk.Text(f, height=4, font=("Consolas", 11), wrap="char", undo=True)
        self.txt_raw.pack(fill="x", padx=10, pady=(2, 6))
        self.txt_raw.bind("<Return>", self._on_return)
        self.txt_raw.bind("<KP_Enter>", self._on_return)

        bar = ttk.Frame(f)
        bar.pack(fill="x", padx=10, pady=2)
        ttk.Button(bar, text="修复 (Enter)", command=self.do_fix).pack(side="left")
        ttk.Button(bar, text="从剪贴板取乱码", command=self._from_clipboard).pack(side="left", padx=6)
        ttk.Button(bar, text="清空", command=self._clear).pack(side="left")
        self.var_auto = tk.BooleanVar(value=True)
        ttk.Checkbutton(bar, text="扫完自动复制到剪贴板", variable=self.var_auto).pack(side="right")

        ttk.Separator(f).pack(fill="x", padx=10, pady=8)

        ttk.Label(f, text="② 还原结果", font=("微软雅黑", 10, "bold")).pack(anchor="w", padx=10)
        self.txt_fix = tk.Text(f, height=5, font=("微软雅黑", 13), wrap="char",
                               bg="#f4fbf4", fg="#0b5c28")
        self.txt_fix.pack(fill="x", padx=10, pady=(2, 6))

        mid = ttk.Frame(f)
        mid.pack(fill="x", padx=10)
        self.lbl_how = ttk.Label(mid, text="还原方式：—", foreground="#666")
        self.lbl_how.pack(anchor="w")

        self.lbl_parts = ttk.Label(f, text="分段：—", foreground="#333", wraplength=820, justify="left")
        self.lbl_parts.pack(anchor="w", padx=10, pady=(4, 8))

        ttk.Label(f, text="③ 回填到业务软件", font=("微软雅黑", 10, "bold")).pack(anchor="w", padx=10)

        row = ttk.Frame(f)
        row.pack(fill="x", padx=10, pady=4)
        ttk.Label(row, text="目标窗口：").pack(side="left")
        self.cmb_win = ttk.Combobox(row, width=52, state="readonly")
        self.cmb_win.pack(side="left", padx=4)
        ttk.Button(row, text="刷新窗口列表", command=self.refresh_windows).pack(side="left")
        ttk.Button(row, text="使用「本工具打开前的窗口」", command=self._bind_prev).pack(side="left", padx=6)

        row2 = ttk.Frame(f)
        row2.pack(fill="x", padx=10, pady=(2, 4))
        ttk.Button(row2, text="复制到剪贴板 (Ctrl+C)", command=self._copy).pack(side="left")
        ttk.Button(row2, text="切到目标窗口并粘贴 (Ctrl+V)", command=lambda: self._paste(False)).pack(side="left", padx=6)
        ttk.Button(row2, text="切到目标窗口并键入", command=self._type_into).pack(side="left")
        ttk.Button(row2, text="粘贴并按回车", command=lambda: self._paste(True)).pack(side="left", padx=6)
        ttk.Button(row2, text="导出诊断", command=self._export_diag).pack(side="left", padx=6)

        row3 = ttk.Frame(f)
        row3.pack(fill="x", padx=10, pady=(2, 10))
        self.var_auto_back = tk.BooleanVar(value=False)
        ttk.Checkbutton(row3, text="扫完自动回填（不用再点按钮）", variable=self.var_auto_back).pack(side="left")
        self.var_back_mode = tk.StringVar(value="粘贴")
        ttk.Combobox(row3, width=8, state="readonly", textvariable=self.var_back_mode,
                     values=["粘贴", "键入"]).pack(side="left", padx=6)
        self.var_back_enter = tk.BooleanVar(value=False)
        ttk.Checkbutton(row3, text="末尾加回车", variable=self.var_back_enter).pack(side="left", padx=6)

    def _build_ser_tab(self):
        f = self.tab_ser
        ttk.Label(
            f,
            text=("把扫描枪切到「蓝牙 SPP / 虚拟串口」通道后，在这里选 COM 口读原始字节。\n"
                  "串口是字节流，工具按 GBK 解码，一个字节都不会丢 —— 这是让记事本、网页输入框\n"
                  "直接出现正确中文的最正规做法（YC200D 走蓝牙 SPP，见「排查说明」页）。"),
            justify="left", foreground="#333",
        ).pack(anchor="w", padx=10, pady=(10, 6))

        row = ttk.Frame(f)
        row.pack(fill="x", padx=10, pady=4)
        ttk.Label(row, text="串口：").pack(side="left")
        self.cmb_com = ttk.Combobox(row, width=12, state="readonly")
        self.cmb_com.pack(side="left")
        ttk.Button(row, text="刷新", command=self.refresh_coms).pack(side="left", padx=4)
        ttk.Label(row, text="波特率：").pack(side="left", padx=(10, 0))
        self.cmb_baud = ttk.Combobox(row, width=8, state="readonly",
                                     values=["9600", "19200", "38400", "57600", "115200"])
        self.cmb_baud.set("9600")
        self.cmb_baud.pack(side="left")
        self.btn_open = ttk.Button(row, text="打开串口", command=self.toggle_serial)
        self.btn_open.pack(side="left", padx=8)

        row2 = ttk.Frame(f)
        row2.pack(fill="x", padx=10, pady=4)
        ttk.Label(row2, text="按此编码解码：").pack(side="left")
        self.cmb_enc = ttk.Combobox(row2, width=10, state="readonly",
                                    values=["gbk", "gb18030", "utf-8", "自动"])
        self.cmb_enc.set("gbk")
        self.cmb_enc.pack(side="left")
        self.var_ser_copy = tk.BooleanVar(value=True)
        ttk.Checkbutton(row2, text="复制到剪贴板", variable=self.var_ser_copy).pack(side="left", padx=12)

        row3 = ttk.Frame(f)
        row3.pack(fill="x", padx=10, pady=4)
        self.var_ser_type = tk.BooleanVar(value=False)
        ttk.Checkbutton(row3, text="收到后直接键入到当前光标处（可把本窗口最小化，扫完网页/记事本里直接出中文）",
                        variable=self.var_ser_type).pack(side="left")
        self.var_ser_enter = tk.BooleanVar(value=False)
        ttk.Checkbutton(row3, text="键入后回车", variable=self.var_ser_enter).pack(side="left", padx=8)

        self.txt_ser = tk.Text(f, font=("Consolas", 10), wrap="char")
        self.txt_ser.pack(fill="both", expand=True, padx=10, pady=8)

    def _build_help_tab(self):
        f = self.tab_help
        bar = ttk.Frame(f)
        bar.pack(fill="x", padx=10, pady=(10, 0))
        ttk.Button(bar, text="打开「设置码速查」图", command=lambda: self._open_file("设置码速查.png")).pack(side="left")
        ttk.Button(bar, text="打开 YC200D 说明书 PDF", command=lambda: self._open_file("YC200D说明书.pdf")).pack(side="left", padx=8)
        txt = tk.Text(f, wrap="word", font=("微软雅黑", 10), bg="#fbfbfb")
        txt.pack(fill="both", expand=True, padx=10, pady=10)
        txt.insert("1.0", HELP_TEXT)
        txt.configure(state="disabled")

    def _app_dir(self):
        """打包成 exe 后，取 exe 所在目录；直接跑脚本时取脚本目录。"""
        if getattr(sys, "frozen", False):
            return os.path.dirname(os.path.abspath(sys.executable))
        return os.path.dirname(os.path.abspath(__file__))

    def _open_file(self, name):
        path = os.path.join(self._app_dir(), name)
        if os.path.exists(path):
            os.startfile(path)
        else:
            messagebox.showinfo("找不到文件", f"同目录里没有 {name}，\n请把设置码图片和 exe 放在同一个文件夹。")

    # ---------------- 键盘模式逻辑 ----------------
    def _auto_focus_box(self):
        self.txt_raw.focus_set()

    def _on_return(self, event=None):
        if event is not None and event.state & 0x0001:  # Shift+Enter 换行
            return None
        self.after(30, self.do_fix)
        return "break"

    def _clear(self):
        self.txt_raw.delete("1.0", "end")
        self.txt_fix.delete("1.0", "end")
        self.lbl_how.config(text="还原方式：—")
        self.lbl_parts.config(text="分段：—")
        self.txt_raw.focus_set()

    def _from_clipboard(self):
        try:
            data = self.clipboard_get()
        except Exception:
            messagebox.showinfo("提示", "剪贴板里没有文本。")
            return
        self.txt_raw.delete("1.0", "end")
        self.txt_raw.insert("1.0", data)
        self.do_fix()

    def do_fix(self):
        raw = self.txt_raw.get("1.0", "end").rstrip("\r\n")
        if not raw.strip():
            self._auto_focus_box()
            return
        fixed, how, cands = repair(raw)

        self.raw_text, self.fixed_text = raw, fixed
        self.txt_fix.delete("1.0", "end")
        self.txt_fix.insert("1.0", fixed)
        self.lbl_how.config(text=f"还原方式：{how}")

        if "#" in fixed:
            parts = [p for p in fixed.split("#")]
            self.lbl_parts.config(text="分段：" + " | ".join(f"[{i}]{p}" for i, p in enumerate(parts) if p))
        else:
            self.lbl_parts.config(text="分段：（无 # 分隔）")

        if looks_lossy(raw):
            self.status.set("⚠ 原始数据里已经出现 � / ?，说明字节在途中被丢掉了 —— 这种情况只能改用「串口模式」。")
        elif fixed == raw:
            self.status.set("这次扫描是正常的，没有检出乱码。")
        else:
            self.status.set("✓ 已还原成中文。原串：%s" % (raw[:60] + ("…" if len(raw) > 60 else "")))

        if self.var_auto.get():
            self.clipboard_clear()
            self.clipboard_append(fixed)
        self._clear_box_silent(raw)

        if self.var_auto_back.get() and self.target_hwnd:
            if self.var_back_mode.get() == "键入":
                self.after(60, self._type_into)
            else:
                self.after(60, lambda: self._paste(self.var_back_enter.get()))

    def _clear_box_silent(self, raw):
        # 保留原串在框里方便对照，但把光标放回末尾，下一次扫码会接在后面 —— 所以清空更安全
        self.txt_raw.delete("1.0", "end")
        self.txt_raw.focus_set()

    def _copy(self):
        if not self.fixed_text:
            self.do_fix()
        if not self.fixed_text:
            return
        self.clipboard_clear()
        self.clipboard_append(self.fixed_text)
        self.status.set("已复制到剪贴板。")

    def _export_diag(self):
        """把收到的原始乱码、每个字符的码位、所有编码尝试结果写成文件。
        用于远程定位『还原不了』的情况 —— 截图看不清字符，文本才准。"""
        raw = (getattr(self, "raw_text", "") or "").strip()
        if not raw:
            try:
                raw = self.txt_raw.get("1.0", "end-1c").strip()
            except Exception:
                raw = ""
        if not raw:
            messagebox.showinfo("没有内容", "请先扫一次（或粘贴乱码），再点导出。")
            return

        lines = ["扫码诊断报告", "=" * 46,
                 "字符数: %d" % len(raw), "", "【原文】", raw, "",
                 "【码位】", " ".join("U+%04X" % ord(c) for c in raw), "",
                 "【逐字符】"]
        for i, c in enumerate(raw):
            lines.append("%3d  %-6r U+%04X" % (i, c, ord(c)))
        lines += ["", "【编码尝试】"]
        for enc_in in ("cp437", "cp850", "cp1252", "latin-1",
                       "gbk", "utf-8", "cp932", "cp950"):
            try:
                b = raw.encode(enc_in)
            except Exception as e:
                lines.append("%s: 无法编码（%s）" % (enc_in, type(e).__name__))
                continue
            lines.append("%s -> %s" % (enc_in, b.hex(" ")))
            for enc_out in ("gbk", "gb18030", "utf-8", "big5", "cp932"):
                try:
                    lines.append("    -> %-9s: %s" % (enc_out, b.decode(enc_out)))
                except Exception:
                    lines.append("    -> %-9s: 解码失败" % enc_out)
            lines.append("")

        try:
            base = os.path.join(os.path.expanduser("~"), "Desktop")
            if not os.path.isdir(base):
                base = os.path.expanduser("~")
            path = os.path.join(base, "扫码诊断.txt")
            with open(path, "w", encoding="utf-8-sig") as f:
                f.write("\n".join(lines))
            self.status.set("诊断已导出：" + path)
            messagebox.showinfo("已导出", "诊断文件已保存到：\n\n%s\n\n"
                                "请把这个文件拷回来（里面的码位比截图准得多）。" % path)
        except Exception as e:
            messagebox.showerror("导出失败", str(e))

    def _paste(self, with_enter):
        if not self.fixed_text:
            self.do_fix()
        if not self.fixed_text:
            return
        if not self.target_hwnd:
            messagebox.showinfo("提示", "还没绑定目标窗口。\n可以在下拉框里选，或先点「使用本工具打开前的窗口」。")
            return
        self.clipboard_clear()
        self.clipboard_append(self.fixed_text)
        self.update()
        focus_window(self.target_hwnd)
        time.sleep(0.18)
        send_ctrl_v(with_enter)
        self.status.set("已粘贴到目标窗口。切回本工具继续扫码即可。")
        time.sleep(0.25)
        self.lift()
        self._auto_focus_box()

    def _type_into(self):
        if not self.fixed_text:
            self.do_fix()
        if not self.fixed_text or not self.target_hwnd:
            return
        focus_window(self.target_hwnd)
        time.sleep(0.18)
        type_unicode(self.fixed_text)
        if self.var_back_enter.get():
            _send([_key(0x0D), _key(0x0D, True)])
        time.sleep(0.15)
        self.lift()
        self._auto_focus_box()
        self.status.set("已键入到目标窗口（Unicode 方式，不受输入法影响）。")

    def refresh_windows(self):
        wins = list_windows()
        self._win_map = {f"{t}  [{h}]": h for t, h in wins}
        self.cmb_win["values"] = list(self._win_map.keys())
        if wins and not self.cmb_win.get():
            self.cmb_win.current(0)
            self.target_hwnd = wins[0][1]
        self.status.set(f"已刷新窗口列表（{len(wins)} 个）。选一个作为回填目标。")
        self.cmb_win.bind("<<ComboboxSelected>>", self._on_win_pick)

    def _on_win_pick(self, _evt=None):
        self.target_hwnd = self._win_map.get(self.cmb_win.get())
        self.status.set("目标窗口已绑定：%s" % self.cmb_win.get())

    def _bind_prev(self):
        if getattr(self, "_prev_hwnd", None):
            self.target_hwnd = self._prev_hwnd
            self.status.set("目标窗口已绑定为：%s" % hwnd_title(self._prev_hwnd))
        else:
            messagebox.showinfo("提示", "没记录到之前的窗口，请用下拉框选择。")

    # ---------------- 串口模式逻辑 ----------------
    def refresh_coms(self):
        try:
            from serial.tools import list_ports
            ports = [p.device for p in list_ports.comports()]
        except Exception:
            ports = []
        self.cmb_com["values"] = ports
        if ports and not self.cmb_com.get():
            self.cmb_com.current(0)
        self.status.set("串口列表已刷新：" + (", ".join(ports) if ports else "未发现串口"))

    def toggle_serial(self):
        if self.ser:
            self.ser_stop.set()
            try:
                self.ser.close()
            except Exception:
                pass
            self.ser = None
            self.btn_open.config(text="打开串口")
            self.status.set("串口已关闭。")
            return
        try:
            import serial
        except Exception:
            messagebox.showerror("缺少组件", "串口模式需要 pyserial：\npip install pyserial")
            return
        port = self.cmb_com.get()
        if not port:
            messagebox.showinfo("提示", "先选一个 COM 口。")
            return
        try:
            self.ser = serial.Serial(port, int(self.cmb_baud.get() or 9600), timeout=0.2)
        except Exception as e:
            messagebox.showerror("打不开串口", f"{e}\n\n可能被别的程序占用，或扫码枪还没切到虚拟串口模式。")
            return
        self.btn_open.config(text="关闭串口")
        self.ser_stop.clear()
        self.ser_thread = threading.Thread(target=self._ser_loop, daemon=True)
        self.ser_thread.start()
        self.after(80, self._drain_serial)
        self.status.set(f"已打开 {port}，请扫码。")

    def _ser_loop(self):
        buf = b""
        while not self.ser_stop.is_set():
            try:
                chunk = self.ser.read(256)
            except Exception as e:
                self.ser_q.put(("err", str(e)))
                break
            if not chunk:
                continue
            buf += chunk
            if b"\r" in buf or b"\n" in buf:
                line, _, buf = buf.partition(b"\r" if b"\r" in buf else b"\n")
                self.ser_q.put(("line", line))
            elif len(buf) > 4096:
                self.ser_q.put(("line", buf))
                buf = b""

    def _drain_serial(self):
        if not self.ser:
            return
        try:
            while True:
                kind, payload = self.ser_q.get_nowait()
                if kind == "err":
                    self.status.set("串口出错：" + payload)
                    continue
                self._show_serial_line(payload)
        except queue.Empty:
            pass
        self.after(120, self._drain_serial)

    def _show_serial_line(self, raw: bytes):
        enc = self.cmb_enc.get()
        if enc == "自动":
            best, best_enc = None, "gbk"
            for e in ("gbk", "utf-8", "gb18030"):
                try:
                    t = raw.decode(e)
                except UnicodeDecodeError:
                    continue
                if best is None or _score(t) > _score(best):
                    best, best_enc = t, e
            text = best if best is not None else raw.decode("gbk", "replace")
            enc = best_enc
        else:
            text = raw.decode(enc, "replace")
        self.txt_ser.insert("end", f"[{time.strftime('%H:%M:%S')}] ({enc}) {text}\n")
        self.txt_ser.insert("end", f"          HEX: {raw.hex(' ')}\n")
        self.txt_ser.see("end")
        if self.var_ser_copy.get():
            self.clipboard_clear()
            self.clipboard_append(text)
        if self.var_ser_type.get():
            # 后台直达：不切换任何窗口，直接以 Unicode 键入选当前光标处
            type_unicode(text)
            if self.var_ser_enter.get():
                _send([_key(0x0D), _key(0x0D, True)])
            self.status.set("已键入到当前窗口：" + text)
        else:
            self.status.set("串口收到一条：" + text)


HELP_TEXT = """【结论先说】YC200D 这台机器，在「USB 键盘(HID)模式」下是出不来中文的。

为什么？因为二维码里存的是 GBK 编码的字节（两个字节一个汉字），而键盘模式下扫码枪只能
"模拟按键"。按键只能打出一个字符，没法把两个字节合成一个汉字。厂家也知道这点，所以：
  · 同厂的 EP-8600 等型号，固件里有「数据编码格式 GBK / UNICODE」这一项，能自己转换；
  · YC200D 的官方固件里没有这一项（说明书全文已核对：只有 语言设置/结束符/蓝牙协议/…）。

你这张标签（7678.pdf）里的内容，按 GBK 解出来是：
    #沪#GE7678#11895*2550*3880#CFF#JIT#同舟;英利横梁;地毯金鸿顺多利;新安;实业交通#17752890125#0000SHYHWL
所以问题不在码，也不在"设置对不对"，而在于通道选错了：字节流走串口，中文才有救。

────────────────────────────────────────────
【两条真正可行的路】

路线 A（推荐）：让数据走「蓝牙 SPP」通道
    1. 扫「设置码速查」里的 ① 蓝牙 SPP 模式。
    2. 在 Windows 设置 → 蓝牙和其他设备 → 添加设备，配对你的扫描枪。
       配对后「设备管理器 → 端口(COM 和 LPT)」里会多出
       「蓝牙链接上的标准串行 (COM x)」。
    3. 打开本工具「串口模式」，选这个 COM 口，编码选 gbk，点打开串口。
    4. 勾上「收到后直接键入到当前光标处」，把本工具最小化。
       之后鼠标点着网页输入框直接扫码 —— 输入框里就是正确的中文，全程不用切换窗口。
    注意：SPP 通道不会自己"打字"，必须靠本工具（或任何串口程序）接收，这是正常的。

路线 B（不改任何设置，今天就能用）：保持键盘模式，让本工具当"中转"
    1. 「键盘模式」里先把「目标窗口」选成你的网页/记事本，勾「扫完自动回填」。
    2. 光标留在本工具输入框，扫一下 → 自动还原并粘贴/键入选好的窗口。
    3. 如果只想临时用：扫完点「切到目标窗口并粘贴」也一样。

────────────────────────────────────────────
【为什么记事本/网页里叫"乱码"，而本工具能救回来】
    乱码串其实是「GBK 的两个字节被当成了两个西欧字母」，比如 沪 = BB A6 → 显示成 »¦。
    字节没丢，只是被拆错了，所以能反解回来。一旦串里出现 � 或 ?，说明字节真丢了，
    那时只能走路线 A。

【设置码怎么扫】
    · 用「设置码速查.png / .pdf」在屏幕上 100% 缩放显示，扫屏幕就行；扫不出来就打印。
    · 听到"嘀嘀"两声＝设置成功，一声长音＝失败。
    · 扫错进了别的模式，扫 ③ 初始化主控设置 复位（只恢复设置，不影响使用）。
    · 设置码是从科密官方 YC200D 说明书（40 页）里原样裁出来的，本目录已存原件。

【其它常见坑】
    · 记事本正常、只有业务软件乱 → 业务软件自己在解码，让它按 GBK 解，或用路线 B。
    · 每条多出回车/Tab → 是结束符设置，扫 ⑤ 或说明书的取消项。
    · 扫描时请把输入法切到英文，中文输入法会吞掉高速按键。
    · 蓝牙 SPP 下如果搜不到设备，先用 ② 切回 HID 确认枪是好的，再切回 SPP 重配对。
"""


def main():
    app = App()
    # 记录工具拿到焦点之前的那个窗口，方便"回填"绑定
    app._prev_hwnd = foreground_hwnd()
    app.after(200, app.refresh_windows)
    app.after(400, app.refresh_coms)
    app.mainloop()


if __name__ == "__main__":
    main()
