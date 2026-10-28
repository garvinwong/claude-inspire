"""掌上灵感 · 端到端加密信封

配对：电脑生成 32 字节配对密钥，经二维码交给手机（不经服务器）。两个方向各派生一把密钥（HKDF-SHA256）：
  电脑→手机 info = b"palm v2 pc->m"，手机→电脑 info = b"palm v2 m->pc"。
信封（JSON）：{v, dev, kind, seq, ts, n, ct}
  v/dev/kind/seq/ts 明文（中转据此排队转发），并作为 AES-256-GCM 的附加认证数据：改任何一项都解不开；
  n = 12 字节随机数（Base64），ct = 密文（Base64），明文是 JSON。
防重放：|now - ts| ≤ 300 秒；滑动窗口——每个 seq 只收一次，比已收最大 seq 早 60 秒以上的拒收（窗口内乱序照收：
  两端都会并发发送，到达顺序与序号顺序不一致，真机实测严格递增会丢包）。seq 用“毫秒时间 × 1000 + 计数”，重启后也不倒退；
  接收方把各方向的下限与窗口内已收 seq 落盘（中枢重启后收过的依然拒收）。
手机端（Kotlin）用 javax.crypto 的 AES/GCM/NoPadding 与 HmacSHA256 实现同一格式，见 PLAN 第三节。
"""
import base64
import json
import os
import threading
import time
from pathlib import Path

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

VERSION = 2
WINDOW = 300.0
PC2M, M2PC = b'palm v2 pc->m', b'palm v2 m->pc'
KINDS = ('L1', 'L2', 'L3', 'L4', 'L5', 'L6', 'ack')


class EnvelopeError(Exception):
    """解不开 / 过期 / 重放 / 格式坏：一律丢弃该信封。"""


def derive(pair_key: bytes, info: bytes) -> bytes:
    if len(pair_key) != 32:
        raise ValueError('配对密钥须为 32 字节')
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=b'palm-inspire', info=info).derive(pair_key)


def aad_of(v, dev, kind, seq, ts) -> bytes:
    return f'{v}|{dev}|{kind}|{seq}|{ts}'.encode()


class Sealer:
    """发送方：一个方向一个。"""

    def __init__(self, pair_key: bytes, info: bytes, dev: str, clock=time.time):
        self.aes = AESGCM(derive(pair_key, info))
        self.dev = dev
        self.clock = clock
        self._last = 0
        self._lock = threading.Lock()

    def _next_seq(self) -> int:
        with self._lock:
            seq = max(int(self.clock() * 1000) * 1000, self._last + 1)
            self._last = seq
            return seq

    def seal(self, kind: str, payload) -> dict:
        if kind not in KINDS:
            raise ValueError(f'未知类型 {kind}')
        seq, ts = self._next_seq(), int(self.clock())
        n = os.urandom(12)
        ct = self.aes.encrypt(n, json.dumps(payload, ensure_ascii=False).encode(), aad_of(VERSION, self.dev, kind, seq, ts))
        return {'v': VERSION, 'dev': self.dev, 'kind': kind, 'seq': seq, 'ts': ts,
                'n': base64.b64encode(n).decode(), 'ct': base64.b64encode(ct).decode()}


REORDER = 60 * 1_000_000          # 乱序容忍窗口：60 秒（seq 单位是微秒级计数）


class Opener:
    """接收方：一个方向一个；state_path 落盘 {floor: {设备: 下限}, recent: {设备: [窗口内已收 seq]}}。"""

    def __init__(self, pair_key: bytes, info: bytes, state_path=None, clock=time.time):
        self.aes = AESGCM(derive(pair_key, info))
        self.clock = clock
        self.state_path = Path(state_path) if state_path else None
        self.floor, self.recent = self._load()
        self._lock = threading.Lock()

    def _load(self):
        try:
            d = json.loads(self.state_path.read_text())
        except (OSError, ValueError, AttributeError, TypeError):
            return {}, {}
        try:
            if isinstance(d, dict) and isinstance(d.get('floor'), dict):
                return ({k: int(v) for k, v in d['floor'].items()},
                        {k: {int(x) for x in v} for k, v in (d.get('recent') or {}).items()})
            return {k: int(v) for k, v in d.items()}, {}   # 旧版只存最大序号：当下限用，升级后不多收旧信封
        except (TypeError, ValueError, AttributeError):
            return {}, {}

    def _save(self):
        if not self.state_path:
            return
        tmp = self.state_path.with_suffix('.tmp')
        old = os.umask(0o177)
        try:
            tmp.write_text(json.dumps({'floor': self.floor, 'recent': {k: sorted(v) for k, v in self.recent.items()}}))
        finally:
            os.umask(old)
        os.replace(tmp, self.state_path)

    def open(self, env: dict):
        """返回 (kind, payload)；任何问题抛 EnvelopeError。"""
        try:
            v, dev, kind, seq, ts = env['v'], str(env['dev']), env['kind'], int(env['seq']), int(env['ts'])
            n, ct = base64.b64decode(env['n'], validate=True), base64.b64decode(env['ct'], validate=True)
        except (KeyError, TypeError, ValueError) as e:
            raise EnvelopeError(f'格式坏：{e}')
        if v != VERSION or kind not in KINDS or len(n) != 12:
            raise EnvelopeError('版本、类型或随机数不对')
        if abs(self.clock() - ts) > WINDOW:
            raise EnvelopeError('超出时间窗')
        try:
            pt = self.aes.decrypt(n, ct, aad_of(v, dev, kind, seq, ts))
        except Exception:
            raise EnvelopeError('解不开（密钥不对或被篡改）')
        with self._lock:                               # 先验真伪再比序号：假信封不能推高序号
            floor, seen = self.floor.get(dev, 0), self.recent.setdefault(dev, set())
            if seq <= floor or seq in seen:
                raise EnvelopeError('重放（序号已收过或早于窗口）')
            seen.add(seq)
            floor = max(floor, max(seen) - REORDER)
            self.floor[dev] = floor
            self.recent[dev] = {x for x in seen if x > floor}
            self._save()
        try:
            return kind, json.loads(pt)
        except ValueError:
            raise EnvelopeError('明文不是 JSON')


def new_pair_key() -> bytes:
    return os.urandom(32)
