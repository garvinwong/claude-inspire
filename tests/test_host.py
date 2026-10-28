"""会话宿主测试：透传、注入、仲裁、按键、旁听、退出码、失败放行、窗口缩放、启动包装。
全部用临时 PALM_HOME_DIR 与假会话，绝不碰真实 ~/.palm 与真 claude。"""
import fcntl
import json
import os
import re
import select
import signal
import socket
import struct
import subprocess
import sys
import termios
import time
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
HOST = ROOT / 'host' / 'palm_host.py'
PALM_SH = ROOT / 'host' / 'palm.sh'
FAKE = Path(__file__).resolve().parent / 'fake_child.py'
sys.path.insert(0, str(ROOT / 'host'))
import palm_host  # noqa: E402

PS, PE = b'\x1b[200~', b'\x1b[201~'


class Term:
    """一块伪终端当“本机终端”，宿主跑在里面。"""

    def __init__(self, home, env=None, rows=30, cols=100, cmd=None):
        self.m, s = os.openpty()
        fcntl.ioctl(s, termios.TIOCSWINSZ, struct.pack('HHHH', rows, cols, 0, 0))
        e = dict(os.environ, PALM_HOME_DIR=str(home))
        e.pop('PALM_HOST_ID', None)
        e.update(env or {})
        cmd = cmd or [sys.executable, str(HOST), '--', sys.executable, str(FAKE)]
        self.p = subprocess.Popen(cmd, stdin=s, stdout=s, stderr=s, env=e, start_new_session=True)
        os.close(s)
        self.out = b''

    def pump(self, timeout=0.2):
        end = time.time() + timeout
        while time.time() < end:
            r, _, _ = select.select([self.m], [], [], max(0, end - time.time()))
            if not r:
                break
            try:
                d = os.read(self.m, 65536)
            except OSError:
                break
            if not d:
                break
            self.out += d

    def wait_for(self, pat: bytes, timeout=5.0):
        end = time.time() + timeout
        while pat not in self.out and time.time() < end:
            self.pump(0.1)
        assert pat in self.out, f'等不到 {pat!r}，已收到：{self.out[-500:]!r}'

    def write(self, b: bytes):
        os.write(self.m, b)

    def got(self) -> bytes:
        return b''.join(bytes.fromhex(h.decode()) for h in re.findall(rb'GOT ([0-9a-f]+)\r\n', self.out))

    def close(self):
        if self.p.poll() is None:
            self.p.kill()
            self.p.wait(timeout=5)
        try:
            os.close(self.m)
        except OSError:
            pass


@pytest.fixture
def home(tmp_path):
    return tmp_path / 'palm'


@pytest.fixture
def term(home):
    t = Term(home)
    t.wait_for(b'READY')
    yield t
    t.close()


def registry(home, timeout=3.0):
    end = time.time() + timeout
    while time.time() < end:
        files = list((home / 'run').glob('*.json'))
        if files:
            return json.loads(files[0].read_text(encoding='utf-8'))
        time.sleep(0.05)
    raise AssertionError('没有登记文件')


def ctl(home, req):
    reg = registry(home)
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(3)
    s.connect(reg['sock'])
    s.sendall(json.dumps(req).encode() + b'\n')
    buf = b''
    while b'\n' not in buf:
        buf += s.recv(65536)
    s.close()
    return json.loads(buf.split(b'\n', 1)[0])


# ── 透传与登记 ─────────────────────────────────────────────

def test_passthrough_registry_and_env(term, home):
    reg = registry(home)
    assert f"READY id={reg['id']}".encode() in term.out          # 子进程带 PALM_HOST_ID
    assert reg['config_dir'] and reg['child_pid'] > 0
    assert oct(os.stat(reg['sock']).st_mode & 0o777) == '0o600'
    assert oct(os.stat(home / 'run').st_mode & 0o777) == '0o700'
    term.write(b'ab')
    term.wait_for(b'GOT 6162')


def test_initial_winsize_and_resize(term, home):
    assert b'size=30x100' in term.out
    fcntl.ioctl(term.m, termios.TIOCSWINSZ, struct.pack('HHHH', 40, 120, 0, 0))
    term.p.send_signal(signal.SIGWINCH)
    term.wait_for(b'SIZE 40x120')


# ── 注入与仲裁 ─────────────────────────────────────────────

def test_inject_is_bracketed_paste_then_enter(term, home):
    assert ctl(home, {'op': 'inject', 'text': '你好\nworld'})['ok']
    term.wait_for(b'GOT 0d')
    got = term.got()
    assert got == PS + '你好\nworld'.encode() + PE + b'\r'


def test_inject_without_submit_has_no_enter(term, home):
    assert ctl(home, {'op': 'inject', 'text': 'x', 'submit': False})['ok']
    term.wait_for(b'GOT ')
    term.pump(0.3)
    assert term.got() == PS + b'x' + PE


def test_inject_strips_escape_so_paste_cannot_end_early(term, home):
    assert ctl(home, {'op': 'inject', 'text': 'a\x1b[201~b', 'submit': False})['ok']
    term.wait_for(b'GOT ')
    term.pump(0.3)
    assert term.got() == PS + b'a[201~b' + PE


def test_local_typing_blocks_inject_until_force(term, home):
    term.write(b'xy')
    term.wait_for(b'GOT 7879')
    r = ctl(home, {'op': 'inject', 'text': 'hi'})
    assert {k: r[k] for k in ('ok', 'reason', 'draft_chars')} == {'ok': False, 'reason': 'local_typing', 'draft_chars': 2}
    assert 0 <= r['idle_s'] < 5
    assert ctl(home, {'op': 'inject', 'text': 'hi', 'force': True})['ok']
    assert ctl(home, {'op': 'status'})['local']['draft_chars'] == 0


def test_local_enter_clears_draft(term, home):
    term.write(b'abc\r')
    term.wait_for(b'GOT 6162630d')
    assert ctl(home, {'op': 'inject', 'text': 'hi'})['ok']


def test_stale_draft_reports_local_draft(home):
    t = Term(home, env={'PALM_TYPING_WINDOW': '0.3'})
    try:
        t.wait_for(b'READY')
        t.write(b'z')
        t.wait_for(b'GOT 7a')
        time.sleep(0.5)
        r = ctl(home, {'op': 'inject', 'text': 'hi'})
        assert r['reason'] == 'local_draft' and r['draft_chars'] == 1
    finally:
        t.close()


# 实际遇到过：出门前在电脑输入框留了字，手机发的消息一直“已排队”，
# 手机终端页按退格也清不掉（远程按键不计入草稿计数），会话从手机上进不去。
def test_remote_keys_count_toward_draft(term, home):
    term.write(b'abc')
    term.wait_for(b'GOT 616263')
    assert ctl(home, {'op': 'keys', 'keys': ['backspace', 'backspace']})['ok']
    assert ctl(home, {'op': 'status'})['local']['draft_chars'] == 1          # 手机上删了两个字
    assert ctl(home, {'op': 'keys', 'keys': ['ctrl_u']})['ok']
    assert ctl(home, {'op': 'status'})['local']['draft_chars'] == 0          # 手机上清了行
    assert ctl(home, {'op': 'inject', 'text': 'hi'})['ok']


def test_remote_keys_do_not_count_as_local_typing(term, home):
    assert ctl(home, {'op': 'keys', 'keys': ['1']})['ok']
    st = ctl(home, {'op': 'status'})['local']
    assert st['last_key_age'] is None                                    # 手机按键不算“人在电脑前”


def test_inject_clear_wipes_pc_draft_then_sends(term, home):
    term.write(b'draft')
    term.wait_for(b'GOT 6472616674')
    r = ctl(home, {'op': 'inject', 'text': 'hi', 'clear': True})
    assert r['ok'] and r['cleared'] == 5
    term.wait_for(b'GOT 0d')
    term.pump(0.3)
    got = term.got()
    head = got[:got.index(PS)]
    assert head == b'draft' + b'\x15' * 8 + b'\x0b' * 8                  # 草稿 5 字：各按 8 下（多按无副作用，真机实测）
    assert got.endswith(PS + b'hi' + PE + b'\r')                          # 先清（Ctrl+U 往前删、Ctrl+K 往后删），再粘贴发出
    assert ctl(home, {'op': 'status'})['local']['draft_chars'] == 0


def test_draft_refusal_reports_how_long_pc_has_been_idle(home):
    t = Term(home, env={'PALM_TYPING_WINDOW': '0.3'})
    try:
        t.wait_for(b'READY')
        t.write(b'z')
        t.wait_for(b'GOT 7a')
        time.sleep(0.5)
        r = ctl(home, {'op': 'inject', 'text': 'hi'})
        assert r['reason'] == 'local_draft' and r['idle_s'] >= 0.4            # 手机据此推荐“清空后发送”
    finally:
        t.close()


def test_bad_inject_text(term, home):
    assert ctl(home, {'op': 'inject', 'text': ''})['reason'] == 'bad_text'
    assert ctl(home, {'op': 'inject', 'text': 'x' * (70 * 1024)})['reason'] == 'bad_text'


# ── 按键、重绘、旁听 ───────────────────────────────────────

def test_named_keys(term, home):
    assert ctl(home, {'op': 'keys', 'keys': ['esc', 'shift_tab', 'down', 'enter', '1']})['ok']
    term.wait_for(b'GOT ')
    term.pump(0.3)
    assert term.got() == b'\x1b' + b'\x1b[Z' + b'\x1b[B' + b'\r' + b'1'
    assert ctl(home, {'op': 'keys', 'keys': ['rm -rf']})['ok'] is False


def test_redraw_nudges_size_and_restores(term, home):
    assert ctl(home, {'op': 'redraw'})['ok']
    term.wait_for(b'SIZE 29x100')
    term.wait_for(b'SIZE 30x100')


def test_subscribe_gets_replay_then_live_output(term, home):
    reg = registry(home)
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(3)
    s.connect(reg['sock'])
    s.sendall(b'{"op":"subscribe"}\n')
    f = s.makefile('rb')
    first = json.loads(f.readline())
    import base64
    assert first['ev'] == 'replay' and b'READY' in base64.b64decode(first['b64'])
    term.write(b'q')
    seen = b''
    end = time.time() + 3
    while b'GOT 71' not in seen and time.time() < end:
        ev = json.loads(f.readline())
        if ev['ev'] == 'out':
            seen += base64.b64decode(ev['b64'])
    assert b'GOT 71' in seen
    s.close()


def test_unknown_op_and_garbage_do_not_kill_session(term, home):
    reg = registry(home)
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(3)
    s.connect(reg['sock'])
    s.sendall(b'not json\n{"op":"nope"}\n')
    f = s.makefile('rb')
    assert json.loads(f.readline())['ok'] is False
    assert json.loads(f.readline())['reason'].startswith('unknown_op')
    s.close()
    term.write(b'k')
    term.wait_for(b'GOT 6b')


# ── 退出与失败放行 ─────────────────────────────────────────

def test_exit_code_propagates_and_cleans_up(term, home):
    reg = registry(home)
    term.write(b'QUIT')
    assert term.p.wait(timeout=5) == 7
    assert not Path(reg['sock']).exists()
    assert not list((home / 'run').glob('*.json'))


def test_fail_open_when_home_unwritable(tmp_path):
    blocker = tmp_path / 'file-not-dir'
    blocker.write_text('x')
    t = Term(blocker)                     # PALM_HOME_DIR 指向文件：建不了套接字目录
    try:
        t.wait_for(b'READY')
        t.write(b'ok')
        t.wait_for(b'GOT 6f6b')
        t.write(b'QUIT')
        assert t.p.wait(timeout=5) == 7
    finally:
        t.close()


def test_deep_home_falls_back_to_short_socket_path(tmp_path):
    deep = tmp_path / ('d' * 60) / ('e' * 60)
    t = Term(deep)
    try:
        t.wait_for(b'READY')
        reg = registry(deep)
        assert reg['sock'].startswith(f'/tmp/palm-{os.getuid()}/')
        assert ctl(deep, {'op': 'status'})['ok']
        t.write(b'QUIT')
        assert t.p.wait(timeout=5) == 7
        assert not Path(reg['sock']).exists()
    finally:
        t.close()


def test_missing_command_exits_127(home):
    t = Term(home, cmd=[sys.executable, str(HOST), '--', 'palm-no-such-command-xyz'])
    try:
        assert t.p.wait(timeout=5) == 127
    finally:
        t.close()


# ── 本机输入估计 ───────────────────────────────────────────

def make_input():
    clock = [100.0]
    li = palm_host.LocalInput(clock=lambda: clock[0])
    return li, clock


def test_local_input_counts_chars_not_escape_sequences():
    li, _ = make_input()
    li.feed('ab你'.encode())
    li.feed(b'\x1b[A\x1b[B\x1bOC')       # 方向键（含应用模式）不计
    assert li.count == 3
    li.feed(b'\x7f')
    assert li.count == 2
    li.feed(b'\x15')                      # Ctrl+U 清空
    assert li.count == 0


def test_local_input_paste_newlines_do_not_submit():
    li, _ = make_input()
    li.feed(PS + b'a\rb' + PE)
    assert li.count == 3
    li.feed(b'\r')
    assert li.count == 0


def test_lone_esc_does_not_swallow_next_char():
    li, _ = make_input()
    li.feed(b'\x1b')
    li.feed(b'h')
    assert li.count == 1


def test_typing_window():
    li, clock = make_input()
    li.feed(b'x')
    assert li.state()['typing'] is True
    clock[0] += palm_host.TYPING_WINDOW + 1
    st = li.state()
    assert st['typing'] is False and st['draft_chars'] == 1


# ── 启动包装 palm.sh ───────────────────────────────────────

def run_bash_in_pty(script, home, extra_env=None, path_prefix=None):
    """在伪终端里跑一段 bash（-t 0 / -t 1 为真），返回输出文本。"""
    env = {'PALM_HOME_DIR': str(home)}
    if path_prefix:
        env['PATH'] = f"{path_prefix}:{os.environ['PATH']}"
    env.update(extra_env or {})
    t = Term(home, env=env, cmd=['bash', '--norc', '--noprofile', '-c', script])
    try:
        t.p.wait(timeout=10)
        t.pump(0.3)
        return t.out.decode(errors='replace')
    finally:
        t.close()


@pytest.mark.parametrize('args,expect', [
    ('', 0), ('"修一下登录"', 0), ('--resume abc', 0), ('-c', 0),
    ('-p hi', 1), ('--print hi', 1), ('--version', 1), ('mcp list', 1), ('doctor', 1), ('--bg', 1),
    ('remote-control', 1), ('attach x', 1),
])
def test_should_wrap_rules(home, args, expect):
    out = run_bash_in_pty(f'source {PALM_SH}; _palm_should_wrap {args}; echo "RC=$?"', home)
    assert f'RC={expect}' in out


def test_wrapper_runs_claude_inside_host_and_bypass(home, tmp_path):
    fakebin = tmp_path / 'bin'
    fakebin.mkdir()
    fake_claude = fakebin / 'claude'
    fake_claude.write_text('#!/bin/sh\necho "HOSTID=[$PALM_HOST_ID] CFG=[$CLAUDE_CONFIG_DIR] ARGS=[$*]"\n')
    fake_claude.chmod(0o755)
    script = (f'unset CLAUDE_CONFIG_DIR; source {PALM_SH}; alias claude-work="CLAUDE_CONFIG_DIR=/tmp/cfg-work claude"; '
              'claude x; PALM_HOST=0 claude y; claude -p z; '
              'shopt -s expand_aliases; eval "claude-work w"')
    out = run_bash_in_pty(script, home, path_prefix=str(fakebin))
    lines = [ln for ln in out.splitlines() if 'HOSTID=' in ln]
    assert len(lines) == 4, out
    assert re.search(r'HOSTID=\[[0-9a-f]{12}\] CFG=\[\] ARGS=\[x\]', lines[0])
    assert 'HOSTID=[] CFG=[] ARGS=[y]' in lines[1]                       # 旁路
    assert 'HOSTID=[] CFG=[] ARGS=[-p z]' in lines[2]                    # -p 直通
    assert re.search(r'HOSTID=\[[0-9a-f]{12}\] CFG=\[/tmp/cfg-work\] ARGS=\[w\]', lines[3])   # 别名 + 环境变量照传
