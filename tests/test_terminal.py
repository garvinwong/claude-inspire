"""终端视图测试：整屏还原、颜色与反色、宽字符、按键后更新、窗口跟随、空闲断开、中枢接口。"""
import json
import os
import subprocess
import sys
import time
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'hub'))
import terminal  # noqa: E402
from test_host import HOST, ctl, registry  # noqa: E402

TUI = Path(__file__).resolve().parent / 'fake_tui.py'


@pytest.fixture
def tui(tmp_path):
    home = tmp_path / 'palm'
    env = dict(os.environ, PALM_HOME_DIR=str(home))
    env.pop('PALM_HOST_ID', None)
    p = subprocess.Popen([sys.executable, str(HOST), '--detached', '--size', '10x40', '--', sys.executable, str(TUI)],
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         env=env, start_new_session=True)
    reg = registry(home)
    time.sleep(0.4)                                   # 假界面装好窗口变化处理
    yield home, reg
    p.kill()
    p.wait(timeout=5)


def wait_text(view, text, timeout=3):
    end = time.time() + timeout
    while time.time() < end:
        snap = view.snapshot()
        if text in terminal.TermView.plain(snap):
            return snap
        time.sleep(0.1)
    raise AssertionError(terminal.TermView.plain(view.snapshot()))


def test_snapshot_restores_screen_with_attrs_and_wide_chars(tui):
    home, reg = tui
    v = terminal.TermView(reg)
    snap = wait_text(v, '❯ Opus')
    plain = terminal.TermView.plain(snap).splitlines()
    assert plain[0] == '红色标题 选择模型'                     # 宽字符第二格不留空洞
    assert plain[1:4] == ['❯ Opus', '  Sonnet', '  Haiku']
    first = snap['lines'][0][0]
    assert first[0] == '红色标题' and first[1] == 'red'
    sel = next(seg for seg in snap['lines'][1] if '❯' in seg[0])
    assert sel[4] is True                                      # 反色 = 选中项
    assert snap['rows'] == 10 and snap['cols'] == 40


def test_keys_update_screen(tui):
    home, reg = tui
    v = terminal.TermView(reg)
    wait_text(v, '❯ Opus')
    assert ctl(home, {'op': 'keys', 'keys': ['down']})['ok']
    snap = wait_text(v, '❯ Sonnet')
    assert '  Opus' in terminal.TermView.plain(snap)


def test_view_follows_host_size(tui):
    home, reg = tui
    v = terminal.TermView(reg)
    wait_text(v, '❯ Opus')
    assert ctl(home, {'op': 'resize', 'rows': 12, 'cols': 50})['ok']
    end = time.time() + 3
    while time.time() < end and v.snapshot()['cols'] != 50:
        time.sleep(0.1)
    snap = v.snapshot()
    assert (snap['rows'], snap['cols']) == (12, 50) and len(snap['lines']) == 12


def test_idle_view_stops_and_manager_rebuilds(tui):
    home, reg = tui
    views = terminal.TermViews(idle=0.5)
    v1 = views.get(reg)
    wait_text(v1, 'Opus')
    time.sleep(2.0)
    assert v1.alive is False
    v2 = views.get(reg)
    assert v2 is not v1
    wait_text(v2, 'Opus')


def test_view_marks_session_end(tui):
    home, reg = tui
    v = terminal.TermView(reg)
    wait_text(v, 'Opus')
    ctl(home, {'op': 'keys', 'keys': ['q']})
    end = time.time() + 3
    while time.time() < end and v.alive:
        time.sleep(0.1)
    assert v.alive is False


def test_hub_screen_endpoint(tmp_path):
    import palm_hub
    hub = palm_hub.Hub(home=tmp_path / 'palm', bridge='http://127.0.0.1:9', config_dirs=[str(tmp_path / 'c')])
    assert hub.screen('nope') == {'ok': False, 'reason': 'not_hosted'}
