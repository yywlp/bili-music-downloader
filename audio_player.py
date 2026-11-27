from __future__ import annotations

import ctypes
import ctypes.wintypes
import queue
import threading
from dataclasses import dataclass
from typing import Callable, Optional


def _pump_sta_messages() -> None:
    user32 = ctypes.windll.user32
    msg = ctypes.wintypes.MSG()
    pm_remove = 0x0001
    while user32.PeekMessageW(ctypes.byref(msg), 0, 0, 0, pm_remove) != 0:
        user32.TranslateMessage(ctypes.byref(msg))
        user32.DispatchMessageW(ctypes.byref(msg))


@dataclass
class PlayerSnapshot:
    current_path: str = ""
    state: str = "stopped"  # "stopped" | "playing" | "paused" | "ended"
    position_sec: float = 0.0
    duration_sec: float = 0.0
    volume: int = 80
    error: str = ""


class LocalAudioPlayer:
    """
    Thread-safe local audio player backed by Windows Media Player COM (WMPlayer.OCX).
    Natively supports .m4a (AAC), .mp3, .flac, .wav on Windows 10/11 with zero extra codecs.
    All COM calls execute strictly on a dedicated STA thread.
    """

    def __init__(
        self,
        initial_volume: int = 80,
        on_track_ended: Optional[Callable[[], None]] = None,
    ) -> None:
        self._cmd_q: queue.Queue = queue.Queue()
        self._lock = threading.Lock()
        self._snapshot = PlayerSnapshot(volume=max(0, min(100, int(initial_volume))))
        self._on_track_ended = on_track_ended
        self._stop_flag = threading.Event()
        self._ready = threading.Event()
        self._thread = threading.Thread(target=self._sta_loop, daemon=True)
        self._thread.start()
        self._ready.wait(timeout=3.0)

    def get_snapshot(self) -> PlayerSnapshot:
        with self._lock:
            s = self._snapshot
            return PlayerSnapshot(
                current_path=s.current_path,
                state=s.state,
                position_sec=s.position_sec,
                duration_sec=s.duration_sec,
                volume=s.volume,
                error=s.error,
            )

    def play_file(self, path: str) -> None:
        self._cmd_q.put(("play_file", path))

    def pause(self) -> None:
        self._cmd_q.put(("pause", None))

    def resume(self) -> None:
        self._cmd_q.put(("resume", None))

    def toggle_pause(self) -> None:
        self._cmd_q.put(("toggle", None))

    def stop(self) -> None:
        self._cmd_q.put(("stop", None))

    def release_file(self, timeout: float = 1.5) -> None:
        done = threading.Event()
        self._cmd_q.put(("release", done))
        done.wait(timeout=timeout)

    def seek(self, seconds: float) -> None:
        self._cmd_q.put(("seek", max(0.0, float(seconds))))

    def set_volume(self, volume: int) -> None:
        v = max(0, min(100, int(volume)))
        with self._lock:
            self._snapshot.volume = v
        self._cmd_q.put(("volume", v))

    def shutdown(self) -> None:
        self._stop_flag.set()
        self._cmd_q.put(("shutdown", None))
        if self._thread.is_alive():
            self._thread.join(timeout=2.0)

    def _sta_loop(self) -> None:
        import comtypes
        import comtypes.client

        comtypes.CoInitialize()
        wmp = None
        ended_fired = False
        user_stopped = True
        try:
            wmp = comtypes.client.CreateObject("WMPlayer.OCX")
            wmp.settings.autoStart = True
            wmp.settings.volume = self._snapshot.volume
            self._ready.set()

            while not self._stop_flag.is_set():
                _pump_sta_messages()
                try:
                    cmd, arg = self._cmd_q.get(timeout=0.05)
                except queue.Empty:
                    cmd, arg = None, None

                if cmd == "shutdown":
                    break
                elif cmd == "play_file" and arg:
                    ended_fired = False
                    user_stopped = False
                    try:
                        wmp.controls.stop()
                        wmp.URL = str(arg)
                        wmp.controls.play()
                        with self._lock:
                            self._snapshot.current_path = str(arg)
                            self._snapshot.state = "playing"
                            self._snapshot.position_sec = 0.0
                            self._snapshot.duration_sec = 0.0
                            self._snapshot.error = ""
                    except Exception as exc:
                        with self._lock:
                            self._snapshot.error = str(exc)
                            self._snapshot.state = "stopped"
                elif cmd == "pause":
                    try:
                        wmp.controls.pause()
                    except Exception:
                        pass
                elif cmd == "resume":
                    user_stopped = False
                    try:
                        wmp.controls.play()
                    except Exception:
                        pass
                elif cmd == "toggle":
                    try:
                        st = int(wmp.playState)
                        if st == 3:
                            wmp.controls.pause()
                        elif st in (2, 1, 8, 10) and self._snapshot.current_path:
                            user_stopped = False
                            ended_fired = False
                            wmp.controls.play()
                    except Exception:
                        pass
                elif cmd == "stop":
                    user_stopped = True
                    ended_fired = True
                    try:
                        wmp.controls.stop()
                    except Exception:
                        pass
                    with self._lock:
                        self._snapshot.state = "stopped"
                        self._snapshot.position_sec = 0.0
                elif cmd == "release":
                    user_stopped = True
                    ended_fired = True
                    try:
                        wmp.controls.stop()
                        wmp.URL = ""
                        wmp.close()
                    except Exception:
                        pass
                    with self._lock:
                        self._snapshot.current_path = ""
                        self._snapshot.state = "stopped"
                        self._snapshot.position_sec = 0.0
                        self._snapshot.duration_sec = 0.0
                    if isinstance(arg, threading.Event):
                        arg.set()
                elif cmd == "seek" and arg is not None:
                    try:
                        wmp.controls.currentPosition = float(arg)
                    except Exception:
                        pass
                elif cmd == "volume" and arg is not None:
                    try:
                        wmp.settings.volume = int(arg)
                    except Exception:
                        pass

                # Poll playback state & progress
                try:
                    raw_state = int(wmp.playState)
                    pos = float(wmp.controls.currentPosition or 0.0)
                    dur = 0.0
                    try:
                        if wmp.currentMedia is not None:
                            dur = float(wmp.currentMedia.duration or 0.0)
                    except Exception:
                        dur = 0.0

                    if raw_state == 3:
                        mapped = "playing"
                        ended_fired = False
                    elif raw_state == 2:
                        mapped = "paused"
                    elif raw_state == 8:
                        mapped = "ended"
                    elif raw_state in (6, 7, 9):
                        mapped = "playing"
                    else:
                        mapped = "stopped"

                    with self._lock:
                        self._snapshot.state = mapped
                        self._snapshot.position_sec = pos
                        if dur > 0:
                            self._snapshot.duration_sec = dur

                    if raw_state == 8 and not ended_fired and not user_stopped:
                        ended_fired = True
                        if self._on_track_ended:
                            try:
                                self._on_track_ended()
                            except Exception:
                                pass
                except Exception:
                    pass
        except Exception as exc:
            with self._lock:
                self._snapshot.error = f"音频引擎初始化失败: {exc}"
            self._ready.set()
        finally:
            if wmp is not None:
                try:
                    wmp.controls.stop()
                except Exception:
                    pass
            try:
                comtypes.CoUninitialize()
            except Exception:
                pass
