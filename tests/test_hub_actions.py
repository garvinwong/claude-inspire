"""中枢动作测试：排队与取消、续接四重拦截、续接与分叉、新建会话校验、后台上限、保持后台。
拉起会话一律用假 claude（写下参数与环境后运行假会话），绝不调用真 claude。"""
import json
import os
import time
import uuid
from pathlib import Path

import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'hub'))
import palm_hub  # noqa: E402
from test_host import FAKE
from test_hub import call, env  # noqa: F401  （env 是夹具）


def refresh(e):
    e['hub']._bridge_cache = (0.0, None)


def add_row(e, **kw):
    row = {'session_id': str(uuid.uuid4()), 'title': '历史会话', 'cwd': str(e['cfg_main'].parent), 'is_live': False,
           'config_dir': str(e['cfg_main']), 'age_seconds': 100}
    row.update(kw)
    e['fb'].state['sessions']['claude'].append(row)
    refresh(e)
    return row['session_id']


@pytest.fixture
def fake_claude(tmp_path, monkeypatch):
    """PATH 里放一个假 claude：把 “CLAUDE_CONFIG_DIR|参数|目录” 写到文件，再变成假会话。"""
    bin_dir = tmp_path / 'fakebin'
    bin_dir.mkdir()
    out = tmp_path / 'claude_calls.txt'
    script = bin_dir / 'claude'
    script.write_text(f'#!/bin/sh\necho "${{CLAUDE_CONFIG_DIR:-<unset>}}|$*|$PWD" >> "{out}"\n'
                      f'exec python3 "{FAKE}"\n')
    script.chmod(0o755)
    monkeypatch.setenv('PATH', f"{bin_dir}:{os.environ['PATH']}")
    monkeypatch.setenv('HOME', str(tmp_path))                 # 让 tmp/.claude 成为默认配置目录，目录也在家目录下
    yield out
    for p in (tmp_path / 'palm' / 'run').glob('*.json'):      # 收掉测试拉起的后台宿主
        try:
            os.kill(json.loads(p.read_text())['host_pid'], 9)
        except (OSError, ValueError, KeyError):
            pass


def wait_calls(out, n=1, timeout=5):
    end = time.time() + timeout
    while time.time() < end:
        if out.exists() and len(out.read_text().splitlines()) >= n:
            return out.read_text().splitlines()
        time.sleep(0.1)
    raise AssertionError('假 claude 没被调用')


# ── 排队 ───────────────────────────────────────────────────

def test_queue_waits_for_local_draft_then_sends(env, monkeypatch):  # noqa: F811
    monkeypatch.setattr(palm_hub, 'QUEUE_RETRY', 0.2)
    env['term'].write(b'ab')
    env['term'].wait_for(b'GOT 6162')
    _, r = call(env['base'], '/api/inject', env['tok'], {'sid': 'sid-host', 'text': '排队的话', 'queue': True})
    assert r['queued'] and r['reason'] == 'local_typing'
    _, ob = call(env['base'], '/api/outbox', env['tok'])
    assert [m['state'] for m in ob['outbox']] == ['waiting']
    env['term'].write(b'\r')                                   # 电脑端把自己那句发出去
    end = time.time() + 5
    while time.time() < end and call(env['base'], '/api/outbox', env['tok'])[1]['outbox'][0]['state'] != 'sent':
        time.sleep(0.1)
    assert call(env['base'], '/api/outbox', env['tok'])[1]['outbox'][0]['state'] == 'sent'
    env['term'].wait_for('排队的话'.encode().hex().encode())


def test_queue_cancel_and_no_queue_without_flag(env, monkeypatch):  # noqa: F811
    monkeypatch.setattr(palm_hub, 'QUEUE_RETRY', 0.2)
    env['term'].write(b'x')
    env['term'].wait_for(b'GOT 78')
    _, r = call(env['base'], '/api/inject', env['tok'], {'sid': 'sid-host', 'text': 'y'})
    assert {k: r[k] for k in ('ok', 'reason', 'draft_chars')} == {'ok': False, 'reason': 'local_typing', 'draft_chars': 1}
    _, r = call(env['base'], '/api/inject', env['tok'], {'sid': 'sid-host', 'text': 'y', 'queue': True})
    assert call(env['base'], '/api/outbox/cancel', env['tok'], {'qid': r['qid']})[1]['ok']
    assert call(env['base'], '/api/outbox/cancel', env['tok'], {'qid': r['qid']})[1]['reason'] == 'not_waiting'
    env['term'].write(b'\r')
    time.sleep(0.6)
    assert b'\x1b[200~y' not in env['term'].got()              # 取消后没发出去


# ── 续接 ───────────────────────────────────────────────────

def test_resume_refusals(env, fake_claude, tmp_path):  # noqa: F811
    assert call(env['base'], '/api/resume', env['tok'], {'sid': 'x; rm -rf /'})[1]['reason'] == 'bad_sid'
    live = add_row(env, is_live=True)
    assert call(env['base'], '/api/resume', env['tok'], {'sid': live})[1]['reason'] == 'session_live'
    assert not fake_claude.exists()                                            # 以上都没拉起任何会话


def test_resume_spawns_background_host_main_account_unset(env, fake_claude, tmp_path):  # noqa: F811
    work = tmp_path / 'proj'
    work.mkdir()
    old = add_row(env, cwd=str(work))
    _, r = call(env['base'], '/api/resume', env['tok'], {'sid': old})
    assert r['ok'] and r['host_id']
    line = wait_calls(fake_claude)[0]
    assert line == f'<unset>|--resume {old}|{work}'                             # 默认目录 ~/.claude 不设 CLAUDE_CONFIG_DIR
    reg = json.loads((env['home'] / 'run' / f"{r['host_id']}.json").read_text())
    assert reg['detached'] and reg['keep_alive']


def test_fork_allowed_even_when_live(env, fake_claude):  # noqa: F811
    live = add_row(env, is_live=True)
    _, r = call(env['base'], '/api/resume', env['tok'], {'sid': live, 'fork': True})
    assert r['ok']
    assert wait_calls(fake_claude)[0].split('|')[1] == f'--resume {live} --fork-session'


# ── 新建 ───────────────────────────────────────────────────

def test_new_session_validation(env, fake_claude, tmp_path):  # noqa: F811
    work = tmp_path / 'w'
    work.mkdir()
    bad = [({'cwd': '/etc'}, 'bad_cwd'),
           ({'cwd': str(tmp_path / 'nope')}, 'bad_cwd'),
           ({'cwd': str(work), 'permission_mode': 'bypassPermissions'}, 'bad_permission_mode'),
           ({'cwd': str(work), 'model': 'haiku; rm -rf ~'}, 'bad_model'),
           ({'cwd': str(work), 'effort': 'ultra'}, 'bad_effort')]
    for body, reason in bad:
        assert call(env['base'], '/api/new', env['tok'], body)[1]['reason'] == reason, body
    assert not fake_claude.exists()


def test_new_session_with_options(env, fake_claude, tmp_path):  # noqa: F811
    work = tmp_path / 'w2'
    work.mkdir()
    _, r = call(env['base'], '/api/new', env['tok'], {'cwd': str(work), 'model': 'haiku',
                                                      'permission_mode': 'plan', 'prompt': '先看看目录'})
    assert r['ok']
    cfg, args, cwd = wait_calls(fake_claude)[0].split('|')
    assert cfg == '<unset>' and cwd == str(work)
    assert args == '--model haiku --permission-mode plan -- 先看看目录'


def test_new_session_with_model_and_effort(env, fake_claude, tmp_path):  # noqa: F811
    work = tmp_path / 'w4'
    work.mkdir()
    _, r = call(env['base'], '/api/new', env['tok'], {'cwd': str(work), 'model': 'fable', 'effort': 'xhigh'})
    assert r['ok']
    assert wait_calls(fake_claude)[0].split('|')[1] == '--model fable --effort xhigh'


def test_new_options_reports_defaults(env, tmp_path, monkeypatch):  # noqa: F811
    """手机新建页：“默认”要写明是什么（settings.json 的 model / effortLevel）；坏的 settings.json 当没设。"""
    (env['cfg_main'] / 'settings.json').write_text(json.dumps({'model': 'opus[1m]', 'effortLevel': 'high'}))
    monkeypatch.setenv('PALM_DEFAULT_DIR', str(tmp_path))
    _, r = call(env['base'], '/api/new_options', env['tok'])
    assert r['accounts'] == [{'account': 'main', 'model': 'opus[1m]', 'effort': 'high', 'fable': True}]
    (env['cfg_main'] / 'settings.json').write_text('坏的 json')
    assert call(env['base'], '/api/new_options', env['tok'])[1]['accounts'][0]['model'] == ''
    assert r['efforts'] == ['low', 'medium', 'high', 'xhigh', 'max'] and r['default_dir'] == str(tmp_path)


def test_background_cap(env, fake_claude, tmp_path, monkeypatch):  # noqa: F811
    monkeypatch.setattr(palm_hub, 'BG_MAX', 0)
    work = tmp_path / 'w3'
    work.mkdir()
    assert call(env['base'], '/api/new', env['tok'], {'cwd': str(work)})[1]['reason'] == 'too_many_background'


def test_keep_alive_endpoint_and_recent_dirs(env, tmp_path, monkeypatch):  # noqa: F811
    monkeypatch.setenv('HOME', str(tmp_path))                                   # 临时目录当家目录
    _, r = call(env['base'], '/api/keep_alive', env['tok'], {'sid': 'sid-host', 'on': True})
    assert r == {'ok': True, 'keep_alive': True}
    d = tmp_path / 'exists'
    d.mkdir()
    add_row(env, cwd=str(d), age_seconds=1)
    _, r = call(env['base'], '/api/recent_dirs', env['tok'])
    assert r['dirs'][0] == str(d) and '/w/x' not in r['dirs']                 # 不存在的目录不列
    add_row(env, cwd='/tmp', age_seconds=0)
    _, r = call(env['base'], '/api/recent_dirs', env['tok'])
    assert '/tmp' not in r['dirs']                                             # 家目录外的不列


def test_spawn_env_rules(tmp_path):
    base = {'PATH': '/bin', 'CLAUDECODE': '1', 'CLAUDE_CODE_ENTRYPOINT': 'cli', 'PALM_HOST_ID': 'x',
            'CLAUDE_CONFIG_DIR': '/old'}
    e = palm_hub.spawn_env(str(Path('~/.claude').expanduser()), tmp_path, base)
    assert 'CLAUDE_CONFIG_DIR' not in e and 'CLAUDECODE' not in e and 'PALM_HOST_ID' not in e
    assert 'CLAUDE_CODE_ENTRYPOINT' not in e and e['PALM_HOME_DIR'] == str(tmp_path)
    e = palm_hub.spawn_env('~/.claude-work', tmp_path, base)                  # 自定义配置目录：要设
    assert e['CLAUDE_CONFIG_DIR'] == str(Path('~/.claude-work').expanduser())


def test_spawn_env_sets_color_terminal(tmp_path):
    """10-26 实测：中枢跑在 systemd 下没有 TERM，后台会话的 Claude Code 整屏不输出颜色，
    手机终端视图里 Logo 成了白块、状态条全白。后台会话的伪终端由 pyte 还原，固定按 256 色 + 真彩色。"""
    e = palm_hub.spawn_env(str(Path('~/.claude').expanduser()), tmp_path, {'PATH': '/bin'})
    assert e['TERM'] == 'xterm-256color' and e['COLORTERM'] == 'truecolor'
    e = palm_hub.spawn_env(str(Path('~/.claude').expanduser()), tmp_path, {'PATH': '/bin', 'TERM': 'dumb', 'NO_COLOR': '1'})
    assert e['TERM'] == 'xterm-256color' and 'NO_COLOR' not in e


def test_history_row_without_config_dir_uses_transcript_path(env):  # noqa: F811
    """桥只给在跑会话报 config_dir，历史会话为空：按记录文件在哪个配置目录下补上。"""
    f = env['cfg_main'] / 'projects' / '-w-q' / 'x.jsonl'
    sid = add_row(env, config_dir=None, file=str(f))
    rows = {r['sid']: r for r in env['hub'].sessions('all')}
    assert rows[sid]['account'] == 'main' and rows[sid]['config_dir'] == str(env['cfg_main'])


def test_new_options_tags_directories(env, tmp_path, monkeypatch):  # noqa: F811
    """新建页目录带类型标签：工作树 / 评测 / 仓库 / 目录。"""
    monkeypatch.setenv('HOME', str(tmp_path))
    repo, wt, ev, plain = tmp_path / 'myrepo', tmp_path / 'wt' / 'x', tmp_path / 'evalrun' / 'k1', tmp_path / 'notes'
    for d in (repo / '.git', wt, ev, plain):
        d.mkdir(parents=True)
    (wt / '.git').write_text('gitdir: /somewhere')
    for d in (repo, wt, ev, plain):
        add_row(env, cwd=str(d), age_seconds=1)
    monkeypatch.setenv('PALM_DEFAULT_DIR', str(repo))
    _, r = call(env['base'], '/api/new_options', env['tok'])
    assert r['dir_tags'] == {str(repo): '仓库', str(wt): '工作树', str(ev): '评测', str(plain): '目录'}


def test_clear_falls_back_for_old_host(env, monkeypatch):  # noqa: F811
    """10-27 之前起的宿主不认 clear：中枢改用接入端输入送清空键，再注入。"""
    hub = env['hub']
    state = {'draft': 6, 'sent': [], 'inputs': []}

    def old_host(sid, req):
        if req['op'] == 'input':
            data = __import__('base64').b64decode(req['b64'])
            state['inputs'].append(data)
            if b'\x15' in data:
                state['draft'] = 0
            return {'ok': True}
        if req['op'] == 'inject':
            if state['draft'] and not req.get('force'):                     # 旧宿主：不认 clear
                return {'ok': False, 'reason': 'local_draft', 'draft_chars': state['draft']}
            state['sent'].append(req['text'])
            return {'ok': True}
        return {'ok': False}
    monkeypatch.setattr(hub, 'host_call', old_host)
    r = hub.inject({'sid': 'sid-host', 'text': '手机', 'clear': True})
    assert r['ok'] and r['cleared'] == 6 and state['sent'] == ['手机']
    assert state['inputs'] == [b'\x15' * 8 + b'\x0b' * 8]
