"""中枢外连测试：进程内真中转（v2）+ 中枢（假桥 + 真宿主跑假会话）+ 模拟手机（同一套加密协议）。"""
import base64
import hashlib
import json
import sys
import threading
import time
import urllib.request
import uuid
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'hub'))
import mailbox  # noqa: E402
import relay_link  # noqa: E402
from cryptography.hazmat.primitives.ciphers.aead import AESGCM  # noqa: E402
from envelope import M2PC, PC2M, Opener, Sealer, derive  # noqa: E402
from test_hub import PNG, call, env  # noqa: E402,F401

KEY = bytes(range(1, 33))
TOK = {'palm-pc': 'palmpc-token-0123456789abcdef', 'phone': 'phone-token-0123456789abcdef'}


class Phone:
    def __init__(self, base, key=KEY):
        self.base = base
        self.sealer, self.opener = Sealer(key, M2PC, 'phone'), Opener(key, PC2M)
        self.up, self.down = AESGCM(derive(key, relay_link.BLOB_M2PC)), AESGCM(derive(key, relay_link.BLOB_PC2M))
        self.since, self.msgs, self.last_env = 0, [], None

    def http(self, method, path, body=None, raw=None, headers=None):
        h = {'Authorization': 'Bearer ' + TOK['phone'], **(headers or {})}
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        with urllib.request.urlopen(urllib.request.Request(self.base + path, data=data, method=method, headers=h), timeout=10) as r:
            b = r.read()
            return b if r.headers.get('Content-Type') == 'application/octet-stream' else json.loads(b or b'{}')

    def send_env(self, env):
        return self.http('POST', '/v2/send', {'to': 'palm-pc', 'items': [env]})

    def cmd(self, op, **args):
        cid = uuid.uuid4().hex[:8]
        self.last_env = self.sealer.seal('L5', {'id': cid, 'op': op, 'args': args})
        self.send_env(self.last_env)
        return cid

    def poll(self, timeout=1):
        r = self.http('GET', f'/v2/inbox?since={self.since}&timeout={timeout}')
        for it in r['items']:
            self.since = max(self.since, it['rseq'])
            self.msgs.append(self.opener.open(it['env']))

    def wait(self, pred, timeout=6):
        end = time.time() + timeout
        while time.time() < end:
            for m in self.msgs:
                if pred(*m):
                    return m[1]
            self.poll(1)
        raise AssertionError(f'等不到消息；已收：{[(k, list(v)[:4]) for k, v in self.msgs]}')

    def ack(self, cid, timeout=6):
        """与 App 的 Link 一致：带列表（sessions / events / items）且有 parts 的回执，收齐后合并。"""
        end = time.time() + timeout
        while time.time() < end:
            got = [m for k, m in self.msgs if k == 'ack' and m.get('ref') == cid]
            if got:
                key = next((k for k in ('sessions', 'events', 'items') if k in got[0]), None)
                if key is None or 'parts' not in got[0]:
                    return got[0]
                if len(got) >= got[0]['parts']:
                    merged = dict(sorted(got, key=lambda m: m['part'])[0])
                    merged[key] = [x for m in sorted(got, key=lambda m: m['part']) for x in m[key]]
                    return merged
            self.poll(1)
        raise AssertionError(f'等不到回执 {cid}；已收：{[(k, list(v)[:4]) for k, v in self.msgs]}')

    def upload(self, data, name, mime):
        bid, nonce = uuid.uuid4().hex, b'\x01' * 12
        ct = self.up.encrypt(nonce, data, bid.encode())
        parts = (len(ct) + relay_link.PART - 1) // relay_link.PART
        for i in range(parts):
            self.http('PUT', f'/v2/blob/{bid}?part={i}', raw=ct[i * relay_link.PART:(i + 1) * relay_link.PART],
                      headers={'X-To': 'palm-pc', 'X-Total': str(parts)})
        return {'blob': bid, 'parts': parts, 'nonce': base64.b64encode(nonce).decode(), 'bytes': len(data),
                'mime': mime, 'name': name}


@pytest.fixture
def link(env, tmp_path, monkeypatch):  # noqa: F811
    monkeypatch.setattr(mailbox, 'V2_WAIT_MAX', 1.0)
    monkeypatch.setattr(relay_link, 'PUSH_EVERY', 0.2)
    dev_file = tmp_path / 'mailbox.json'
    dev_file.write_text(json.dumps({'devices': {
        'palm-pc': {'role': 'pc', 'sha256': hashlib.sha256(TOK['palm-pc'].encode()).hexdigest()},
        'phone': {'role': 'client', 'sha256': hashlib.sha256(TOK['phone'].encode()).hexdigest()}}}))
    srv, mb = mailbox.make_server('127.0.0.1', 0, dev_file, tmp_path / 'blobs')
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{srv.server_address[1]}'
    lk = relay_link.RelayLink(env['hub'], base, TOK['palm-pc'], KEY, state_dir=tmp_path, inbox_dir=tmp_path / 'inbox')
    lk.run()
    phone = Phone(base)
    yield dict(env, lk=lk, phone=phone, mb=mb, tmp=tmp_path)
    lk.stop.set()
    srv.shutdown()


def test_sessions_and_events_roundtrip(link):
    ph = link['phone']
    cid = ph.cmd('sessions')
    r = ph.ack(cid)
    assert r['ok'] and [s['sid'] for s in r['sessions']] == ['sid-host', 'sid-live']
    cid = ph.cmd('events', sid='sid-host', tail=50)
    r = ph.wait(lambda k, m: k == 'L4' and m.get('ref') == cid)
    assert [e['k'] for e in r['events']] == ['user', 'image', 'text'] and r['parts'] == 1


def test_inject_through_relay(link):
    ph = link['phone']
    r = ph.ack(ph.cmd('inject', sid='sid-host', text='经中转来的话'))
    assert r['ok']
    link['term'].wait_for(b'GOT 0d')
    assert '经中转来的话'.encode() in link['term'].got()


def test_push_overview_and_watched_events_when_online(link):
    ph = link['phone']
    ph.poll(1)                                                     # 手机在线（取过件）
    ov = ph.wait(lambda k, m: k == 'L1')
    assert {s['sid'] for s in ov['sessions']} == {'sid-host', 'sid-live'}
    r = ph.ack(ph.cmd('watch', sid='sid-host'))
    assert r['ok']
    tfile = link['cfg_main'] / 'projects' / '-w-x' / 'sid-host.jsonl'
    with open(tfile, 'a', encoding='utf-8') as f:
        f.write(json.dumps({'type': 'assistant', 'uuid': 'new', 'message': {'content': [{'type': 'text', 'text': '新回复'}]}}) + '\n')
    pushed = ph.wait(lambda k, m: k == 'L4' and m.get('push') and m['sid'] == 'sid-host')
    assert [e['text'] for e in pushed['events']] == ['新回复']


def test_image_download(link):
    ph = link['phone']
    cid = ph.cmd('events', sid='sid-host')
    ev = ph.wait(lambda k, m: k == 'L4' and m.get('ref') == cid)
    img = next(e for e in ev['events'] if e['k'] == 'image')
    meta = ph.ack(ph.cmd('image', id=img['img']))
    ct = b''.join(ph.http('GET', f"/v2/blob/{meta['blob']}?part={i}") for i in range(meta['parts']))
    data = ph.down.decrypt(base64.b64decode(meta['nonce']), ct, meta['blob'].encode())
    assert data == base64.b64decode(PNG) and meta['mime'] == 'image/png'


def test_send_files_saves_and_injects_paths(link):
    ph = link['phone']
    photo = b'\xff\xd8\xff' + b'p' * 200000                        # 约 200 KB，多片
    meta = ph.upload(photo, '../../照片 1.jpg', 'image/jpeg')
    r = ph.ack(ph.cmd('send_files', sid='sid-host', text='看看这张', files=[meta]), timeout=10)
    assert r['ok'] and len(r['paths']) == 1
    p = Path(r['paths'][0])
    assert p.parent == link['tmp'] / 'inbox' and p.read_bytes() == photo and '..' not in p.name
    assert not list((link['tmp'] / 'blobs').iterdir())             # 取走即删
    link['term'].wait_for(b'GOT 0d')
    got = link['term'].got().decode()
    assert '看看这张' in got and str(p) in got


def test_replay_and_tamper_are_dropped_and_link_survives(link):
    ph = link['phone']
    ph.ack(ph.cmd('inject', sid='sid-host', text='只一次'))
    link['term'].wait_for(b'GOT 0d')
    ph.send_env(ph.last_env)                                       # 原样重放
    bad = dict(ph.sealer.seal('L5', {'id': 'x', 'op': 'inject', 'args': {'sid': 'sid-host', 'text': '篡改'}}))
    bad['kind'] = 'L4'
    ph.send_env(bad)
    time.sleep(1.5)
    link['term'].pump(0.5)
    assert link['term'].got().count('只一次'.encode()) == 1 and '篡改'.encode() not in link['term'].got()
    assert link['lk'].stats['bad'] >= 2
    assert ph.ack(ph.cmd('pending'))['pending'][0]['id'] == 'p1'  # 链路照常


def test_no_push_when_phone_offline(link):
    time.sleep(1.0)                                                # 手机从没取过件
    assert link['lk'].push_once() is False
    assert link['mb'].q.get('phone', []) == []


def test_unknown_op_and_bad_args(link):
    ph = link['phone']
    assert ph.ack(ph.cmd('rm_rf'))['reason'].startswith('unknown_op')
    assert ph.ack(ph.cmd('events', sid='sid-host', since='abc'))['reason'] == 'bad_cursor'
    assert ph.ack(ph.cmd('send_files', sid='sid-host', files=[]))['reason'] == 'bad_files'


def test_safe_name():
    assert relay_link.safe_name('../../etc/passwd') == 'passwd'
    assert relay_link.safe_name('我的 照片(1).jpg') == '我的_照片_1_.jpg'
    assert relay_link.safe_name('') == 'file'


def test_large_session_list_fits_relay_envelope_limit(link):
    """真机实测：95 条会话约 49.6 KB，按 48 KB 明文切片加密 + Base64 后超中转 64 KB 上限被拒，手机一直空等。"""
    rows = [{'session_id': f'00000000-0000-0000-0000-{i:012d}', 'title': '历史会话标题' * 20, 'cwd': '/w/' + 'd' * 80,
             'is_live': False, 'config_dir': str(link['cfg_main']), 'age_seconds': i} for i in range(150)]
    link['fb'].state['sessions']['claude'] += rows
    link['hub']._bridge_cache = (0.0, None)
    ph = link['phone']
    r = ph.ack(ph.cmd('sessions', scope='all'), timeout=15)
    assert r['ok'] and len(r['sessions']) >= 150 and r['parts'] >= 2


def test_commands_and_files_through_relay(link):
    """斜杠命令面板与 @ 文件补全：经中转取回；命令清单按 items 分片，会话不存在回错。"""
    ph = link['phone']
    r = ph.ack(ph.cmd('commands', sid='sid-host'))
    names = [c['name'] for c in r['items']]
    assert r['ok'] and r['parts'] >= 1 and '/compact' in names and '/model' in names
    cwd = link['tmp'] / 'proj'
    cwd.mkdir()
    (cwd / 'palm_probe_file.txt').write_text('x')
    next(s for s in link['fb'].state['sessions']['claude'] if s['session_id'] == 'sid-host')['cwd'] = str(cwd)
    link['hub']._bridge_cache = (0.0, None)
    r = ph.ack(ph.cmd('files', sid='sid-host', q='palm_probe'))
    assert r['ok'] and r['items'] == ['palm_probe_file.txt']
    r = ph.ack(ph.cmd('files', sid='nope', q='a'))
    assert not r['ok'] and r['reason'] == 'no_session'


def test_link_status_for_doctor(link):
    """palm doctor 靠 /api/link 判断中枢是否在取件：取过件后 poll_age 是小数字；没配对时 paired=false。"""
    ph = link['phone']
    ph.ack(ph.cmd('pending'))
    st = link['lk'].status()
    assert st['poll_age'] is not None and st['poll_age'] < 5 and st['in'] >= 1 and st['peer'] == 'phone'
    _, r = call(link['base'], '/api/link', link['tok'])
    assert r == {'ok': True, 'paired': False}                     # 测试里的中枢没挂 link
    link['hub'].link = link['lk']
    _, r = call(link['base'], '/api/link', link['tok'])
    assert r['paired'] and r['poll_age'] < 5


def test_stats_through_relay_fits_one_envelope(link):
    ph = link['phone']
    r = ph.ack(ph.cmd('stats', days=30))
    assert r['ok'] and r['ready'] and len(r['days']) == 30
    assert len(json.dumps(r, ensure_ascii=False).encode()) < relay_link.ENV_BUDGET
