"""配对测试：本机生成设备令牌与密钥、写局域网信箱设备表；假 adb，不碰手机；令牌与密钥不得明文打印。"""
import base64
import hashlib
import json
import os
import stat
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
PAIR = ROOT / 'hub' / 'pair.py'


@pytest.fixture
def env(tmp_path):
    adb = tmp_path / 'adb'
    adb.write_text(f'#!/usr/bin/env bash\necho "$@" > {tmp_path}/adb_args\n')
    adb.chmod(adb.stat().st_mode | stat.S_IEXEC)
    e = dict(os.environ, PALM_HOME_DIR=str(tmp_path / 'palm'), PALM_ADB=str(adb))
    e.pop('PALM_LAN_PORT', None)
    return {'env': e, 'tmp': tmp_path, 'home': tmp_path / 'palm'}


def run(env, *args):
    p = subprocess.run([sys.executable, str(PAIR), *args], env=env['env'], capture_output=True, text=True, timeout=60)
    assert p.returncode == 0, p.stderr
    return p


def sha(t):
    return hashlib.sha256(t.encode()).hexdigest()


def phone_payload(env):
    args = (env['tmp'] / 'adb_args').read_text()
    raw = args.split("claudeinspire://pair#", 1)[1].split("'", 1)[0]
    return json.loads(base64.urlsafe_b64decode(raw + '=' * (-len(raw) % 4))), args


def test_pair_writes_mailbox_devices_link_and_pushes_phone(env):
    p = run(env, '--host', '100.101.102.103', '--adb', 'SER123')
    home = env['home']
    for f in ('link.json', 'mailbox.json', 'pair-tokens.json'):
        assert oct((home / f).stat().st_mode & 0o777) == '0o600'
    link = json.loads((home / 'link.json').read_text())
    devs = json.loads((home / 'mailbox.json').read_text())['devices']
    assert link['relay_url'] == 'http://127.0.0.1:5613' and link['me'] == 'palm-pc' and link['peer'] == 'phone'
    assert devs['palm-pc'] == {'role': 'pc', 'sha256': sha(link['token'])}
    assert len(base64.b64decode(link['pair_key_b64'])) == 32
    phone, args = phone_payload(env)
    assert 'am start -a android.intent.action.VIEW' in args and 'io.github.garvinwong.claudeinspire' in args and 'SER123' in args
    assert phone['relay'] == 'http://100.101.102.103:5613' and phone['me'] == 'phone' and phone['peer'] == 'palm-pc'
    assert devs['phone'] == {'role': 'client', 'sha256': sha(phone['token'])} and phone['key'] == link['pair_key_b64']
    for secret in (link['token'], link['pair_key_b64'], phone['token']):
        assert secret not in p.stdout + p.stderr                                 # 不明文打印


def test_pair_again_keeps_tokens_rotate_changes_all(env):
    run(env, '--host', '10.0.0.5')
    first = json.loads((env['home'] / 'link.json').read_text())
    run(env, '--host', '10.0.0.5')
    assert json.loads((env['home'] / 'link.json').read_text()) == first
    run(env, '--host', '10.0.0.5', '--rotate')
    again = json.loads((env['home'] / 'link.json').read_text())
    assert again['pair_key_b64'] != first['pair_key_b64'] and again['token'] != first['token']


def test_candidate_hosts_prefers_tailscale(monkeypatch):
    sys.path.insert(0, str(ROOT / 'hub'))
    import pair

    class R:
        stdout = '172.20.1.2 127.0.0.1 100.80.1.2 8.8.8.8 fe80::1'
    monkeypatch.setattr(pair.subprocess, 'run', lambda *a, **k: R())
    assert pair.candidate_hosts() == ['100.80.1.2', '172.20.1.2']             # 公网 / 回环 / IPv6 不要


def test_qr_contains_deep_link_payload(env, monkeypatch):
    sys.path.insert(0, str(ROOT / 'hub'))
    import pair
    captured = {}

    class FakeQR:
        def __init__(self, **kw):
            pass

        def add_data(self, d):
            captured['url'] = d

        def print_ascii(self, invert=False):
            print('[二维码]')

    qrcode = pytest.importorskip('qrcode')
    monkeypatch.setattr(qrcode, 'QRCode', FakeQR)
    for k, v in env['env'].items():
        monkeypatch.setenv(k, v)
    assert pair.main(['--qr', '--host', '192.168.1.20']) == 0
    url = captured['url']
    assert url.startswith('claudeinspire://pair#')
    raw = url.split('#', 1)[1]
    payload = json.loads(base64.urlsafe_b64decode(raw + '=' * (-len(raw) % 4)))
    assert payload['me'] == 'phone' and payload['relay'] == 'http://192.168.1.20:5613' and len(base64.b64decode(payload['key'])) == 32
