"""掌上灵感 · 中枢外连中转

电脑主动外连公网中转的 v2 信箱，与手机之间只走加密信封（envelope.py）。中转只看见设备、类型、大小、时间。

手机 → 电脑（L5 命令，明文 {id, op, args}），电脑回 ack {ref, ok, data | reason}：
  sessions{scope} / events{sid,tail|since|before}（回 L4，可分片）/ image{id}（回 L6 附件元数据）/ screen{sid}
  inject{sid,text,force,queue} / keys{sid,keys} / pending / decision{id,decision,reason,answers}
  resume{sid,fork} / new{account,cwd,model,effort,permission_mode,prompt} / new_options / keep_alive{sid,on}
  outbox / outbox_cancel{qid} / recent_dirs
  commands{sid}（回 items，可分片）/ files{sid,q}     斜杠命令面板与 @ 文件补全（complete.py）
  watch{sid,cursor} / unwatch{sid}       订阅某会话的增量事件（手机在线才推，120 秒不续订自动退订）
  send_files{sid,text,files:[附件元数据],force,queue}   取回附件解密、落 ~/.palm/inbox/（PALM_INBOX_DIR 可改），路径拼进消息注入
电脑 → 手机（主动推送，只在手机在线时）：L1 会话总览变化、L2 待审批变化、L4 订阅会话的新事件。

附件：整份文件用方向密钥 AES-256-GCM 加密（附加认证数据 = 附件编号），密文切 96 KB 分片走 /v2/blob；
  元数据 {blob, parts, nonce, bytes, mime, name} 放在信封里。
"""
import base64
import hashlib
import json
import os
import re
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from envelope import M2PC, PC2M, EnvelopeError, Opener, Sealer, derive

PART = 96 * 1024
ENV_BUDGET = 40 * 1024          # 单个信封明文预算：加密后 Base64 膨胀 4/3，中转上限 64 KB（48 KB 会超，真机实测）
FILE_MAX = 20 * 1024 * 1024
PEER_ONLINE = 40                # 手机多少秒内取过件算在线
WATCH_TTL = 120.0
PUSH_EVERY = 1.0
INBOX_KEEP_DAYS = 7
BLOB_PC2M, BLOB_M2PC = b'palm v2 blob pc->m', b'palm v2 blob m->pc'


def _digest(obj) -> str:
    return hashlib.sha256(json.dumps(obj, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def safe_name(name: str) -> str:
    base = Path(str(name or 'file')).name
    base = re.sub(r'[^\w.\-一-鿿]', '_', base).strip('._') or 'file'
    return base[-80:]


class RelayLink:
    def __init__(self, hub, relay_url, token, pair_key: bytes, me='palm-pc', peer='phone',
                 state_dir=None, inbox_dir=None, clock=time.time):
        self.hub = hub
        self.url = relay_url.rstrip('/')
        self.token = token
        self.me, self.peer = me, peer
        state_dir = Path(state_dir or hub.home)
        self.sealer = Sealer(pair_key, PC2M, me, clock)
        self.opener = Opener(pair_key, M2PC, state_dir / 'replay.json', clock)
        self.blob_up = AESGCM(derive(pair_key, BLOB_PC2M))
        self.blob_down = AESGCM(derive(pair_key, BLOB_M2PC))
        self.inbox_dir = Path(inbox_dir or os.environ.get('PALM_INBOX_DIR')
                              or '~/.palm/inbox').expanduser()
        self.since, self.boot = 0, None
        self.watch = {}                                # sid → {cursor, until}
        self.last = {'L1': None, 'L2': None}
        self.peer_age = None
        self.stop = threading.Event()
        self.stats = {'in': 0, 'bad': 0, 'out': 0}
        self.last_poll = None                          # 最近一次成功取件（palm doctor 看链路是否活着）
        self.poll_started = None                       # 在途长轮询的发起时间（最长 25 秒才回，刚启动时 last_poll 还是空）

    # 传输 --------------------------------------------------------------
    def _http(self, method, path, body=None, raw=None, headers=None, timeout=35):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        h = {'Authorization': f'Bearer {self.token}'}
        if body is not None:
            h['Content-Type'] = 'application/json'
        h.update(headers or {})
        req = urllib.request.Request(self.url + path, data=data, method=method, headers=h)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            payload = r.read()
            if r.headers.get('Content-Type') == 'application/octet-stream':
                return payload
            return json.loads(payload or b'{}')

    def send(self, kind, payload):
        env = self.sealer.seal(kind, payload)
        r = self._http('POST', '/v2/send', {'to': self.peer, 'items': [env]})
        self.stats['out'] += 1
        if isinstance(r, dict) and r.get('peer_age') is not None:
            self.peer_age = r['peer_age']
        return r

    def send_list(self, kind, payload, key):
        """payload[key] 是列表：按明文大小切成多个信封，带 part / parts。"""
        items, chunks, cur, size = payload.get(key) or [], [], [], 0
        for it in items:
            n = len(json.dumps(it, ensure_ascii=False).encode())
            if cur and size + n > ENV_BUDGET:
                chunks.append(cur)
                cur, size = [], 0
            cur.append(it if n <= ENV_BUDGET else {'k': 'system', 'text': '（这一条太大，未传到手机）'})
            size += min(n, ENV_BUDGET)
        chunks.append(cur)
        for i, c in enumerate(chunks):
            self.send(kind, dict(payload, **{key: c, 'part': i, 'parts': len(chunks)}))

    # 附件 --------------------------------------------------------------
    def blob_upload(self, data: bytes, mime='application/octet-stream', name='') -> dict:
        bid = os.urandom(16).hex()
        nonce = os.urandom(12)
        ct = self.blob_up.encrypt(nonce, data, bid.encode())
        parts = max(1, (len(ct) + PART - 1) // PART)
        for i in range(parts):
            self._http('PUT', f'/v2/blob/{bid}?part={i}', raw=ct[i * PART:(i + 1) * PART],
                       headers={'X-To': self.peer, 'X-Total': str(parts), 'Content-Type': 'application/octet-stream'})
        return {'blob': bid, 'parts': parts, 'nonce': base64.b64encode(nonce).decode(), 'bytes': len(data),
                'mime': mime, 'name': name}

    def blob_download(self, meta: dict) -> bytes:
        bid, parts = str(meta.get('blob') or ''), int(meta.get('parts') or 0)
        if not re.fullmatch(r'[0-9a-f]{32}', bid) or not 1 <= parts <= 256:
            raise ValueError('附件元数据不对')
        ct = b''.join(self._http('GET', f'/v2/blob/{bid}?part={i}') for i in range(parts))
        data = self.blob_down.decrypt(base64.b64decode(meta['nonce']), ct, bid.encode())
        try:
            self._http('DELETE', f'/v2/blob/{bid}')           # 取走即删
        except (OSError, urllib.error.URLError):
            pass
        if len(data) > FILE_MAX:
            raise ValueError('附件超过 20 MB')
        return data

    def save_inbox(self, name: str, data: bytes) -> Path:
        self.inbox_dir.mkdir(parents=True, exist_ok=True)
        cutoff = time.time() - INBOX_KEEP_DAYS * 86400          # 7 天自动清
        for f in self.inbox_dir.iterdir():
            try:
                if f.is_file() and f.stat().st_mtime < cutoff:
                    f.unlink()
            except OSError:
                pass
        path = self.inbox_dir / f"{time.strftime('%Y%m%d-%H%M%S')}-{os.urandom(2).hex()}-{safe_name(name)}"
        path.write_bytes(data)
        return path

    # 命令 --------------------------------------------------------------
    def dispatch(self, op: str, a: dict):
        hub = self.hub
        if op == 'sessions':
            return {'sessions': hub.sessions(a.get('scope', 'live'))}
        if op == 'pending':
            return {'pending': hub.pending()}
        if op == 'usage':
            return {'usage': hub.usage(), 'pc_active': hub.pc_active()}
        if op == 'screen':
            return hub.screen(str(a.get('sid') or ''))
        if op == 'recent_dirs':
            return {'dirs': hub.recent_dirs()}
        if op == 'new_options':
            return hub.new_options()
        if op == 'outbox':
            return {'outbox': hub.outbox_list()}
        if op == 'stats':
            return hub.stats(a.get('days', 7))
        if op == 'commands':
            return hub.commands(str(a.get('sid') or ''))
        if op == 'files':
            return hub.files(str(a.get('sid') or ''), a.get('q') or '')
        if op == 'outbox_cancel':
            return hub.outbox_cancel(str(a.get('qid') or ''))
        if op == 'usage_refresh':
            return hub.refresh_usage(str(a.get('account') or ''))
        if op == 'yolo':
            return hub.yolo(str(a.get('sid') or ''), bool(a.get('on')))
        if op == 'close':
            return hub.close(str(a.get('sid') or ''))
        if op == 'open_on_pc':
            return hub.open_on_pc(str(a.get('sid') or ''))
        if op == 'outbox_flush':
            return hub.outbox_flush(str(a.get('qid') or ''), str(a.get('mode') or ''))
        if op == 'inject':
            return hub.inject(a)
        if op == 'keys':
            keys = a.get('keys') if isinstance(a.get('keys'), list) else []
            return hub.host_call(str(a.get('sid') or ''), {'op': 'keys', 'keys': keys[:32]})
        if op == 'keep_alive':
            return hub.host_call(str(a.get('sid') or ''), {'op': 'keep_alive', 'on': bool(a.get('on'))})
        if op == 'decision':
            return hub.decision(a)
        if op == 'resume':
            return hub.resume(a)
        if op == 'new':
            return hub.new_session(a)
        if op == 'watch':
            sid = str(a.get('sid') or '')
            if not hub.find(sid):
                return {'ok': False, 'reason': 'no_session'}
            cur = a.get('cursor')
            if cur is None:
                cur = hub.events(sid, tail=1).get('cursor', 0)
            self.watch[sid] = {'cursor': int(cur), 'until': time.time() + WATCH_TTL}
            return {'ok': True, 'cursor': int(cur)}
        if op == 'unwatch':
            self.watch.pop(str(a.get('sid') or ''), None)
            return {'ok': True}
        if op == 'image':
            p = hub.images.find(str(a.get('id') or ''))
            if not p:
                return {'ok': False, 'reason': 'no_image'}
            mime = {'.png': 'image/png', '.jpg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp'}.get(p.suffix, '')
            return {'ok': True, **self.blob_upload(p.read_bytes(), mime, p.name)}
        if op == 'send_files':
            files = a.get('files') if isinstance(a.get('files'), list) else []
            if not files or len(files) > 9:
                return {'ok': False, 'reason': 'bad_files'}
            paths = [str(self.save_inbox(f.get('name') or 'file', self.blob_download(f))) for f in files]
            text = (str(a.get('text') or '').strip() + '\n\n[手机发来的附件]\n' + '\n'.join(paths)).strip()
            r = hub.inject({'sid': a.get('sid'), 'text': text, 'force': a.get('force'), 'clear': a.get('clear'),
                            'queue': a.get('queue')})
            return dict(r, paths=paths)
        return {'ok': False, 'reason': f'unknown_op: {op}'}

    def handle(self, env: dict):
        try:
            kind, msg = self.opener.open(env)
        except EnvelopeError:
            self.stats['bad'] += 1                     # 解不开 / 重放 / 过期：丢弃，不回应
            return
        self.stats['in'] += 1
        if kind != 'L5' or not isinstance(msg, dict):
            return
        ref, op, args = msg.get('id'), str(msg.get('op') or ''), msg.get('args') or {}
        try:
            if op == 'events':
                r = self.hub.events(args.get('sid'), args.get('tail', 200), args.get('since'), args.get('before'))
                if r.get('ok'):
                    return self.send_list('L4', dict(r, ref=ref), 'events')
                return self.send('ack', {'ref': ref, **r})
            r = self.dispatch(op, args if isinstance(args, dict) else {})
        except Exception as e:                         # 单条命令出错只回错，不拖垮链路
            r = {'ok': False, 'reason': f'error: {e}'}
        r = r if isinstance(r, dict) else {'data': r}
        r.setdefault('ok', True)
        try:
            if op in ('sessions', 'commands') and r.get('ok'):
                return self.send_list('ack', dict(r, ref=ref), 'sessions' if op == 'sessions' else 'items')
            self.send('ack', dict(r, ref=ref))
        except (OSError, ValueError, urllib.error.URLError) as e:   # 回执发不出去（如超限）：回短的失败回执，别让手机空等
            self.send('ack', {'ref': ref, 'ok': False, 'reason': f'reply_failed: {str(e)[:80]}'})

    def status(self) -> dict:
        return {'url': self.url, 'me': self.me, 'peer': self.peer, 'peer_age': self.peer_age, 'watching': len(self.watch),
                'poll_age': None if self.last_poll is None else int(time.time() - self.last_poll),
                'in_flight': None if self.poll_started is None else int(time.time() - self.poll_started), **self.stats}

    # 循环 --------------------------------------------------------------
    def poll_once(self, timeout=25):
        self.poll_started = time.time()
        try:
            r = self._http('GET', f'/v2/inbox?since={self.since}&timeout={timeout}', timeout=timeout + 10)
        finally:
            self.poll_started = None
        self.last_poll = time.time()
        if r.get('boot') != self.boot:
            if self.boot is not None and self.since:  # 中转重启过：序号从头
                self.since = 0
            self.boot = r.get('boot')
        for it in r.get('items') or []:
            self.since = max(self.since, int(it.get('rseq') or 0))
            self.handle(it.get('env') or {})
        return r

    def push_once(self):
        peers = self._http('GET', '/v2/peers').get('peers') or {}
        age = peers.get(self.peer)
        self.peer_age = age
        now = time.time()
        for sid in [s for s, w in self.watch.items() if w['until'] < now]:
            self.watch.pop(sid, None)
        if age is None or age > PEER_ONLINE:
            self.last = {'L1': None, 'L2': None}      # 手机回来时补一份全量
            return False
        sessions = self.hub.sessions('live')
        usage, pc_active = self.hub.usage(), self.hub.pc_active()
        d = _digest([[(r['sid'], r.get('status'), r.get('title'), r.get('control'), r.get('context_pct'), r.get('model'),
                       r.get('effort'), (r.get('activity') or {}).get('ts'), r.get('yolo'), r.get('keep_alive')) for r in sessions],
                     {k: [v.get('h5', {}).get('pct'), v.get('d7', {}).get('pct')] for k, v in usage.items()}, pc_active])
        if d != self.last['L1']:
            self.send_list('L1', {'sessions': sessions, 'usage': usage, 'pc_active': pc_active}, 'sessions')
            self.last['L1'] = d
        pending = self.hub.pending()
        d = _digest([p.get('id') for p in pending])
        if d != self.last['L2']:
            self.send('L2', {'pending': pending})
            self.last['L2'] = d
        for sid, w in list(self.watch.items()):
            r = self.hub.events(sid, since=w['cursor'])
            if r.get('ok') and r.get('events'):
                w['cursor'] = r['cursor']
                self.send_list('L4', {'sid': sid, 'events': r['events'], 'cursor': r['cursor'], 'push': True}, 'events')
        return True

    def run(self):
        def inbox_loop():
            backoff = 0
            while not self.stop.is_set():
                try:
                    self.poll_once()
                    backoff = 0
                except (OSError, urllib.error.URLError, ValueError):
                    backoff = min(30, backoff * 2 + 3)
                    self.stop.wait(backoff)

        def push_loop():
            while not self.stop.is_set():
                try:
                    self.push_once()
                except (OSError, urllib.error.URLError, ValueError):
                    self.stop.wait(5)
                self.stop.wait(PUSH_EVERY)

        for f in (inbox_loop, push_loop):
            threading.Thread(target=f, daemon=True).start()


def load_link(hub):
    """~/.palm/link.json：{relay_url, token, me, peer, pair_key_b64}（600，palm pair 生成）。没有就不外连。"""
    p = hub.home / 'link.json'
    try:
        cfg = json.loads(p.read_text(encoding='utf-8'))
        key = base64.b64decode(cfg['pair_key_b64'])
        return RelayLink(hub, cfg['relay_url'], cfg['token'], key, cfg.get('me', 'palm-pc'), cfg.get('peer', 'phone'))
    except (OSError, ValueError, KeyError):
        return None
