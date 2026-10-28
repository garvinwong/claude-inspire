"""后台宿主与电脑接入测试：后台模式、接入端输入、窗口大小归属、关终端语义、palm attach。"""
import base64
import json
import os
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path

import pytest

from test_host import FAKE, HOST, Term, ctl, registry

ATTACH = Path(__file__).resolve().parents[1] / 'host' / 'palm_attach.py'


class Sub:
    """旁听者：收宿主推来的输出。"""

    def __init__(self, home):
        self.s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.s.settimeout(5)
        self.s.connect(registry(home)['sock'])
        self.s.sendall(b'{"op":"subscribe"}\n')
        self.f = self.s.makefile('rb')
        self.out = b''

    def wait_for(self, pat: bytes, timeout=5):
        end = time.time() + timeout
        while pat not in self.out and time.time() < end:
            ev = json.loads(self.f.readline())
            if ev.get('ev') in ('out', 'replay'):
                self.out += base64.b64decode(ev['b64'])
        assert pat in self.out, self.out[-300:]

    def close(self):
        self.s.close()


def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


@pytest.fixture
def bg(tmp_path):
    home = tmp_path / 'palm'
    env = dict(os.environ, PALM_HOME_DIR=str(home))
    env.pop('PALM_HOST_ID', None)
    p = subprocess.Popen([sys.executable, str(HOST), '--detached', '--id', 'abcdef012345', '--size', '24x80',
                          '--', sys.executable, str(FAKE)],
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         env=env, start_new_session=True)
    reg = registry(home)
    yield home, p, reg
    if p.poll() is None:
        p.kill()
        p.wait(timeout=5)


def test_detached_host_registry_size_and_inject(bg):
    home, p, reg = bg
    assert reg['id'] == 'abcdef012345' and reg['detached'] and reg['keep_alive']
    st = ctl(home, {'op': 'status'})
    assert st['attached'] is False and st['rows'] == 24 and st['cols'] == 80
    sub = Sub(home)
    sub.wait_for(b'size=24x80')
    assert ctl(home, {'op': 'inject', 'text': 'hi'})['ok']
    sub.wait_for(b'GOT ' + (b'\x1b[200~hi\x1b[201~').hex().encode())
    sub.close()


def test_resize_belongs_to_whoever_has_no_local_terminal(bg, tmp_path):
    home, p, reg = bg
    sub = Sub(home)
    sub.wait_for(b'READY')                          # 假会话装好信号处理后再改尺寸（否则 SIGWINCH 被忽略）
    assert ctl(home, {'op': 'resize', 'rows': 30, 'cols': 90})['ok']
    sub.wait_for(b'SIZE 30x90')
    assert ctl(home, {'op': 'resize', 'rows': 1, 'cols': 90})['reason'] == 'bad_size'
    sub.close()
    h2 = tmp_path / 'palm2'
    t = Term(h2)
    try:
        t.wait_for(b'READY')
        assert ctl(h2, {'op': 'resize', 'rows': 30, 'cols': 90})['reason'] == 'local_terminal_owns_size'
    finally:
        t.close()


def test_attach_input_counts_as_local_typing(bg):
    home, p, reg = bg
    assert ctl(home, {'op': 'input', 'b64': base64.b64encode(b'ab').decode()})['ok']
    assert ctl(home, {'op': 'status'})['local']['draft_chars'] == 2
    assert ctl(home, {'op': 'inject', 'text': 'x'})['reason'] == 'local_typing'
    assert ctl(home, {'op': 'input', 'b64': '!!!'})['reason'] == 'bad_b64'


def test_terminal_close_ends_session_by_default(tmp_path):
    home = tmp_path / 'palm'
    t = Term(home)
    try:
        t.wait_for(b'READY')
        child = registry(home)['child_pid']
        os.close(t.m)                               # 终端没了
        t.p.wait(timeout=5)                         # 默认：与今天一致，会话结束
        time.sleep(0.2)
        assert not alive(child)
    finally:                                        # 失败时也收掉宿主（10-26 反证时漏过一个后台宿主）
        if t.p.poll() is None:
            t.p.kill()


def test_keep_alive_survives_terminal_close_then_usable(tmp_path):
    home = tmp_path / 'palm'
    t = Term(home)
    t.wait_for(b'READY')
    assert ctl(home, {'op': 'keep_alive', 'on': True})['keep_alive'] is True
    os.close(t.m)
    time.sleep(0.5)
    try:
        assert t.p.poll() is None
        reg = registry(home)
        assert reg['detached'] and alive(reg['child_pid'])
        sub = Sub(home)
        assert ctl(home, {'op': 'inject', 'text': 'QUIT', 'submit': False})['ok']
        assert t.p.wait(timeout=5) == 7
        sub.close()
    finally:
        if t.p.poll() is None:
            t.p.kill()


def test_sighup_with_keep_alive_goes_background(tmp_path):
    home = tmp_path / 'palm'
    t = Term(home)
    try:
        t.wait_for(b'READY')
        ctl(home, {'op': 'keep_alive', 'on': True})
        t.p.send_signal(signal.SIGHUP)
        time.sleep(0.5)
        reg = registry(home)
        assert t.p.poll() is None and reg['detached'] and alive(reg['child_pid'])
    finally:
        t.close()


def test_sighup_without_keep_alive_ends_session(tmp_path):
    home = tmp_path / 'palm'
    t = Term(home)
    try:
        t.wait_for(b'READY')
        t.p.send_signal(signal.SIGHUP)
        assert t.p.wait(timeout=5) != 0
    finally:
        t.close()


def test_palm_attach_end_to_end(bg):
    home, p, reg = bg
    a = Term(home, cmd=[sys.executable, str(ATTACH), 'abcdef'])
    try:
        time.sleep(0.5)
        a.write(b'hi')
        a.wait_for(b'GOT 6869')                     # 接入端打字 → 会话收到 → 屏幕回到接入端
        a.write(b'\x1c')                            # Ctrl+\ 断开
        assert a.p.wait(timeout=5) == 0
        a.pump(0.3)
        assert '会话仍在后台'.encode() in a.out
        assert ctl(home, {'op': 'status'})['ok']   # 会话还在
    finally:
        a.close()


def test_palm_attach_sees_session_end(bg):
    home, p, reg = bg
    a = Term(home, cmd=[sys.executable, str(ATTACH), 'abcdef012345'])
    try:
        time.sleep(0.5)
        a.write(b'QUIT')
        assert a.p.wait(timeout=5) == 0
        a.pump(0.3)
        assert '会话已结束（退出码 7）'.encode() in a.out
    finally:
        a.close()


def test_palm_attach_unknown_host(tmp_path):
    t = Term(tmp_path / 'none', cmd=[sys.executable, str(ATTACH), 'ffff'])
    try:
        assert t.p.wait(timeout=5) == 1
    finally:
        t.close()
