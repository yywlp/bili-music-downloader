from __future__ import annotations

import ctypes
import os
import tempfile
import threading
import tkinter as tk
import webbrowser
from pathlib import Path
from tkinter import filedialog, messagebox, ttk
from typing import List, Optional, Tuple

import customtkinter as ctk
from PIL import Image

from bili_client import BiliClient
from config import AppSettings, get_resource_path, load_settings, save_settings
from converter import convert_to_mp3, probe_mp3_summary, resolve_output_path, sanitize_filename
from playlist_importer import fetch_netease_playlist
from ranker import VideoCandidate, format_play_count, rank_candidates

# Brand palette (Bilibili Sakura Pink + Sky Blue + Fluent Neutrals)
PINK = "#FB7299"
PINK_HOVER = "#E85D85"
BLUE = "#23ADE5"
BLUE_HOVER = "#1A96C8"
LIGHT_BG = "#F3F5F9"
DARK_BG = "#14161D"
LIGHT_CARD = "#FFFFFF"
DARK_CARD = "#1E212B"
LIGHT_BORDER = "#E5E7EB"
DARK_BORDER = "#2E3342"


def _setup_windows_shell() -> None:
    if os.name != "nt":
        return
    try:
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("Antigravity.BiliMusicDownloader.1")
    except Exception:
        pass
    try:
        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except Exception:
        try:
            ctypes.windll.user32.SetProcessDPIAware()
        except Exception:
            pass


def parse_batch_line(line: str) -> Optional[Tuple[str, str]]:
    text = line.strip()
    if not text or text.startswith("#"):
        return None
    for sep in (" - ", " — ", "—", "｜", "|", "\t"):
        if sep in text:
            left, right = text.split(sep, 1)
            if left.strip():
                return left.strip(), right.strip()
    return text, ""


class BiliMusicApp(ctk.CTk):
    def __init__(self) -> None:
        super().__init__()
        ctk.set_appearance_mode("Light")
        ctk.set_default_color_theme("blue")

        self.settings: AppSettings = load_settings()
        self.client = BiliClient(sessdata=self.settings.sessdata)
        self.candidates: List[VideoCandidate] = []
        self._busy = False
        self._cancel_event = threading.Event()
        self._last_song_query = ""
        self._last_artist_query = ""
        self._imported_playlist_name = ""
        self._theme_mode = "Light"
        self._logo_image: Optional[ctk.CTkImage] = None

        self.title("BiliMusic · Bilibili 纯单曲 MP3 下载器")
        self.geometry("1120x740")
        self.minsize(960, 640)
        self.configure(fg_color=(LIGHT_BG, DARK_BG))

        self._apply_window_icon()
        self._build_ui()
        self._apply_treeview_style()
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    def _apply_window_icon(self) -> None:
        ico_path = get_resource_path("assets/app.ico")
        if ico_path.is_file():
            try:
                self.iconbitmap(str(ico_path))
            except Exception:
                pass
        png_path = get_resource_path("assets/app.png")
        if png_path.is_file():
            try:
                img = Image.open(png_path)
                self._logo_image = ctk.CTkImage(light_image=img, dark_image=img, size=(42, 42))
            except Exception:
                self._logo_image = None

    def _build_ui(self) -> None:
        self.font_title = ctk.CTkFont(family="Microsoft YaHei UI", size=19, weight="bold")
        self.font_sub = ctk.CTkFont(family="Microsoft YaHei UI", size=12)
        self.font_body = ctk.CTkFont(family="Microsoft YaHei UI", size=13)
        self.font_bold = ctk.CTkFont(family="Microsoft YaHei UI", size=13, weight="bold")
        self.font_small = ctk.CTkFont(family="Microsoft YaHei UI", size=12)

        # ---------- Header Card ----------
        header = ctk.CTkFrame(
            self,
            corner_radius=16,
            fg_color=(LIGHT_CARD, DARK_CARD),
            border_width=1,
            border_color=(LIGHT_BORDER, DARK_BORDER),
        )
        header.pack(fill="x", padx=18, pady=(16, 10))

        top_row = ctk.CTkFrame(header, fg_color="transparent")
        top_row.pack(fill="x", padx=18, pady=(14, 8))

        brand_left = ctk.CTkFrame(top_row, fg_color="transparent")
        brand_left.pack(side="left")

        if self._logo_image is not None:
            ctk.CTkLabel(brand_left, image=self._logo_image, text="").pack(side="left", padx=(0, 12))

        title_col = ctk.CTkFrame(brand_left, fg_color="transparent")
        title_col.pack(side="left")
        ctk.CTkLabel(
            title_col,
            text="BiliMusic 音乐下载器",
            font=self.font_title,
            text_color=("#111827", "#F9FAFB"),
        ).pack(anchor="w")
        ctk.CTkLabel(
            title_col,
            text="自动筛选 B站高播放无损纯单曲 · 320 kbps MP3 直出 · 自动以歌名命名",
            font=self.font_sub,
            text_color=("#6B7280", "#9CA3AF"),
        ).pack(anchor="w", pady=(2, 0))

        tools_right = ctk.CTkFrame(top_row, fg_color="transparent")
        tools_right.pack(side="right")

        self.theme_btn = ctk.CTkButton(
            tools_right,
            text="深色模式",
            width=86,
            height=34,
            corner_radius=10,
            font=self.font_small,
            fg_color=("#F3F4F6", "#2A2E3B"),
            hover_color=("#E5E7EB", "#363C4D"),
            text_color=("#374151", "#E5E7EB"),
            command=self._toggle_theme,
        )
        self.theme_btn.pack(side="left", padx=(0, 8))

        ctk.CTkButton(
            tools_right,
            text="打开目录",
            width=86,
            height=34,
            corner_radius=10,
            font=self.font_small,
            fg_color=("#EEF8FD", "#1A3242"),
            hover_color=("#DCF1FB", "#224358"),
            text_color=("#0284C7", "#7DD3FC"),
            command=self._open_download_dir,
        ).pack(side="left", padx=(0, 8))

        ctk.CTkButton(
            tools_right,
            text="更改目录",
            width=86,
            height=34,
            corner_radius=10,
            font=self.font_small,
            fg_color=("#F3F4F6", "#2A2E3B"),
            hover_color=("#E5E7EB", "#363C4D"),
            text_color=("#374151", "#E5E7EB"),
            command=self._choose_download_dir,
        ).pack(side="left", padx=(0, 8))

        ctk.CTkButton(
            tools_right,
            text="设置",
            width=72,
            height=34,
            corner_radius=10,
            font=self.font_small,
            fg_color=("#F3F4F6", "#2A2E3B"),
            hover_color=("#E5E7EB", "#363C4D"),
            text_color=("#374151", "#E5E7EB"),
            command=self._open_settings_dialog,
        ).pack(side="left")

        # Subtle info strip inside header
        meta_row = ctk.CTkFrame(header, fg_color=("#F8FAFC", "#181B24"), corner_radius=10)
        meta_row.pack(fill="x", padx=18, pady=(0, 14))

        self.dir_var = tk.StringVar(value=f"保存目录：{self.settings.download_dir}")
        ctk.CTkLabel(
            meta_row,
            textvariable=self.dir_var,
            font=self.font_small,
            text_color=("#4B5563", "#9CA3AF"),
        ).pack(side="left", padx=12, pady=6)

        ff_ok = Path(self.settings.ffmpeg_path).is_file()
        self.ff_badge = ctk.CTkLabel(
            meta_row,
            text="● ffmpeg 内置就绪" if ff_ok else "● ffmpeg 未找到",
            font=self.font_small,
            text_color=("#10B981", "#34D399") if ff_ok else ("#EF4444", "#F87171"),
        )
        self.ff_badge.pack(side="right", padx=12, pady=6)

        # ---------- Main Tabview ----------
        self.tabview = ctk.CTkTabview(
            self,
            corner_radius=16,
            fg_color=(LIGHT_CARD, DARK_CARD),
            border_width=1,
            border_color=(LIGHT_BORDER, DARK_BORDER),
            segmented_button_fg_color=("#EEF0F5", "#252936"),
            segmented_button_selected_color=PINK,
            segmented_button_selected_hover_color=PINK_HOVER,
            segmented_button_unselected_color=("#EEF0F5", "#252936"),
            segmented_button_unselected_hover_color=("#E2E6EE", "#323747"),
            text_color=("#1F2937", "#F3F4F6"),
        )
        self.tabview.pack(fill="both", expand=True, padx=18, pady=(0, 10))
        self.tabview._segmented_button.configure(font=self.font_bold)

        self.single_tab = self.tabview.add("  单曲搜选下载  ")
        self.batch_tab = self.tabview.add("  批量歌单下载  ")

        self._build_single_tab()
        self._build_batch_tab()

        # ---------- Bottom Progress Card ----------
        footer = ctk.CTkFrame(
            self,
            corner_radius=14,
            fg_color=(LIGHT_CARD, DARK_CARD),
            border_width=1,
            border_color=(LIGHT_BORDER, DARK_BORDER),
        )
        footer.pack(fill="x", padx=18, pady=(0, 16))

        self.progress = ctk.CTkProgressBar(
            footer,
            height=8,
            corner_radius=4,
            fg_color=("#E5E7EB", "#2C3140"),
            progress_color=PINK,
        )
        self.progress.set(0)
        self.progress.pack(fill="x", padx=16, pady=(12, 6))

        status_row = ctk.CTkFrame(footer, fg_color="transparent")
        status_row.pack(fill="x", padx=16, pady=(0, 10))

        self.status_var = tk.StringVar(value="就绪：输入歌曲名，一键自动筛选无损纯单曲并下载为 歌曲名.mp3")
        ctk.CTkLabel(
            status_row,
            textvariable=self.status_var,
            font=self.font_small,
            text_color=("#4B5563", "#D1D5DB"),
        ).pack(side="left")

        self.cancel_btn = ctk.CTkButton(
            status_row,
            text="停止任务",
            width=84,
            height=28,
            corner_radius=8,
            font=self.font_small,
            fg_color=("#FEE2E2", "#451A1A"),
            hover_color=("#FECACA", "#612424"),
            text_color=("#DC2626", "#FCA5A5"),
            command=self._cancel_task,
            state="disabled",
        )
        self.cancel_btn.pack(side="right")

    def _build_single_tab(self) -> None:
        # Search bar row
        search_bar = ctk.CTkFrame(self.single_tab, fg_color="transparent")
        search_bar.pack(fill="x", padx=10, pady=(8, 12))

        self.song_entry = ctk.CTkEntry(
            search_bar,
            placeholder_text="输入歌曲名（如：青花瓷、兰亭序）",
            width=260,
            height=38,
            corner_radius=10,
            font=self.font_body,
            border_color=("#D1D5DB", "#374151"),
        )
        self.song_entry.pack(side="left", padx=(0, 8))
        self.song_entry.bind("<Return>", lambda _e: self._start_search(auto_download=False))

        self.artist_entry = ctk.CTkEntry(
            search_bar,
            placeholder_text="歌手（选填）",
            width=145,
            height=38,
            corner_radius=10,
            font=self.font_body,
            border_color=("#D1D5DB", "#374151"),
        )
        self.artist_entry.pack(side="left", padx=(0, 10))
        self.artist_entry.bind("<Return>", lambda _e: self._start_search(auto_download=False))

        mode_label = "无损优先" if self.settings.rank_mode == "lossless_first" else "播放量优先"
        self.mode_seg = ctk.CTkSegmentedButton(
            search_bar,
            values=["无损优先", "播放量优先"],
            height=38,
            corner_radius=10,
            font=self.font_small,
            selected_color=BLUE,
            selected_hover_color=BLUE_HOVER,
            command=self._on_mode_change,
        )
        self.mode_seg.set(mode_label)
        self.mode_seg.pack(side="left", padx=(0, 10))

        self.search_btn = ctk.CTkButton(
            search_bar,
            text="搜索候选",
            width=96,
            height=38,
            corner_radius=10,
            font=self.font_bold,
            fg_color=BLUE,
            hover_color=BLUE_HOVER,
            command=lambda: self._start_search(auto_download=False),
        )
        self.search_btn.pack(side="left", padx=(0, 8))

        self.quick_btn = ctk.CTkButton(
            search_bar,
            text="一键搜并下载第1名",
            width=158,
            height=38,
            corner_radius=10,
            font=self.font_bold,
            fg_color=PINK,
            hover_color=PINK_HOVER,
            command=lambda: self._start_search(auto_download=True),
        )
        self.quick_btn.pack(side="left")

        # Candidate Table Container
        self.table_container = ctk.CTkFrame(
            self.single_tab,
            corner_radius=12,
            fg_color=("#FFFFFF", "#191C25"),
            border_width=1,
            border_color=(LIGHT_BORDER, DARK_BORDER),
        )
        self.table_container.pack(fill="both", expand=True, padx=10, pady=(0, 10))

        inner = tk.Frame(self.table_container, bd=0, highlightthickness=0)
        inner.pack(fill="both", expand=True, padx=2, pady=2)
        self._table_inner = inner

        cols = ("rank", "badges", "title", "author", "play", "duration", "bvid")
        self.tree = ttk.Treeview(inner, columns=cols, show="headings", selectmode="browse", style="Modern.Treeview")
        self.tree.heading("rank", text="#")
        self.tree.heading("badges", text="推荐标签")
        self.tree.heading("title", text="视频标题")
        self.tree.heading("author", text="UP主")
        self.tree.heading("play", text="播放量")
        self.tree.heading("duration", text="时长")
        self.tree.heading("bvid", text="BV号")

        self.tree.column("rank", width=42, anchor=tk.CENTER, stretch=False)
        self.tree.column("badges", width=140, anchor=tk.CENTER, stretch=False)
        self.tree.column("title", width=440, anchor=tk.W, stretch=True)
        self.tree.column("author", width=130, anchor=tk.W, stretch=False)
        self.tree.column("play", width=85, anchor=tk.E, stretch=False)
        self.tree.column("duration", width=70, anchor=tk.CENTER, stretch=False)
        self.tree.column("bvid", width=120, anchor=tk.CENTER, stretch=False)

        ysb = ttk.Scrollbar(inner, orient=tk.VERTICAL, command=self.tree.yview)
        self.tree.configure(yscrollcommand=ysb.set)
        self.tree.pack(side="left", fill="both", expand=True)
        ysb.pack(side="right", fill="y")

        self.tree.bind("<Double-1>", lambda _e: self._download_selected())
        self.tree.bind("<Button-3>", self._show_tree_menu)

        self.tree_menu = tk.Menu(self, tearoff=0)
        self.tree_menu.add_command(label="下载选中项为 MP3", command=self._download_selected)
        self.tree_menu.add_command(label="在浏览器中打开核对", command=self._open_selected_in_browser)
        self.tree_menu.add_command(label="复制视频链接", command=self._copy_selected_url)

        # Action bar below table
        action_bar = ctk.CTkFrame(self.single_tab, fg_color="transparent")
        action_bar.pack(fill="x", padx=10, pady=(0, 6))

        ctk.CTkLabel(
            action_bar,
            text="提示：默认已按无损纯单曲规则排好序 · 双击列表项直接下载 · 右键可在浏览器核对原视频",
            font=self.font_small,
            text_color=("#6B7280", "#9CA3AF"),
        ).pack(side="left")

        self.dl_selected_btn = ctk.CTkButton(
            action_bar,
            text="下载选中项 (MP3)",
            width=148,
            height=36,
            corner_radius=10,
            font=self.font_bold,
            fg_color=PINK,
            hover_color=PINK_HOVER,
            command=self._download_selected,
        )
        self.dl_selected_btn.pack(side="right")

    def _build_batch_tab(self) -> None:
        # NetEase Playlist Import Bar
        import_bar = ctk.CTkFrame(
            self.batch_tab,
            corner_radius=12,
            fg_color=("#F8FAFC", "#181B24"),
            border_width=1,
            border_color=(LIGHT_BORDER, DARK_BORDER),
        )
        import_bar.pack(fill="x", padx=10, pady=(8, 8))

        imp_row1 = ctk.CTkFrame(import_bar, fg_color="transparent")
        imp_row1.pack(fill="x", padx=12, pady=(10, 6))

        ctk.CTkLabel(
            imp_row1,
            text="导入网易云歌单：",
            font=self.font_bold,
            text_color=("#1F2937", "#F3F4F6"),
        ).pack(side="left", padx=(0, 8))

        self.playlist_entry = ctk.CTkEntry(
            imp_row1,
            placeholder_text="直接粘贴网易云歌单分享文案、链接（music.163.com/...）或纯歌单 ID",
            height=34,
            corner_radius=8,
            font=self.font_small,
            border_color=("#D1D5DB", "#374151"),
        )
        self.playlist_entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self.playlist_entry.bind("<Return>", lambda _e: self._start_import_playlist())

        self.import_btn = ctk.CTkButton(
            imp_row1,
            text="解析导入",
            width=96,
            height=34,
            corner_radius=8,
            font=self.font_bold,
            fg_color=BLUE,
            hover_color=BLUE_HOVER,
            command=self._start_import_playlist,
        )
        self.import_btn.pack(side="right")

        imp_row2 = ctk.CTkFrame(import_bar, fg_color="transparent")
        imp_row2.pack(fill="x", padx=12, pady=(0, 10))

        self.use_subfolder_var = tk.BooleanVar(value=True)
        ctk.CTkCheckBox(
            imp_row2,
            text="按歌单名自动建子文件夹",
            variable=self.use_subfolder_var,
            font=self.font_small,
            checkbox_width=18,
            checkbox_height=18,
            fg_color=PINK,
            hover_color=PINK_HOVER,
        ).pack(side="left", padx=(0, 18))

        self.strict_match_var = tk.BooleanVar(value=True)
        ctk.CTkCheckBox(
            imp_row2,
            text="严格匹配（B站搜不到对应纯单曲则跳过，不乱下）",
            variable=self.strict_match_var,
            font=self.font_small,
            checkbox_width=18,
            checkbox_height=18,
            fg_color=PINK,
            hover_color=PINK_HOVER,
        ).pack(side="left")

        self.playlist_info_var = tk.StringVar(value="")
        ctk.CTkLabel(
            imp_row2,
            textvariable=self.playlist_info_var,
            font=self.font_small,
            text_color=("#0284C7", "#7DD3FC"),
        ).pack(side="right")

        split = ctk.CTkFrame(self.batch_tab, fg_color="transparent")
        split.pack(fill="both", expand=True, padx=10, pady=(0, 10))
        split.grid_columnconfigure(0, weight=2)
        split.grid_columnconfigure(1, weight=3)
        split.grid_rowconfigure(0, weight=1)

        self.batch_input = ctk.CTkTextbox(
            split,
            corner_radius=12,
            border_width=1,
            border_color=(LIGHT_BORDER, DARK_BORDER),
            font=self.font_body,
            fg_color=("#FAFBFD", "#181B24"),
        )
        self.batch_input.grid(row=0, column=0, sticky="nsew", padx=(0, 8))
        self.batch_input.insert("end", "# 可在上方直接粘贴网易云歌单导入，或手动每行输入一首：\n# 青花瓷 - 周杰伦\n# 兰亭序\n")

        self.batch_log = ctk.CTkTextbox(
            split,
            corner_radius=12,
            border_width=1,
            border_color=(LIGHT_BORDER, DARK_BORDER),
            font=self.font_small,
            fg_color=("#FAFBFD", "#181B24"),
            state="disabled",
        )
        self.batch_log.grid(row=0, column=1, sticky="nsew", padx=(8, 0))

        btn_bar = ctk.CTkFrame(self.batch_tab, fg_color="transparent")
        btn_bar.pack(fill="x", padx=10, pady=(0, 6))

        ctk.CTkButton(
            btn_bar,
            text="清空歌单",
            width=96,
            height=36,
            corner_radius=10,
            font=self.font_small,
            fg_color=("#F3F4F6", "#2A2E3B"),
            hover_color=("#E5E7EB", "#363C4D"),
            text_color=("#374151", "#E5E7EB"),
            command=self._clear_batch_input,
        ).pack(side="left")

        self.batch_start_btn = ctk.CTkButton(
            btn_bar,
            text="开始批量下载",
            width=148,
            height=36,
            corner_radius=10,
            font=self.font_bold,
            fg_color=PINK,
            hover_color=PINK_HOVER,
            command=self._start_batch_download,
        )
        self.batch_start_btn.pack(side="right")

    def _apply_treeview_style(self) -> None:
        dark = self._theme_mode == "Dark"
        bg = "#191C25" if dark else "#FFFFFF"
        fg = "#E5E7EB" if dark else "#1F2937"
        head_bg = "#232734" if dark else "#F8FAFC"
        head_fg = "#D1D5DB" if dark else "#475569"
        sel_bg = "#59253B" if dark else "#FFE4EC"
        sel_fg = "#FFFFFF" if dark else "#9D174D"
        alt_bg = "#1E222E" if dark else "#FAFBFC"

        self._table_inner.configure(bg=bg)
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure(
            "Modern.Treeview",
            background=bg,
            foreground=fg,
            fieldbackground=bg,
            borderwidth=0,
            rowheight=34,
            font=("Microsoft YaHei UI", 10),
        )
        style.map(
            "Modern.Treeview",
            background=[("selected", sel_bg)],
            foreground=[("selected", sel_fg)],
        )
        style.configure(
            "Modern.Treeview.Heading",
            background=head_bg,
            foreground=head_fg,
            relief="flat",
            borderwidth=0,
            padding=(8, 8),
            font=("Microsoft YaHei UI", 10, "bold"),
        )
        style.map("Modern.Treeview.Heading", background=[("active", head_bg)])
        self.tree.tag_configure("odd", background=alt_bg)
        self.tree.tag_configure("even", background=bg)

    def _toggle_theme(self) -> None:
        self._theme_mode = "Dark" if self._theme_mode == "Light" else "Light"
        ctk.set_appearance_mode(self._theme_mode)
        self.theme_btn.configure(text="浅色模式" if self._theme_mode == "Dark" else "深色模式")
        self._apply_treeview_style()

    def _current_rank_mode(self) -> str:
        return "lossless_first" if self.mode_seg.get() == "无损优先" else "views_first"

    def _on_mode_change(self, _value: str) -> None:
        self.settings.rank_mode = self._current_rank_mode()
        save_settings(self.settings)

    def _set_busy(self, busy: bool) -> None:
        self._busy = busy
        state = "disabled" if busy else "normal"
        self.search_btn.configure(state=state)
        self.quick_btn.configure(state=state)
        self.dl_selected_btn.configure(state=state)
        self.batch_start_btn.configure(state=state)
        self.import_btn.configure(state=state)
        self.cancel_btn.configure(state="normal" if busy else "disabled")
        if not busy:
            self._cancel_event.clear()

    def _set_status(self, text: str, progress: Optional[float] = None) -> None:
        self.status_var.set(text)
        if progress is not None:
            self.progress.set(max(0.0, min(1.0, progress / 100.0)))

    def _append_batch_log(self, msg: str) -> None:
        self.batch_log.configure(state="normal")
        self.batch_log.insert("end", msg + "\n")
        self.batch_log.see("end")
        self.batch_log.configure(state="disabled")

    def _choose_download_dir(self) -> None:
        chosen = filedialog.askdirectory(initialdir=self.settings.download_dir, title="选择 MP3 保存目录")
        if chosen:
            self.settings.download_dir = chosen
            save_settings(self.settings)
            self.dir_var.set(f"保存目录：{chosen}")

    def _open_download_dir(self) -> None:
        d = Path(self.settings.download_dir)
        d.mkdir(parents=True, exist_ok=True)
        os.startfile(str(d))

    def _open_settings_dialog(self) -> None:
        win = ctk.CTkToplevel(self)
        win.title("偏好设置")
        win.geometry("560x340")
        win.resizable(False, False)
        win.transient(self)
        win.grab_set()

        ico_path = get_resource_path("assets/app.ico")
        if ico_path.is_file():
            try:
                win.after(100, lambda: win.iconbitmap(str(ico_path)))
            except Exception:
                pass

        card = ctk.CTkFrame(win, corner_radius=14, fg_color=(LIGHT_CARD, DARK_CARD))
        card.pack(fill="both", expand=True, padx=16, pady=16)

        ctk.CTkLabel(card, text="重名文件处理方式", font=self.font_bold).grid(row=0, column=0, sticky="w", padx=16, pady=(16, 8))
        policy_map = {"rename": "自动编号", "overwrite": "直接覆盖", "skip": "跳过"}
        rev_map = {v: k for k, v in policy_map.items()}
        policy_seg = ctk.CTkSegmentedButton(
            card,
            values=["自动编号", "直接覆盖", "跳过"],
            font=self.font_small,
            selected_color=PINK,
            selected_hover_color=PINK_HOVER,
        )
        policy_seg.set(policy_map.get(self.settings.conflict_policy, "自动编号"))
        policy_seg.grid(row=0, column=1, columnspan=2, sticky="w", padx=(8, 16), pady=(16, 8))

        ctk.CTkLabel(card, text="ffmpeg.exe 路径", font=self.font_bold).grid(row=1, column=0, sticky="w", padx=16, pady=8)
        ff_entry = ctk.CTkEntry(card, width=290, height=34, corner_radius=8, font=self.font_small)
        ff_entry.insert(0, self.settings.ffmpeg_path)
        ff_entry.grid(row=1, column=1, sticky="ew", padx=(8, 6), pady=8)

        def browse_ff() -> None:
            p = filedialog.askopenfilename(
                title="选择 ffmpeg.exe", filetypes=[("Executable", "*.exe"), ("All", "*.*")]
            )
            if p:
                ff_entry.delete(0, "end")
                ff_entry.insert(0, p)

        ctk.CTkButton(card, text="浏览", width=64, height=34, corner_radius=8, font=self.font_small, command=browse_ff).grid(
            row=1, column=2, padx=(0, 16), pady=8
        )

        ctk.CTkLabel(card, text="B站 SESSDATA (可选)", font=self.font_bold).grid(row=2, column=0, sticky="w", padx=16, pady=8)
        sess_entry = ctk.CTkEntry(card, width=360, height=34, corner_radius=8, font=self.font_small, show="*")
        sess_entry.insert(0, self.settings.sessdata)
        sess_entry.grid(row=2, column=1, columnspan=2, sticky="ew", padx=(8, 16), pady=8)

        ctk.CTkLabel(
            card,
            text="说明：程序已内置 ffmpeg.exe，不填 SESSDATA 即可直接拉取普通最高音质流转 320k MP3；若有大会员可填入解锁 FLAC。",
            font=self.font_small,
            text_color=("#6B7280", "#9CA3AF"),
            wraplength=490,
            justify="left",
        ).grid(row=3, column=0, columnspan=3, sticky="w", padx=16, pady=(6, 16))

        def on_save() -> None:
            self.settings.conflict_policy = rev_map.get(policy_seg.get(), "rename")
            self.settings.ffmpeg_path = ff_entry.get().strip()
            self.settings.sessdata = sess_entry.get().strip()
            self.client.set_sessdata(self.settings.sessdata)
            save_settings(self.settings)
            ff_ok = Path(self.settings.ffmpeg_path).is_file()
            self.ff_badge.configure(
                text="● ffmpeg 内置就绪" if ff_ok else "● ffmpeg 未找到",
                text_color=("#10B981", "#34D399") if ff_ok else ("#EF4444", "#F87171"),
            )
            win.destroy()

        btn_row = ctk.CTkFrame(card, fg_color="transparent")
        btn_row.grid(row=4, column=0, columnspan=3, sticky="e", padx=16, pady=(4, 16))
        ctk.CTkButton(
            btn_row,
            text="取消",
            width=80,
            height=34,
            corner_radius=8,
            fg_color=("#F3F4F6", "#2A2E3B"),
            hover_color=("#E5E7EB", "#363C4D"),
            text_color=("#374151", "#E5E7EB"),
            command=win.destroy,
        ).pack(side="right", padx=(8, 0))
        ctk.CTkButton(
            btn_row,
            text="保存设置",
            width=96,
            height=34,
            corner_radius=8,
            font=self.font_bold,
            fg_color=PINK,
            hover_color=PINK_HOVER,
            command=on_save,
        ).pack(side="right")

    def _show_tree_menu(self, event: tk.Event) -> None:
        item = self.tree.identify_row(event.y)
        if item:
            self.tree.selection_set(item)
            self.tree_menu.tk_popup(event.x_root, event.y_root)

    def _get_selected_candidate(self) -> Optional[VideoCandidate]:
        sel = self.tree.selection()
        if not sel:
            return None
        idx = int(sel[0])
        if 0 <= idx < len(self.candidates):
            return self.candidates[idx]
        return None

    def _open_selected_in_browser(self) -> None:
        c = self._get_selected_candidate()
        if c:
            webbrowser.open(f"https://www.bilibili.com/video/{c.bvid}/")

    def _copy_selected_url(self) -> None:
        c = self._get_selected_candidate()
        if c:
            url = f"https://www.bilibili.com/video/{c.bvid}/"
            self.clipboard_clear()
            self.clipboard_append(url)
            self._set_status(f"已复制链接：{url}")

    def _cancel_task(self) -> None:
        self._cancel_event.set()
        self._set_status("正在停止任务...")

    def _on_close(self) -> None:
        self._cancel_event.set()
        self.destroy()

    # ---------- Single Search & Download ----------
    def _start_search(self, auto_download: bool = False) -> None:
        if self._busy:
            return
        song = self.song_entry.get().strip()
        artist = self.artist_entry.get().strip()
        if not song:
            messagebox.showwarning("提示", "请先输入歌曲名。")
            return

        self._last_song_query = song
        self._last_artist_query = artist
        self._cancel_event.clear()
        self._set_busy(True)
        self._set_status(f"正在 Bilibili 搜索《{song}》并筛选最佳无损纯单曲...", 15)

        mode = self._current_rank_mode()
        threading.Thread(
            target=self._worker_search,
            args=(song, artist, mode, auto_download),
            daemon=True,
        ).start()

    def _worker_search(self, song: str, artist: str, mode: str, auto_download: bool) -> None:
        try:
            raw = self.client.search_song_candidates(
                song_name=song,
                artist=artist,
                lossless_first=(mode == "lossless_first"),
            )
            ranked = rank_candidates(raw, song_name=song, artist=artist, mode=mode, limit=15)
        except Exception as exc:
            self.after(0, lambda e=str(exc): self._on_search_error(e))
            return

        self.after(0, lambda: self._populate_candidates(ranked))

        if not ranked:
            self.after(0, lambda: self._finish_single_idle(f"未找到与《{song}》匹配的视频，请尝试调整关键词。"))
            return

        if auto_download and not self._cancel_event.is_set():
            self._worker_download_one(ranked[0], song, artist)
        else:
            self.after(
                0,
                lambda: self._finish_single_idle(
                    f"已筛选出 {len(ranked)} 个候选，第 1 名为推荐版本，双击或点右下角按钮即可下载。"
                ),
            )

    def _populate_candidates(self, ranked: List[VideoCandidate]) -> None:
        self.candidates = ranked
        for row in self.tree.get_children():
            self.tree.delete(row)
        for i, c in enumerate(ranked):
            badge_str = " · ".join(c.badges) if c.badges else "普通"
            tag = "odd" if i % 2 else "even"
            self.tree.insert(
                "",
                tk.END,
                iid=str(i),
                values=(
                    i + 1,
                    badge_str,
                    c.title,
                    c.author,
                    format_play_count(c.play),
                    c.duration_str,
                    c.bvid,
                ),
                tags=(tag,),
            )
        if ranked:
            self.tree.selection_set("0")
            self.tree.focus("0")

    def _on_search_error(self, err: str) -> None:
        self._set_busy(False)
        self._set_status(f"搜索失败：{err}", 0)
        messagebox.showerror("搜索失败", err)

    def _finish_single_idle(self, msg: str, progress: float = 0) -> None:
        self._set_busy(False)
        self._set_status(msg, progress)

    def _download_selected(self) -> None:
        if self._busy:
            return
        c = self._get_selected_candidate()
        if not c:
            messagebox.showinfo("提示", "请先搜索并在列表中选择一个视频。")
            return
        song = self.song_entry.get().strip() or self._last_song_query or c.title
        artist = self.artist_entry.get().strip() or self._last_artist_query

        self._cancel_event.clear()
        self._set_busy(True)
        threading.Thread(
            target=self._worker_download_one,
            args=(c, song, artist),
            daemon=True,
        ).start()

    def _download_candidate_sync(
        self,
        candidate: VideoCandidate,
        song_name: str,
        artist_name: str,
        status_prefix: str = "",
        target_dir: Optional[str] = None,
    ) -> Tuple[str, Optional[Path], str]:
        out_path, skipped = resolve_output_path(
            target_dir or self.settings.download_dir,
            song_name,
            conflict_policy=self.settings.conflict_policy,
        )
        if skipped:
            return "skipped", out_path, f"文件已存在，已跳过：{out_path.name}"

        self.after(0, lambda: self._set_status(f"{status_prefix}正在解析音频流 ({candidate.bvid})...", 20))
        info = self.client.get_video_info(candidate.bvid)
        stream = self.client.get_best_audio_stream(candidate.bvid, info["cid"])

        tmp_dir = Path(tempfile.gettempdir()) / "bili_music_dl"
        tmp_dir.mkdir(parents=True, exist_ok=True)
        tmp_file = tmp_dir / f"{candidate.bvid}_{info['cid']}.m4s"

        def on_prog(done: int, total: int) -> None:
            if total > 0:
                pct = 20.0 + (done / total) * 60.0
                mb_done = done / (1024 * 1024)
                mb_tot = total / (1024 * 1024)
                self.after(
                    0,
                    lambda p=pct, d=mb_done, t=mb_tot: self._set_status(
                        f"{status_prefix}正在下载音频流 ({d:.1f}/{t:.1f} MB)...", p
                    ),
                )

        try:
            self.client.download_stream(
                stream,
                candidate.bvid,
                tmp_file,
                progress_cb=on_prog,
                cancel_event=self._cancel_event,
            )
            if self._cancel_event.is_set():
                return "cancelled", None, "已取消"

            self.after(0, lambda: self._set_status(f"{status_prefix}正在转码为 320k MP3...", 85))
            ok, msg = convert_to_mp3(
                ffmpeg_path=self.settings.ffmpeg_path,
                input_path=tmp_file,
                output_path=out_path,
                title=song_name,
                artist=artist_name or candidate.author,
                bitrate=self.settings.bitrate,
            )
            if not ok:
                return "error", None, msg

            summary = probe_mp3_summary(self.settings.ffmpeg_path, out_path)
            size_mb = out_path.stat().st_size / (1024 * 1024)
            quality_tag = "FLAC源" if stream.get("is_flac") else f"流{stream.get('id')}"
            detail = f"已保存：{out_path.name} ({size_mb:.2f} MB · {quality_tag}" + (f" · {summary})" if summary else ")")
            return "ok", out_path, detail
        except InterruptedError:
            return "cancelled", None, "已取消"
        finally:
            if tmp_file.exists():
                tmp_file.unlink(missing_ok=True)

    def _worker_download_one(self, candidate: VideoCandidate, song_name: str, artist_name: str) -> None:
        try:
            status, _out_path, detail = self._download_candidate_sync(candidate, song_name, artist_name)
        except Exception as exc:
            self.after(0, lambda e=str(exc): self._on_download_error(e))
            return

        if status == "ok":
            self.after(0, lambda: self._finish_single_idle(f"下载完成！{detail}", 100))
        elif status == "skipped":
            self.after(0, lambda: self._finish_single_idle(detail, 100))
        elif status == "cancelled":
            self.after(0, lambda: self._finish_single_idle("任务已取消。", 0))
        else:
            self.after(0, lambda: self._on_download_error(detail))

    def _on_download_error(self, err: str) -> None:
        self._set_busy(False)
        self._set_status(f"下载失败：{err}", 0)
        messagebox.showerror("下载失败", err)

    # ---------- NetEase Playlist Import & Batch Download ----------
    def _clear_batch_input(self) -> None:
        self.batch_input.delete("1.0", "end")
        self._imported_playlist_name = ""
        self.playlist_info_var.set("")

    def _start_import_playlist(self) -> None:
        if self._busy:
            return
        raw = self.playlist_entry.get().strip()
        if not raw:
            messagebox.showwarning("提示", "请先粘贴网易云歌单分享文案、链接或纯歌单 ID。")
            return

        self._cancel_event.clear()
        self._set_busy(True)
        self._set_status("正在解析网易云歌单曲目列表...", 15)
        threading.Thread(target=self._worker_import_playlist, args=(raw,), daemon=True).start()

    def _worker_import_playlist(self, raw_text: str) -> None:
        def on_prog(done: int, total: int) -> None:
            if total > 0:
                pct = 15.0 + (done / total) * 80.0
                self.after(0, lambda d=done, t=total, p=pct: self._set_status(f"正在展开网易云歌单曲目 ({d}/{t})...", p))

        try:
            pl = fetch_netease_playlist(raw_text, progress_cb=on_prog)
        except Exception as exc:
            self.after(0, lambda e=str(exc): self._on_import_error(e))
            return

        lines = [f"# 网易云歌单：{pl.name}（共 {len(pl.tracks)} 首）"]
        for t in pl.tracks:
            lines.append(f"{t.name} - {t.artist}" if t.artist else t.name)
        joined = "\n".join(lines) + "\n"

        def apply_ui() -> None:
            self._imported_playlist_name = pl.name
            self.playlist_info_var.set(f"已导入：{pl.name}（{len(pl.tracks)} 首）")
            self.batch_input.delete("1.0", "end")
            self.batch_input.insert("end", joined)
            self._finish_single_idle(
                f"成功导入网易云歌单《{pl.name}》（共 {len(pl.tracks)} 首），可删改后点「开始批量下载」。", 100
            )

        self.after(0, apply_ui)

    def _on_import_error(self, err: str) -> None:
        self._set_busy(False)
        self._set_status(f"歌单导入失败：{err}", 0)
        messagebox.showerror("导入失败", err)

    def _start_batch_download(self) -> None:
        if self._busy:
            return
        raw_text = self.batch_input.get("1.0", "end")
        tasks = [parsed for line in raw_text.splitlines() if (parsed := parse_batch_line(line))]
        if not tasks:
            messagebox.showwarning("提示", "请先在左侧输入或导入至少一首歌曲。")
            return

        target_dir = self.settings.download_dir
        if self.use_subfolder_var.get() and self._imported_playlist_name:
            sub = sanitize_filename(self._imported_playlist_name)
            target_dir = str(Path(self.settings.download_dir) / sub)

        self._cancel_event.clear()
        self._set_busy(True)
        self.batch_log.configure(state="normal")
        self.batch_log.delete("1.0", "end")
        self.batch_log.configure(state="disabled")

        mode = self._current_rank_mode()
        strict = self.strict_match_var.get()
        threading.Thread(
            target=self._worker_batch,
            args=(tasks, mode, strict, target_dir),
            daemon=True,
        ).start()

    def _worker_batch(
        self,
        tasks: List[Tuple[str, str]],
        mode: str,
        strict: bool,
        target_dir: str,
    ) -> None:
        total = len(tasks)
        ok_cnt = 0
        skip_cnt = 0
        miss_cnt = 0
        fail_cnt = 0

        self.after(
            0,
            lambda: self._append_batch_log(
                f"开始批量下载，共 {total} 首（模式：{'无损优先' if mode == 'lossless_first' else '播放量优先'} · {'严格匹配' if strict else '宽松匹配'}）\n"
                f"保存目录：{target_dir}\n"
            ),
        )

        for idx, (song, artist) in enumerate(tasks, start=1):
            if self._cancel_event.is_set():
                self.after(0, lambda: self._append_batch_log("已手动停止剩余任务。"))
                break

            prefix = f"[{idx}/{total}] 《{song}》"
            self.after(0, lambda p=prefix: self._set_status(f"{p} 正在搜索...", ((idx - 1) / total) * 100))
            try:
                raw = self.client.search_song_candidates(
                    song_name=song, artist=artist, lossless_first=(mode == "lossless_first")
                )
                ranked = rank_candidates(
                    raw,
                    song_name=song,
                    artist=artist,
                    mode=mode,
                    limit=5,
                    allow_fallback=(not strict),
                )
                if not ranked:
                    miss_cnt += 1
                    self.after(0, lambda p=prefix: self._append_batch_log(f"⊘ {p} B站未找到匹配纯单曲，已跳过"))
                else:
                    best = ranked[0]
                    status, _out, detail = self._download_candidate_sync(
                        best, song, artist, status_prefix=f"{prefix} ", target_dir=target_dir
                    )
                    if status == "ok":
                        ok_cnt += 1
                        self.after(
                            0,
                            lambda p=prefix, d=detail, b=best: self._append_batch_log(
                                f"✓ {p} {d}\n    来源：{b.title} ({b.bvid}, {format_play_count(b.play)}播放)"
                            ),
                        )
                    elif status == "skipped":
                        skip_cnt += 1
                        self.after(0, lambda p=prefix, d=detail: self._append_batch_log(f"- {p} {d}"))
                    elif status == "cancelled":
                        self.after(0, lambda p=prefix: self._append_batch_log(f"! {p} 已取消"))
                        break
                    else:
                        fail_cnt += 1
                        self.after(0, lambda p=prefix, d=detail: self._append_batch_log(f"✗ {p} 失败：{d}"))
            except Exception as exc:
                fail_cnt += 1
                self.after(0, lambda p=prefix, e=str(exc): self._append_batch_log(f"✗ {p} 异常：{e}"))

            # Gentle throttle between songs for large playlists to avoid Bilibili rate limits
            if idx < total and not self._cancel_event.is_set():
                self._cancel_event.wait(1.0)

        summary = f"批量任务结束：成功 {ok_cnt} 首，跳过(已存在) {skip_cnt} 首，未搜到 {miss_cnt} 首，失败 {fail_cnt} 首。"
        self.after(0, lambda: self._append_batch_log("\n" + summary))
        self.after(0, lambda: self._finish_single_idle(summary, 100))


def main() -> None:
    _setup_windows_shell()
    app = BiliMusicApp()
    app.mainloop()


if __name__ == "__main__":
    main()
