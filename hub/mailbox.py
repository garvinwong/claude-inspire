"""局域网信箱：手机与电脑之间的“邮局”，内置在中枢进程里（不需要公网服务器）。

内容全是端到端加密信封，信箱只看 dev / kind / seq / ts 与大小，不解密。设备用各自的令牌鉴权（只存 SHA-256 摘要）。
  POST /v2/send {"to": 设备, "items": [信封...]}   只许 client→pc、pc→client；信封 dev 必须是发件设备本身
  GET  /v2/inbox?since=&timeout=                   长连接（≤25 秒）；返回 {boot, items:[{rseq, env}], dropped}
                                                   since 之前的视为已收、随即释放；boot 变了说明信箱重启过，since 归零
  GET  /v2/peers                                   对方角色各设备最近一次在线距今秒数
  PUT  /v2/blob/<32位十六进制>?part=n               附件密文分片（≤96 KB）；首片带 X-To、X-Total
  GET  /v2/blob/<编号>?part=n、DELETE /v2/blob/<编号>   只有发件人与收件人可取可删
附件密文落 ~/.palm/mailbox-blobs/：30 分钟过期、总量 512 MB、重启清空；明文不落盘。
监听地址由 PALM_LAN_BIND / PALM_LAN_PORT 决定（默认 0.0.0.0:5613）；外出时建议用 Tailscale 等组网后连电脑的组网地址。
"""
import hashlib
import hmac
import json
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DEFAULT_PORT = 5613
FAIL_LIMIT = 5                # 令牌连错次数
LOCK_SECONDS = 60
V2_BODY_MAX = 120 * 1024
V2_ENV_MAX = 64 * 1024
V2_WAIT_MAX = 25.0
V2_QUEUE_MAX = 500
V2_QUEUE_BYTES = 8 * 1024 * 1024
V2_TTL = 600.0
V2_KINDS = ('L1', 'L2', 'L3', 'L4', 'L5', 'L6', 'ack')
ENV_KEYS = {'v', 'dev', 'kind', 'seq', 'ts', 'n', 'ct'}
BLOB_PART_MAX = 96 * 1024
BLOB_PARTS_MAX = 256
BLOB_TTL = 1800.0
BLOB_TOTAL_MAX = 512 * 1024 * 1024
BLOB_ID = re.compile(r'[0-9a-f]{32}')

# ── 设备与鉴权 ──────────────────────────────────────────────

def load_devices(path):
    """设备表 {"devices": {名称: {"role": "pc"|"client", "sha256": 令牌摘要}}}。"""
    with open(path, encoding='utf-8') as f:
        data = json.load(f)
    devs = {}
    for name, d in (data.get('devices') or {}).items():
        role, digest = d.get('role'), str(d.get('sha256') or '').lower()
        if role in ('pc', 'client') and len(digest) == 64:
            devs[name] = (role, digest)
    if not devs:
        raise ValueError(f'{path} 里没有有效设备')
    return devs


class Guard:
    """令牌比对（按摘要，恒定时间）+ 按来源 IP 计错，连错 FAIL_LIMIT 次锁 LOCK_SECONDS 秒。"""

    def __init__(self, devices, clock=time.monotonic):
        self.devices = devices
        self.clock = clock
        self.fails = {}
        self.lock = threading.Lock()

    def check(self, ip, header):
        """返回 (http 码, 设备名, 角色)。"""
        now = self.clock()
        with self.lock:
            n, until = self.fails.get(ip, (0, 0))
            if until and now < until:
                return 429, None, None
            if until and now >= until:
                n = 0
            token = (header or '')[7:] if (header or '').startswith('Bearer ') else ''
            digest = hashlib.sha256(token.encode()).hexdigest() if token else ''
            for name, (role, d) in self.devices.items():
                if digest and hmac.compare_digest(digest, d):
                    self.fails.pop(ip, None)
                    return 200, name, role
            n += 1
            self.fails[ip] = (n, now + LOCK_SECONDS if n >= FAIL_LIMIT else 0)
            return 401, None, None


# ── 信箱状态（全部在内存，附件密文暂存磁盘） ─────────────────

class Mailbox:
    def __init__(self, devices, blob_dir=None, clock=time.monotonic):
        self.roles = {name: role for name, (role, _d) in devices.items()}
        self.clock = clock
        self.cv = threading.Condition()
        self.q = {}               # 收件设备 -> [(rseq, 入队时刻, 大小, 信封)]
        self.rseq = 0
        self.dropped = {}
        self.seen = {}            # 设备 -> 最近一次取件时刻
        self.boot = os.urandom(6).hex()
        self.blob_dir = Path(blob_dir) if blob_dir else None
        self.blobs = {}           # 编号 -> {from, to, total, parts:{序号: 大小}, created}
        self.blob_lock = threading.Lock()
        if self.blob_dir:
            self.blob_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
            for f in self.blob_dir.iterdir():          # 重启清空
                if f.is_file() and re.fullmatch(r'[0-9a-f]{32}\.\d+', f.name):
                    f.unlink()

    # 信封 --------------------------------------------------------------
    def send(self, sender, to, items):
        if to not in self.roles:
            return 404, {'error': 'no such device'}
        if self.roles[to] == self.roles.get(sender):
            return 403, {'error': 'forbidden route'}
        if not isinstance(items, list) or not items:
            return 400, {'error': 'bad items'}
        checked = []
        for env in items:
            if not isinstance(env, dict) or set(env) != ENV_KEYS or env.get('v') != 2 \
                    or env.get('kind') not in V2_KINDS or env.get('dev') != sender:
                return 400, {'error': 'bad envelope'}
            size = len(json.dumps(env))
            if size > V2_ENV_MAX:
                return 400, {'error': 'envelope too large'}
            checked.append((size, env))
        with self.cv:
            now = self.clock()
            box = self.q.setdefault(to, [])
            for size, env in checked:
                self.rseq += 1
                box.append((self.rseq, now, size, env))
            self._trim(to)
            self.cv.notify_all()
            age = self.seen.get(to)
            return 200, {'ok': True, 'accepted': len(checked),
                         'peer_age': None if age is None else int(now - age)}

    def _trim(self, dev):
        box, now = self.q.get(dev, []), self.clock()
        keep = [x for x in box if now - x[1] <= V2_TTL]
        drop = len(box) - len(keep)
        while len(keep) > V2_QUEUE_MAX or sum(x[2] for x in keep) > V2_QUEUE_BYTES:
            keep.pop(0)
            drop += 1
        if drop:
            self.dropped[dev] = self.dropped.get(dev, 0) + drop
        self.q[dev] = keep

    def inbox(self, dev, since, timeout):
        deadline = self.clock() + max(0.0, min(timeout, V2_WAIT_MAX))
        with self.cv:
            self.q[dev] = [x for x in self.q.get(dev, []) if x[0] > since]   # since 之前的已收，释放
            while True:
                self.seen[dev] = self.clock()
                self._trim(dev)
                out = [{'rseq': r, 'env': e} for r, _t, _s, e in self.q.get(dev, []) if r > since]
                left = deadline - self.clock()
                if out or left <= 0:
                    return {'boot': self.boot, 'items': out, 'dropped': self.dropped.pop(dev, 0)}
                self.cv.wait(left)

    def peers(self, dev):
        me, now = self.roles.get(dev), self.clock()
        with self.cv:
            return {n: (None if self.seen.get(n) is None else int(now - self.seen[n]))
                    for n, r in self.roles.items() if r != me}

    # 附件密文 ----------------------------------------------------------
    def _blob_gc(self):
        now = self.clock()
        for bid in [b for b, m in self.blobs.items() if now - m['created'] > BLOB_TTL]:
            self._blob_drop(bid)

    def _blob_drop(self, bid):
        m = self.blobs.pop(bid, None)
        for part in (m or {}).get('parts', {}):
            try:
                (self.blob_dir / f'{bid}.{part}').unlink()
            except OSError:
                pass

    def blob_put(self, sender, bid, part, to, total, data):
        if not self.blob_dir:
            return 503, {'error': 'blobs disabled'}
        if not BLOB_ID.fullmatch(bid or '') or not (0 <= part < BLOB_PARTS_MAX) or len(data) > BLOB_PART_MAX:
            return 400, {'error': 'bad blob'}
        with self.blob_lock:
            self._blob_gc()
            m = self.blobs.get(bid)
            if m is None:
                if to not in self.roles or self.roles[to] == self.roles.get(sender) \
                        or not (1 <= total <= BLOB_PARTS_MAX):
                    return 400, {'error': 'bad blob header'}
                m = self.blobs[bid] = {'from': sender, 'to': to, 'total': total, 'parts': {}, 'created': self.clock()}
            elif m['from'] != sender:
                return 403, {'error': 'forbidden'}
            if part >= m['total']:
                return 400, {'error': 'part out of range'}
            used = sum(sum(x['parts'].values()) for x in self.blobs.values())
            if used - m['parts'].get(part, 0) + len(data) > BLOB_TOTAL_MAX:
                return 507, {'error': 'storage full'}
            path = self.blob_dir / f'{bid}.{part}'
            with open(path, 'wb') as f:
                f.write(data)
            os.chmod(path, 0o600)
            m['parts'][part] = len(data)
            return 200, {'ok': True, 'have': len(m['parts']), 'total': m['total']}

    def _blob_access(self, dev, bid):
        if not self.blob_dir or not BLOB_ID.fullmatch(bid or ''):
            return None, (400, {'error': 'bad blob'})
        self._blob_gc()
        m = self.blobs.get(bid)
        if m is None:
            return None, (404, {'error': 'no such blob'})
        if dev not in (m['from'], m['to']):
            return None, (403, {'error': 'forbidden'})
        return m, None

    def blob_get(self, dev, bid, part):
        with self.blob_lock:
            m, err = self._blob_access(dev, bid)
            if err:
                return err[0], err[1], None
            if part not in m['parts']:
                return 404, {'error': 'no such part'}, None
            return 200, None, (self.blob_dir / f'{bid}.{part}').read_bytes()

    def blob_delete(self, dev, bid):
        with self.blob_lock:
            m, err = self._blob_access(dev, bid)
            if err:
                return err
            self._blob_drop(bid)
            return 200, {'ok': True}


def _query(q):
    return dict(kv.split('=', 1) for kv in q.split('&') if '=' in kv)


def _num(v, default, cast=float):
    try:
        return cast(v)
    except (TypeError, ValueError):
        return default


# ── HTTP ────────────────────────────────────────────────────

def make_handler(guard, mailbox, audit=None):
    audit = audit or (lambda ip, dev, ep, result: None)

    class Handler(BaseHTTPRequestHandler):
        server_version = 'mailbox'
        sys_version = ''
        protocol_version = 'HTTP/1.1'

        def log_message(self, fmt, *args):
            pass

        def version_string(self):
            return self.server_version

        def _ip(self):
            return self.client_address[0]

        def _send(self, code, obj):
            body = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Cache-Control', 'no-store')
            self.end_headers()
            self.wfile.write(body)

        def _auth_any(self, ep):
            code, dev, role = guard.check(self._ip(), self.headers.get('Authorization'))
            if code != 200:
                audit(self._ip(), dev, ep, 'locked' if code == 429 else 'unauthorized')
                self._send(code, {'error': 'locked' if code == 429 else 'unauthorized'})
                return None
            return dev

        def _raw(self, limit):
            n = _num(self.headers.get('Content-Length'), -1, int)
            if n < 0 or n > limit:
                self._send(413 if n > limit else 411, {'error': 'too large' if n > limit else 'length required'})
                self.close_connection = True
                return None
            return self.rfile.read(n)

        def _v2(self, method):
            path, _, query = self.path.partition('?')
            q = _query(query)
            ep = '/v2/blob' if path.startswith('/v2/blob/') else path
            dev = self._auth_any(ep)
            if dev is None:
                return
            if path.startswith('/v2/blob/'):
                bid, part = path[len('/v2/blob/'):], _num(q.get('part'), -1, int)
                if method == 'PUT':
                    data = self._raw(BLOB_PART_MAX)
                    if data is None:
                        return
                    code, obj = mailbox.blob_put(dev, bid, part, self.headers.get('X-To') or '',
                                                 _num(self.headers.get('X-Total'), 0, int), data)
                    audit(self._ip(), dev, ep, f'put {code}')
                    return self._send(code, obj)
                if method == 'GET':
                    code, obj, data = mailbox.blob_get(dev, bid, part)
                    if data is None:
                        return self._send(code, obj)
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/octet-stream')
                    self.send_header('Content-Length', str(len(data)))
                    self.send_header('Cache-Control', 'no-store')
                    self.end_headers()
                    self.wfile.write(data)
                    return
                if method == 'DELETE':
                    return self._send(*mailbox.blob_delete(dev, bid))
                return self._send(405, {'error': 'method not allowed'})
            if path == '/v2/send' and method == 'POST':
                raw = self._raw(V2_BODY_MAX)
                if raw is None:
                    return
                try:
                    data = json.loads(raw or b'{}')
                    assert isinstance(data, dict)
                except (ValueError, AssertionError):
                    return self._send(400, {'error': 'bad json'})
                code, obj = mailbox.send(dev, str(data.get('to') or ''), data.get('items'))
                if code != 200:
                    audit(self._ip(), dev, ep, obj.get('error'))
                return self._send(code, obj)
            if path == '/v2/inbox' and method == 'GET':
                return self._send(200, mailbox.inbox(dev, _num(q.get('since'), 0, int), _num(q.get('timeout'), V2_WAIT_MAX)))
            if path == '/v2/peers' and method == 'GET':
                return self._send(200, {'peers': mailbox.peers(dev)})
            return self._send(404, {'error': 'not found'})

        def do_PUT(self):
            if self.path.startswith('/v2/'):
                return self._v2('PUT')
            return self._send(404, {'error': 'not found'})

        def do_DELETE(self):
            if self.path.startswith('/v2/'):
                return self._v2('DELETE')
            return self._send(404, {'error': 'not found'})

        def do_GET(self):
            if self.path.startswith('/v2/'):
                return self._v2('GET')
            return self._send(404, {'error': 'not found'})

        def do_POST(self):
            if self.path.startswith('/v2/'):
                return self._v2('POST')
            return self._send(404, {'error': 'not found'})
    return Handler


def make_server(bind, port, devices_path, blob_dir, audit=None):
    """起信箱：读设备表，返回 (server, mailbox)。由中枢在后台线程里 serve_forever。"""
    devices = load_devices(devices_path)
    guard = Guard(devices)
    mailbox = Mailbox(devices, blob_dir)
    srv = ThreadingHTTPServer((bind, port), make_handler(guard, mailbox, audit))
    srv.daemon_threads = True
    return srv, mailbox
