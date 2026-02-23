#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import argparse
import json
import socket
import struct
import time
from pathlib import Path
from typing import Tuple, Optional, Dict, Any, Callable

import numpy as np
import queue
import threading

try:
    from PIL import Image
except ImportError as e:
    raise SystemExit("pip install pillow numpy") from e


# ---- Protocol ----
MAGIC_MRL1 = 0x4D524C31  # "MRL1"
MAGIC_HSK1 = 0x48534B31  # "HSK1"
MAGIC_OKAY = 0x4F4B4159  # "OKAY"
PROTO_VER = 1


def format_queue_capacity(current: int, max_size: int, *, bar_width: int = 20) -> str:
    if max_size <= 0:
        return f"100%|{'#' * bar_width}| 0/0"
    clamped = max(0, min(current, max_size))
    ratio = clamped / max_size
    filled = int(ratio * bar_width)
    bar = "#" * filled + "-" * (bar_width - filled)
    percent = int(ratio * 100)
    return f"{percent:3d}%|{bar}| {clamped}/{max_size}"


class LiveStatus:
    def __init__(self, enabled: bool = True):
        self.enabled = enabled
        self._lock = threading.Lock()
        self._printed = False
        self._prev_len = 0
        self.last_tick: Optional[int] = None
        self.last_processed_tick: Optional[int] = None
        self.received = 0
        self.processed = 0
        self.qsize = 0
        self.qmax = 0

    def _render_locked(self) -> None:
        cap = format_queue_capacity(self.qsize, self.qmax)
        line = (
            f"queue_capacity {cap}, recv={self.received}, "
            f"processed={self.processed}, last_tick={self.last_tick}, "
            f"last_processed={self.last_processed_tick}"
        )
        pad = max(0, self._prev_len - len(line))
        print("\r" + line + (" " * pad), end="", flush=True)
        self._printed = True
        self._prev_len = len(line)

    def update(self, **kwargs: Any) -> None:
        if not self.enabled:
            return
        with self._lock:
            for k, v in kwargs.items():
                setattr(self, k, v)
            self._render_locked()

    def bump_processed(self, seq: int, qsize: int, qmax: int) -> None:
        if not self.enabled:
            return
        with self._lock:
            self.processed += 1
            self.last_processed_tick = seq
            self.qsize = qsize
            self.qmax = qmax
            self._render_locked()

    def newline(self) -> None:
        if not self.enabled:
            return
        with self._lock:
            if self._printed:
                print()
                self._printed = False
                self._prev_len = 0


def recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("Socket closed while receiving")
        buf += chunk
    return bytes(buf)


class Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.i = 0

    def _take(self, n: int) -> bytes:
        if self.i + n > len(self.data):
            raise ValueError("Packet truncated")
        b = self.data[self.i : self.i + n]
        self.i += n
        return b

    def i32(self) -> int:
        return struct.unpack(">i", self._take(4))[0]

    def u32(self) -> int:
        return struct.unpack(">I", self._take(4))[0]

    def i64(self) -> int:
        return struct.unpack(">q", self._take(8))[0]

    def f32(self) -> float:
        return struct.unpack(">f", self._take(4))[0]

    def string(self) -> str:
        ln = self.i32()
        if ln < 0 or ln > 10_000_000:
            raise ValueError(f"Bad string length: {ln}")
        return self._take(ln).decode("utf-8", errors="replace")


def maybe_handle_handshake(conn: socket.socket, payload: bytes) -> bool:
    if len(payload) != 8:
        return False
    magic, ver = struct.unpack(">II", payload)
    if magic != MAGIC_HSK1 or ver != PROTO_VER:
        return False
    reply = struct.pack(">II", MAGIC_OKAY, PROTO_VER)
    conn.sendall(struct.pack(">I", len(reply)) + reply)
    return True


def parse_packet(payload: bytes) -> dict:
    r = Reader(payload)

    magic = r.u32()
    if magic != MAGIC_MRL1:
        raise ValueError(f"Bad magic: 0x{magic:08X}")

    seq = r.i32()
    day_time = r.i64()
    hp = r.f32()
    hp_max = r.f32()
    armor = r.i32()
    hunger = r.i32()
    air = r.i32()
    selected_slot = r.i32()
    dimension = r.string()        # NEW
    biome = r.string()            # biomeId
    biome_category = r.string()   # NEW

    main_hand_id = r.string()

    inv_size = r.i32()
    inv = [r.string() for _ in range(inv_size)]

    armor_size = r.i32()
    armor_items = [r.string() for _ in range(armor_size)]

    offhand_size = r.i32()
    offhand = [r.string() for _ in range(offhand_size)]

    w = r.i32()
    h = r.i32()
    bgr_len = r.i32()

    if w <= 0 or h <= 0 or w > 4096 or h > 4096:
        raise ValueError(f"Bad image size: {w}x{h}")
    if bgr_len != w * h * 3:
        raise ValueError(f"Bad rgbLen: {bgr_len} (expected {w*h*3})")

    bgr = r._take(bgr_len)

    return {
        "seq": seq,
        "day_time": day_time,
        "hp": hp,
        "hp_max": hp_max,
        "armor": armor,
        "hunger": hunger,
        "air": air,
        "selected_slot": selected_slot,
        "main_hand": main_hand_id,
        "inventory": inv,
        "armor_items": armor_items,
        "offhand": offhand,
        "image_w": w,
        "image_h": h,
        "bgr": bgr,
        "dimension": dimension,           # NEW
        "biome": biome,                   # biomeId
        "biome_category": biome_category, # NEW
    }


def next_run_id(frames_root: Path, data_root: Path) -> int:
    used = set()
    for root in (frames_root, data_root):
        if root.exists():
            for p in root.iterdir():
                if p.is_dir() and p.name.startswith("run_"):
                    try:
                        used.add(int(p.name.split("_", 1)[1]))
                    except Exception:
                        pass
    i = 0
    while i in used:
        i += 1
    return i


def ensure_dirs(base_frames: Path, base_data: Path, run_id: int) -> Tuple[Path, Path]:
    frames_dir = base_frames / f"run_{run_id}"
    data_dir = base_data / f"run_{run_id}"
    frames_dir.mkdir(parents=True, exist_ok=True)
    data_dir.mkdir(parents=True, exist_ok=True)
    return frames_dir, data_dir


def encode_webp_bytes_from_bgr(bgr: bytes, w: int, h: int, *, method: int = 6) -> bytes:
    # BGR->RGB
    arr = np.frombuffer(bgr, dtype=np.uint8).reshape(h, w, 3)
    rgb = arr[:, :, ::-1]
    img = Image.fromarray(rgb, "RGB")

    # encode to bytes
    import io
    bio = io.BytesIO()
    img.save(bio, format="WEBP", quality=100, lossless=True, method=method)
    return bio.getvalue()


class Pipeline:
    """
    1) net thread кладёт jobs -> job_q
    2) N cv workers берут jobs и возвращают result -> res_q (webp bytes + meta)
    3) 1 writer thread пишет webp и jsonl в правильном порядке seq
    """

    def __init__(
        self,
        frames_dir: Path,
        jsonl_path: Path,
        *,
        workers: int,
        job_q_max: int,
        res_q_max: int,
        flush_every: int,
        webp_method: int,
        max_reorder_gap: int,
        on_processed: Optional[Callable[[int], None]] = None,
    ):
        self.frames_dir = frames_dir
        self.jsonl_path = jsonl_path
        self.job_q: "queue.Queue[Optional[tuple]]" = queue.Queue(maxsize=job_q_max)
        self.res_q: "queue.Queue[Optional[tuple]]" = queue.Queue(maxsize=res_q_max)
        self.stop = threading.Event()

        self.flush_every = flush_every
        self.webp_method = webp_method
        self.max_reorder_gap = max(1, max_reorder_gap)
        self.on_processed = on_processed

        self.worker_threads = [
            threading.Thread(target=self._cv_worker, name=f"cv_worker_{i}", daemon=True)
            for i in range(workers)
        ]
        self.writer_thread = threading.Thread(target=self._writer, name="writer", daemon=True)

    def start(self) -> None:
        for t in self.worker_threads:
            t.start()
        self.writer_thread.start()

    def submit(self, seq: int, w: int, h: int, bgr: bytes, rec: Dict[str, Any]) -> None:
        # без потерь: блокируемся если очередь заполнена
        self.job_q.put((seq, w, h, bgr, rec))

    def close(self) -> None:
        self.stop.set()
        for _ in self.worker_threads:
            self.job_q.put(None)
        for t in self.worker_threads:
            t.join()

        self.res_q.put(None)
        self.writer_thread.join()

    def _cv_worker(self) -> None:
        while not self.stop.is_set():
            item = self.job_q.get()
            if item is None:
                break
            seq, w, h, bgr, rec = item
            try:
                webp_bytes = encode_webp_bytes_from_bgr(bgr, w, h, method=self.webp_method)
                self.res_q.put((seq, webp_bytes, rec))
                if self.on_processed is not None:
                    self.on_processed(seq)
            finally:
                self.job_q.task_done()

    def _writer(self) -> None:
        pending: Dict[int, tuple] = {}
        expect: Optional[int] = None
        written = 0

        with open(self.jsonl_path, "a", encoding="utf-8") as jf:
            while True:
                item = self.res_q.get()
                if item is None:
                    break

                seq, webp_bytes, rec = item

                # если мы ещё не знаем "ожидаемый" seq — выставим на первый пришедший
                if expect is None:
                    expect = seq

                pending[seq] = (webp_bytes, rec)

                # пишем по порядку seq; если кадр пропал, не зависаем навсегда
                while True:
                    if expect in pending:
                        webp_bytes2, rec2 = pending.pop(expect)
                        frame_path = self.frames_dir / f"{expect}.webp"
                        frame_path.write_bytes(webp_bytes2)

                        rec_out = dict(rec2)
                        rec_out["frame_path"] = frame_path.as_posix()
                        jf.write(json.dumps(rec_out, ensure_ascii=False) + "\n")

                        written += 1
                        if self.flush_every and (written % self.flush_every == 0):
                            jf.flush()

                        expect += 1
                        continue

                    if pending:
                        max_pending_seq = max(pending)
                        if max_pending_seq - expect >= self.max_reorder_gap:
                            print(
                                f"⚠ Skip missing frame seq={expect}, "
                                f"next_available={min(pending)}, max_pending={max_pending_seq}"
                            )
                            expect += 1
                            continue
                    break

                self.res_q.task_done()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=5000)
    ap.add_argument("--frames-root", default="frames")
    ap.add_argument("--data-root", default="data")
    ap.add_argument("--run-id", type=int, default=None)

    ap.add_argument("--workers", type=int, default=4, help="CV/WebP worker threads")
    ap.add_argument("--job-queue-max", type=int, default=2000, help="Jobs queue size")
    ap.add_argument("--res-queue-max", type=int, default=2000, help="Results queue size")
    ap.add_argument("--flush-every", type=int, default=0, help="Flush jsonl every N records (0=never)")
    ap.add_argument("--webp-method", type=int, default=6, help="WebP method (0..6), 6=slow/best")
    ap.add_argument(
        "--max-reorder-gap",
        type=int,
        default=512,
        help="Skip missing seq if reorder gap exceeds this value",
    )
    ap.add_argument("--print-every", type=int, default=200)

    args = ap.parse_args()

    frames_root = Path(args.frames_root)
    data_root = Path(args.data_root)

    run_id = args.run_id if args.run_id is not None else next_run_id(frames_root, data_root)
    frames_dir, data_dir = ensure_dirs(frames_root, data_root, run_id)
    jsonl_path = data_dir / "data.jsonl"

    print(f"[run_{run_id}] frames -> {frames_dir}")
    print(f"[run_{run_id}] data   -> {jsonl_path}")

    status = LiveStatus(enabled=(args.print_every > 0))

    pipe: Optional[Pipeline] = None

    def on_processed(seq: int) -> None:
        if pipe is None:
            return
        status.bump_processed(seq, pipe.job_q.qsize(), pipe.job_q.maxsize)

    pipe = Pipeline(
        frames_dir,
        jsonl_path,
        workers=max(1, args.workers),
        job_q_max=args.job_queue_max,
        res_q_max=args.res_queue_max,
        flush_every=args.flush_every,
        webp_method=args.webp_method,
        max_reorder_gap=args.max_reorder_gap,
        on_processed=on_processed,
    )
    pipe.start()

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(1)

    print(f"Listening on {args.host}:{args.port} ...")

    pkt_count = 0
    last_seq: Optional[int] = None

    while True:
        conn, addr = srv.accept()
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        print(f"Client connected: {addr}")

        try:
            while True:
                header = recv_exact(conn, 4)
                (packet_len,) = struct.unpack(">i", header)
                if packet_len <= 0 or packet_len > 100_000_000:
                    raise ValueError(f"Bad packet_len: {packet_len}")

                payload = recv_exact(conn, packet_len)

                if maybe_handle_handshake(conn, payload):
                    continue

                obs = parse_packet(payload)
                seq = obs["seq"]

                if last_seq is not None and seq != last_seq + 1:
                    status.newline()
                    print(f"⚠ Frame gap detected: {last_seq} -> {seq}")
                last_seq = seq

                w = obs["image_w"]
                h = obs["image_h"]
                bgr = obs.pop("bgr")

                rec = {
                    "ts_server": time.time(),
                    "tick": seq,
                    **obs,
                }

                pipe.submit(seq, w, h, bgr, rec)

                pkt_count += 1
                status.update(
                    received=pkt_count,
                    last_tick=seq,
                    qsize=pipe.job_q.qsize(),
                    qmax=pipe.job_q.maxsize,
                )

        except (ConnectionError, OSError) as e:
            status.newline()
            print(f"Client disconnected: {addr} ({e})")
        except Exception as e:
            status.newline()
            print(f"Error while handling client {addr}: {e}")
            pass
        finally:
            try:
                conn.close()
            except Exception:
                pass


if __name__ == "__main__":
    main()
