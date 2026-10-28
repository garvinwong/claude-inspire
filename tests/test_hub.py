"""会话中枢测试：假Agents Island 桥 + 真宿主（跑假会话）+ 临时账号目录，绝不碰真实 ~/.palm 与桥。"""
import base64
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'hub'))
import palm_hub  # noqa: E402
from test_host import Term, registry  # noqa: E402

PNG = base64.b64encode(b'\x89PNG\r\n\x1a\n' + b'7' * 30).decode()


class FakeBridge:
    def __init__(self):
        self.state = {'sessions': {'claude': []}, 'pending': []}
        self.decisions = []
        self.posts = []                                   # (路径, 正文)
        fb = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                body = json.dumps(fb.state).encode()
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                fb.decisions.append(body)
                fb.posts.append((self.path, body))
                self.send_response(200)
                self.send_header('Content-Length', '11')
                self.end_headers()
                self.wfile.write(b'{"ok":true}')

        self.srv = ThreadingHTTPServer(('127.0.0.1', 0), H)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.url = f'http://127.0.0.1:{self.srv.server_port}'

    def close(self):
        self.srv.shutdown()


def call(base, path, token=None, body=None):
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                 method='GET' if body is None else 'POST')
    if token:
        req.add_header('X-Palm-Token', token)
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if r.headers.get('Content-Type', '').startswith('application/json') else raw)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


@pytest.fixture
def env(tmp_path):
    home = tmp_path / 'palm'
    cfg_main = tmp_path / '.claude'                                    # 单账号：一个配置目录
    for c in (cfg_main,):
        (c / 'sessions').mkdir(parents=True)
    # 一个经宿主跑的会话（假会话进程）
    term = Term(home, env={'CLAUDE_CONFIG_DIR': str(cfg_main)})
    term.wait_for(b'READY')
    reg = registry(home)
    (cfg_main / 'sessions' / f"{reg['child_pid']}.json").write_text(json.dumps(
        {'pid': reg['child_pid'], 'sessionId': 'sid-host', 'status': 'idle', 'name': 'host-1', 'cwd': '/w/x'}))
    tfile = cfg_main / 'projects' / '-w-x' / 'sid-host.jsonl'
    tfile.parent.mkdir(parents=True)
    tfile.write_text('\n'.join(json.dumps(x, ensure_ascii=False) for x in [
        {'type': 'user', 'uuid': 'a', 'message': {'content': '你好'}},
        {'type': 'user', 'uuid': 'b', 'message': {'content': [{'type': 'image', 'source': {'type': 'base64', 'data': PNG}}]}},
        {'type': 'assistant', 'uuid': 'c', 'message': {'content': [{'type': 'text', 'text': '收到'}]}},
    ]) + '\n', encoding='utf-8')
    fb = FakeBridge()
    fb.state['sessions']['claude'] = [
        {'session_id': 'sid-live', 'title': '在跑的会话', 'cwd': '/w/y', 'is_live': True, 'config_dir': str(cfg_main),
         'status': 'thinking', 'age_seconds': 5, 'pid': '1'},
        {'session_id': 'sid-host', 'title': '宿主会话', 'cwd': '/w/x', 'is_live': True, 'config_dir': str(cfg_main),
         'status': 'idle', 'age_seconds': 9, 'pid': str(reg['child_pid'])},
        {'session_id': 'sid-old', 'title': '历史', 'cwd': '/w/z', 'is_live': False, 'config_dir': str(cfg_main),
         'age_seconds': 9999},
    ]
    fb.state['pending'] = [{'id': 'p1', 'agent_source': 'claude', 'session_id': 'sid-live'}, {'id': 'p2', 'agent_source': 'codex'}]
    hub = palm_hub.Hub(home=home, bridge=fb.url, config_dirs=[str(cfg_main)])
    srv = palm_hub.make_server(hub, '127.0.0.1', 0)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f'http://127.0.0.1:{srv.server_port}'
    yield dict(home=home, hub=hub, base=base, tok=hub.token, term=term, fb=fb, cfg_main=cfg_main, reg=reg)
    srv.shutdown()
    fb.close()
    term.close()


def test_token_required_and_file_private(env):
    assert call(env['base'], '/api/health')[0] == 200
    assert call(env['base'], '/api/sessions')[0] == 401
    assert call(env['base'], '/api/sessions', token='wrong' * 10)[0] == 401
    assert call(env['base'], '/api/inject', body={'sid': 'sid-host', 'text': 'x'})[0] == 401
    tf = env['home'] / 'hub.token'
    assert oct(tf.stat().st_mode & 0o777) == '0o600'
    assert palm_hub.Hub(home=env['home']).token == env['tok']             # 重启后令牌不变


def test_sessions_merge_and_labels(env):
    code, r = call(env['base'], '/api/sessions', env['tok'])
    rows = {x['sid']: x for x in r['sessions']}
    assert list(rows) == ['sid-host', 'sid-live']                          # 宿主会话排最前；历史默认不列
    assert rows['sid-host']['control'] == 'host' and rows['sid-host']['account'] == 'main'
    assert rows['sid-host']['host_id'] == env['reg']['id']
    assert rows['sid-live']['control'] == 'live' and rows['sid-live']['account'] == 'main'
    _, r = call(env['base'], '/api/sessions?scope=all', env['tok'])
    assert {x['sid']: x['control'] for x in r['sessions']}['sid-old'] == 'history'


def test_events_and_images(env):
    code, r = call(env['base'], '/api/events?sid=sid-host', env['tok'])
    assert code == 200 and [e['k'] for e in r['events']] == ['user', 'image', 'text']
    img = r['events'][1]['img']
    code, raw = call(env['base'], f'/api/image?id={img}', env['tok'])
    assert code == 200 and raw.startswith(b'\x89PNG')
    assert call(env['base'], '/api/image?id=../../x', env['tok'])[0] == 404
    assert call(env['base'], '/api/events?sid=nope', env['tok'])[0] == 404
    _, r2 = call(env['base'], f"/api/events?sid=sid-host&since={r['cursor']}", env['tok'])
    assert r2['events'] == []


def test_inject_routes_to_host_and_refuses_unhosted(env):
    code, r = call(env['base'], '/api/inject', env['tok'], {'sid': 'sid-host', 'text': '手机来的话'})
    assert r == {'ok': True}
    env['term'].wait_for(b'GOT 0d')
    assert env['term'].got() == b'\x1b[200~' + '手机来的话'.encode() + b'\x1b[201~\r'
    _, r = call(env['base'], '/api/inject', env['tok'], {'sid': 'sid-live', 'text': 'x'})
    assert r == {'ok': False, 'reason': 'not_hosted'}


def test_inject_respects_local_typing(env):
    env['term'].write(b'abc')
    env['term'].wait_for(b'GOT 616263')
    _, r = call(env['base'], '/api/inject', env['tok'], {'sid': 'sid-host', 'text': 'x'})
    assert r['reason'] == 'local_typing'


def test_keys_route(env):
    _, r = call(env['base'], '/api/keys', env['tok'], {'sid': 'sid-host', 'keys': ['esc']})
    assert r['ok']
    env['term'].wait_for(b'GOT 1b')


def test_pending_filters_claude_and_decision_proxies(env):
    _, r = call(env['base'], '/api/pending', env['tok'])
    assert [p['id'] for p in r['pending']] == ['p1']
    _, r = call(env['base'], '/api/decision', env['tok'], {'id': 'p1', 'decision': 'deny', 'reason': '先别删', 'extra': 1})
    assert r['ok'] and env['fb'].decisions == [{'id': 'p1', 'decision': 'deny', 'reason': '先别删'}]
    _, r = call(env['base'], '/api/decision', env['tok'], {'id': 'p1', 'decision': 'rm'})
    assert r['reason'] == 'bad_decision' and len(env['fb'].decisions) == 1


def test_bridge_down_falls_back_to_session_files(env):
    hub = palm_hub.Hub(home=env['home'], bridge='http://127.0.0.1:9', config_dirs=[str(env['cfg_main'])])
    rows = hub.sessions()
    assert [(r['sid'], r['control'], r['account']) for r in rows] == [('sid-host', 'host', 'main')]
    assert hub.transcript_path(rows[0]).name == 'sid-host.jsonl'          # 没有桥给的路径时按目录推算


def test_dead_host_is_ignored(env):
    import signal
    os.kill(env['reg']['host_pid'], signal.SIGKILL)       # 宿主崩溃：登记文件残留
    env['term'].p.wait(timeout=5)
    assert list((env['home'] / 'run').glob('*.json'))
    rows = palm_hub.Hub(home=env['home'], bridge=env['fb'].url).sessions()
    assert {r['sid']: r['control'] for r in rows}['sid-host'] == 'live'   # 宿主没了，退回“在跑”（桥的视角）


def test_account_label_and_slug():
    assert palm_hub.account_label('/home/u/.claude') == 'main'
    assert palm_hub.project_slug('/home/u/wt/fix-it_T.1') == '-home-u-wt-fix-it-T-1'


def test_host_without_session_id_is_listed_and_controllable(env):
    """10-26 实测：未信任目录里启动时，确认“信任此目录”之前 Claude Code 不写 sessions/<pid>.json，
    手机曾看不到这个会话、也就答不了那个对话框（默认选项还是“否，退出”）。"""
    (env['cfg_main'] / 'sessions' / f"{env['reg']['child_pid']}.json").unlink()
    _, r = call(env['base'], '/api/sessions', env['tok'])
    row = next(x for x in r['sessions'] if x['sid'] == f"host:{env['reg']['id']}")
    assert row['control'] == 'host' and row['status'] == 'starting' and row['account'] == 'main'
    _, k = call(env['base'], '/api/keys', env['tok'], {'sid': row['sid'], 'keys': ['down', 'enter']})
    assert k['ok']
    env['term'].wait_for(b'GOT ')
    _, s = call(env['base'], f"/api/screen?sid={row['sid']}", env['tok'])
    assert s['ok'] and 'SIZE' in ''.join(seg[0] for ln in s['lines'] for seg in ln)   # 旁听接上并收到了重画


def test_pending_uses_agent_source_and_is_enriched(env):
    """10-26 核对真实数据：桥的待审批条目用 agent_source 标来源（曾误读 agent，Codex 审批会混进手机）。"""
    env['fb'].state['pending'] = [
        {'id': 'c1', 'agent_source': 'codex', 'tool_name': 'Bash', 'tool_input': {'command': 'ls'}},
        {'id': 'k1', 'agent_source': 'claude', 'session_id': 'sid-host', 'tool_name': 'Bash', 'kind': 'perm',
         'tool_input': {'command': 'git push --force origin main', 'description': '强推'}, '_arrived': time.time() - 10},
        {'id': 'k2', 'agent_source': 'claude', 'tool_name': 'AskUserQuestion', 'kind': 'ask',
         'tool_input': {'questions': [{'question': '选哪个？', 'header': '方案', 'multiSelect': False,
                                       'options': [{'label': 'A', 'description': '甲'}, {'label': 'B', 'description': '乙'}]}]}},
        {'id': 'k3', 'agent_source': 'claude', 'tool_name': 'ExitPlanMode', 'kind': 'plan',
         'tool_input': {'plan': '# 计划\n' + '步' * 9000}},
        {'id': 'k4', 'agent_source': 'claude', 'tool_name': 'Edit',
         'tool_input': {'file_path': '/w/x/a.py', 'old_string': 'a' * 6000, 'new_string': 'b'}},
    ]
    env['hub']._bridge_cache = (0.0, None)
    _, r = call(env['base'], '/api/pending', env['tok'])
    p = {x['id']: x for x in r['pending']}
    assert set(p) == {'k1', 'k2', 'k3', 'k4'}                                  # Codex 的不进手机
    assert p['k1']['risk'] == 'danger' and p['k1']['human'] == 'git 强制推送：main' and p['k1']['sid'] == 'sid-host'
    assert 0 < p['k1']['expires_in'] <= p['k1']['ttl']
    assert p['k2']['kind'] == 'ask' and p['k2']['questions'][0]['options'][1]['label'] == 'B'
    assert p['k3']['kind'] == 'plan' and p['k3']['plan'].startswith('# 计划') and len(p['k3']['plan']) < 9000
    assert p['k4']['input']['file_path'] == '/w/x/a.py' and len(p['k4']['input']['old_string']) < 5000


def test_usage_and_pc_active(env, tmp_path, monkeypatch):
    rl = tmp_path / 'rl'
    rl.mkdir()
    monkeypatch.setattr(palm_hub, 'RL_DIR', rl)
    (rl / 'rl.json').write_text('坏的')
    assert env['hub'].usage() == {}                                            # 坏缓存当没有
    far = int(time.time()) + 3600
    (rl / 'rl.json').write_text(json.dumps({'five_hour': {'used_percentage': 40, 'resets_at': far},
                                            'seven_day': {'used_percentage': 73, 'resets_at': far + 1}}))
    u = env['hub'].usage()
    assert u['main']['h5'] == {'pct': 40, 'resets_at': far} and u['main']['d7']['pct'] == 73 and u['main']['at'] > 0
    assert env['hub'].pc_active() is False                                     # 宿主从没收到过本机按键
    env['term'].write(b'x')
    env['term'].wait_for(b'GOT 78')
    assert env['hub'].pc_active() is True
    monkeypatch.setattr(palm_hub, 'PC_ACTIVE_SECONDS', 0)
    assert env['hub'].pc_active() is False


def test_live_rows_carry_model_and_effort(env):
    """对话页五个指标：在跑会话的行带 model / effort（来自记录尾部的 api-request）。"""
    tfile = env['cfg_main'] / 'projects' / '-w-x' / 'sid-host.jsonl'
    with open(tfile, 'a', encoding='utf-8') as f:
        f.write(json.dumps({'type': 'api-request', 'querySource': 'repl_main_thread',
                            'params': {'model': 'claude-opus-5-5', 'output_config': {'effort': 'high'}}}) + '\n')
    env['hub']._bridge_cache = (0.0, None)
    _, r = call(env['base'], '/api/sessions', env['tok'])
    row = next(s for s in r['sessions'] if s['sid'] == 'sid-host')
    assert row['model'] == 'claude-opus-5-5' and row['effort'] == 'high'


def test_stats_endpoint_counts_tokens_and_quota(env, monkeypatch):
    now = time.time()
    with open(env['cfg_main'] / 'projects' / '-w-x' / 'sid-host.jsonl', 'a', encoding='utf-8') as f:
        f.write(json.dumps({'type': 'assistant', 'timestamp': time.strftime('%Y-%m-%dT%H:%M:%S%z', time.localtime(now - 60)),
                            'message': {'id': 'm1', 'model': 'claude-opus-5-5',
                                        'content': [{'type': 'tool_use', 'name': 'Bash', 'input': {}}],
                                        'usage': {'input_tokens': 5, 'output_tokens': 50, 'cache_read_input_tokens': 945}}}) + '\n')
    monkeypatch.setattr(env['hub'], 'usage', lambda: {'main': {'h5': {'pct': 40}, 'd7': {'pct': 9}}})
    st, r = call(env['base'], '/api/stats?days=1', token=env['tok'])
    assert st == 200 and r['ready'] and r['total']['tokens'] == 1000
    assert r['days'][0]['acct'] == {'main': 1000} and r['tools'] == [['Bash', 1]]
    assert r['quota']['main']['h5']['pct'] == 40 and r['trend'][-1]['main'] == [40, 9]   # 当场也记一次额度
    assert call(env['base'], '/api/stats?days=abc', token=env['tok'])[1]['days'].__len__() == 7
    assert call(env['base'], '/api/stats')[0] == 401


def test_inject_clear_through_hub_and_queue_reports_idle(env):
    env['term'].write(b'left')                                             # 出门前留在电脑输入框的字
    env['term'].wait_for(b'GOT 6c656674')
    st, r = call(env['base'], '/api/inject', token=env['tok'], body={'sid': 'sid-host', 'text': '手机', 'queue': True})
    assert r['queued'] and r['draft_chars'] == 4 and r['idle_s'] is not None
    call(env['base'], '/api/outbox/cancel', token=env['tok'], body={'qid': r['qid']})
    st, r = call(env['base'], '/api/inject', token=env['tok'], body={'sid': 'sid-host', 'text': '手机', 'clear': True})
    assert st == 200 and r['ok'] and r['cleared'] == 4
    env['term'].wait_for(b'GOT 0d')


def test_outbox_flush_clear_and_force(env):
    env['term'].write(b'left')
    env['term'].wait_for(b'GOT 6c656674')
    _, r = call(env['base'], '/api/inject', env['tok'], {'sid': 'sid-host', 'text': 'A', 'queue': True})
    assert call(env['base'], '/api/outbox/flush', env['tok'], {'qid': r['qid'], 'mode': 'bad'})[1]['reason'] == 'bad_mode'
    _, f = call(env['base'], '/api/outbox/flush', env['tok'], {'qid': r['qid'], 'mode': 'clear'})
    assert f['ok'] and f['cleared'] == 4
    assert call(env['base'], '/api/outbox/flush', env['tok'], {'qid': r['qid'], 'mode': 'clear'})[1]['reason'] == 'not_waiting'
    env['term'].wait_for(b'GOT 0d')
    env['term'].write(b'zz')                                              # 再留草稿，试合并发送
    env['term'].wait_for(b'GOT 7a7a')
    _, r = call(env['base'], '/api/inject', env['tok'], {'sid': 'sid-host', 'text': 'B', 'queue': True})
    _, f = call(env['base'], '/api/outbox/flush', env['tok'], {'qid': r['qid'], 'mode': 'force'})
    assert f['ok'] and 'cleared' not in f
    assert [m['state'] for m in call(env['base'], '/api/outbox', env['tok'])[1]['outbox']].count('sent') == 2


def test_open_on_pc_launches_windows_terminal_attach_on_wsl(env, monkeypatch):
    launched = []
    monkeypatch.delenv('PALM_TERMINAL', raising=False)
    monkeypatch.setattr(palm_hub.subprocess, 'Popen', lambda argv, **kw: launched.append((argv, kw)))
    monkeypatch.setattr(palm_hub.Hub, '_is_wsl', staticmethod(lambda: True))
    monkeypatch.setattr(palm_hub.Hub, '_find_wt', staticmethod(lambda: Path('/mnt/c/Users/u/wt.exe')))
    monkeypatch.setattr(palm_hub.Hub, '_distro', staticmethod(lambda: 'Ubuntu'))
    _, r = call(env['base'], '/api/open_on_pc', env['tok'], {'sid': 'sid-host'})
    assert r['ok'] and r['host_id'] == env['reg']['id']
    argv, kw = launched[0]
    assert argv[:3] == ['/mnt/c/Users/u/wt.exe', '-w', 'new'] and argv[-1] == f"palm attach {env['reg']['id']}"
    assert ['-d', 'Ubuntu'] == argv[argv.index('wsl.exe') + 1:argv.index('wsl.exe') + 3]
    assert call(env['base'], '/api/open_on_pc', env['tok'], {'sid': 'sid-live'})[1]['reason'] == 'not_hosted'   # 不经宿主的接不了
    assert call(env['base'], '/api/open_on_pc', env['tok'], {'sid': 'nope'})[1]['reason'] == 'no_session'
    monkeypatch.setattr(palm_hub.Hub, '_find_wt', staticmethod(lambda: None))
    r = call(env['base'], '/api/open_on_pc', env['tok'], {'sid': 'sid-host'})[1]
    assert r['reason'] == 'no_terminal' and r['command'] == f"palm attach {env['reg']['id']}"   # 手机上显示命令


def test_terminal_argv_per_platform(env, monkeypatch):
    """“在电脑上打开”按平台选终端：自定义 > WSL > macOS > Linux 图形会话；都没有返回 None（手机显示命令）。"""
    hub, cmd = env['hub'], 'palm attach abc123'
    monkeypatch.setattr(palm_hub.Hub, '_is_wsl', staticmethod(lambda: False))
    monkeypatch.setenv('PALM_TERMINAL', 'gnome-terminal -- bash -lc {cmd}')
    assert hub._terminal_argv('t', cmd) == ['gnome-terminal', '--', 'bash', '-lc', cmd]
    monkeypatch.delenv('PALM_TERMINAL')
    monkeypatch.setattr(palm_hub.sys, 'platform', 'darwin')
    assert hub._terminal_argv('t', cmd)[:2] == ['osascript', '-e'] and cmd in hub._terminal_argv('t', cmd)[2]
    monkeypatch.setattr(palm_hub.sys, 'platform', 'linux')
    monkeypatch.delenv('DISPLAY', raising=False)
    monkeypatch.delenv('WAYLAND_DISPLAY', raising=False)
    assert hub._terminal_argv('t', cmd) is None                                 # 没有图形会话
    monkeypatch.setenv('DISPLAY', ':0')
    monkeypatch.setattr(palm_hub.shutil, 'which', lambda t: '/usr/bin/konsole' if t == 'konsole' else None)
    assert hub._terminal_argv('t', cmd) == ['konsole', '-e', 'bash', '-lc', cmd]
    monkeypatch.setattr(palm_hub.shutil, 'which', lambda t: None)
    assert hub._terminal_argv('t', cmd) is None


def test_ghost_live_rows_on_host_pid_are_dropped(env):
    """实际遇到过：首页三个“只能看”其实是宿主外壳里先前跑过的旧会话——桥按外壳进程号认它们“还活着”。
    外壳里现在跑的是别的会话，这些旧条目应当不出现在首页（记录仍可在历史里续接）。"""
    fb = env['fb']
    fb.state['sessions']['claude'].append(
        {'session_id': 'sid-ghost', 'title': '旧会话', 'cwd': '/w/x', 'is_live': True, 'config_dir': str(env['cfg_main']),
         'status': 'standby', 'age_seconds': 50, 'pid': str(env['reg']['host_pid'])})
    env['hub']._bridge_cache = (0.0, None)
    _, r = call(env['base'], '/api/sessions', env['tok'])
    sids = [s['sid'] for s in r['sessions']]
    assert 'sid-ghost' not in sids and 'sid-host' in sids and 'sid-live' in sids
    _, r = call(env['base'], '/api/sessions?scope=all', env['tok'])
    ghost = [s for s in r['sessions'] if s['sid'] == 'sid-ghost']
    assert ghost and ghost[0]['control'] == 'history'                       # 历史里还在，可续接


def test_demo_root_filters_everything_listed(env, tmp_path, monkeypatch):
    """演示模式：会话、待办、最近目录、新建缺省目录、仪表盘排行都只剩演示目录里的；~/demo 不收 ~/demo2。"""
    demo, sib = tmp_path / 'demo', tmp_path / 'demo2'
    (demo / 'app').mkdir(parents=True)
    sib.mkdir()
    link = tmp_path / 'link-into-demo'
    link.symlink_to(demo / 'app')
    fb = env['fb']
    fb.state['sessions']['claude'] = [
        {'session_id': 'd1', 'title': '演示', 'cwd': str(demo / 'app'), 'is_live': True, 'config_dir': str(env['cfg_main']), 'age_seconds': 1},
        {'session_id': 'd2', 'title': '链接进来', 'cwd': str(link), 'is_live': False, 'config_dir': str(env['cfg_main']), 'age_seconds': 2},
        {'session_id': 'w1', 'title': '工作', 'cwd': str(sib), 'is_live': True, 'config_dir': str(env['cfg_main']), 'age_seconds': 3},
        {'session_id': 'w2', 'title': '工作史', 'cwd': '/w/z', 'is_live': False, 'config_dir': str(env['cfg_main']), 'age_seconds': 4},
    ]
    fb.state['pending'] = [{'id': 'p-demo', 'agent_source': 'claude', 'session_id': 'd1'},
                           {'id': 'p-work', 'agent_source': 'claude', 'session_id': 'w1'},
                           {'id': 'p-none', 'agent_source': 'claude'}]
    monkeypatch.setenv('PALM_DEMO_ROOT', str(demo))
    monkeypatch.delenv('PALM_DEFAULT_DIR', raising=False)
    hub = palm_hub.Hub(home=env['home'], bridge=fb.url, config_dirs=[str(env['cfg_main']), str(env['cfg_main'])])
    assert {r['sid'] for r in hub.sessions('all')} == {'d1', 'd2'}
    assert {p['id'] for p in hub.pending()} == {'p-demo'}
    assert hub.find('w1') is None                                       # 演示时连打开都不行
    assert all(d.startswith(str(demo)) or os.path.realpath(d).startswith(str(demo)) for d in hub.recent_dirs())
    assert hub.new_options()['default_dir'] == str(demo)
    monkeypatch.setattr(hub.stats_db, 'refresh', lambda *a, **k: None)
    monkeypatch.setattr(hub.stats_db, 'summary', lambda days: {'sessions': [
        {'sid': 'd1', 'cwd': str(demo / 'app')}, {'sid': 'w1', 'cwd': str(sib)}, {'sid': 'w3', 'cwd': ''}]})
    monkeypatch.setattr(hub, 'usage', lambda: {})
    assert [s['sid'] for s in hub.stats(7)['sessions']] == ['d1']


def test_demo_root_unset_keeps_everything(env, monkeypatch):
    monkeypatch.delenv('PALM_DEMO_ROOT', raising=False)
    hub = palm_hub.Hub(home=env['home'], bridge=env['fb'].url, config_dirs=[str(env['cfg_main']), str(env['cfg_main'])])
    assert {'sid-live', 'sid-host', 'sid-old'} <= {r['sid'] for r in hub.sessions('all')}
    assert [p['id'] for p in hub.pending()] == ['p1']


def test_session_dir_is_launch_dir_not_last_cd(env, tmp_path, monkeypatch):
    """实际遇到过：会话在项目根目录启动，中途命令 cd 进了子目录，记录里每条消息的 cwd 跟着变；
    桥取最新一条 → 手机显示记忆目录，续接也在那里拉起，CLI 问“是否信任此目录”。应以启动目录为准。"""
    launch, sub = tmp_path / 'proj', tmp_path / 'proj' / 'deep' / 'memory'
    sub.mkdir(parents=True)
    sid = 'abcdef12-0000-4000-8000-000000000001'
    f = env['cfg_main'] / 'projects' / '-proj' / f'{sid}.jsonl'
    f.parent.mkdir(parents=True)
    f.write_text('\n'.join(json.dumps(x) for x in [
        {'type': 'user', 'uuid': 'a', 'cwd': str(launch), 'message': {'content': '开始'}},
        {'type': 'assistant', 'uuid': 'b', 'cwd': str(sub), 'message': {'content': [{'type': 'text', 'text': '好'}]}},
    ]) + '\n', encoding='utf-8')
    env['fb'].state['sessions']['claude'].append({'session_id': sid, 'title': '中途 cd 过', 'cwd': str(sub), 'file': str(f),
        'is_live': False, 'config_dir': str(env['cfg_main']), 'age_seconds': 9999})   # 桥给的是最后一次 cd 到的目录
    env['hub']._bridge_cache = (0.0, None)
    _, r = call(env['base'], '/api/sessions?scope=all', env['tok'])
    assert next(s for s in r['sessions'] if s['sid'] == sid)['cwd'] == str(launch)
    spawned = []
    monkeypatch.setattr(env['hub'], '_spawn', lambda argv, cwd, cd, name: spawned.append(cwd) or {'ok': True})
    rr = env['hub'].resume({'sid': sid})
    assert rr['ok'] and spawned == [str(launch)], rr                         # 续接在启动目录拉起


def test_close_ends_hosted_session_even_with_keep_alive(env):
    """长按会话卡“关闭会话”（关闭藏得太深，原来只能发 /exit）：结束宿主里的会话进程，宿主随之退出；
    开着“保持后台”也照样关（保持后台只挡关终端的 SIGHUP）。不经宿主的会话不能关。"""
    _, r = call(env['base'], '/api/keep_alive', env['tok'], {'sid': 'sid-host', 'on': True})
    assert r['ok']
    _, r = call(env['base'], '/api/sessions', env['tok'])
    row = next(x for x in r['sessions'] if x['sid'] == 'sid-host')
    assert row['keep_alive'] is True                                       # 面板上显示当前开关
    assert call(env['base'], '/api/close', env['tok'], {'sid': 'sid-live'})[1]['reason'] == 'not_hosted'
    assert call(env['base'], '/api/close', env['tok'], {'sid': 'nope'})[1]['reason'] == 'not_hosted'
    (env['cfg_main'] / 'sessions' / f"{env['reg']['child_pid']}.json").unlink()   # 模拟退出途中：会话文件先删、进程还在
    import signal
    os.kill(env['reg']['child_pid'], signal.SIGSTOP)                       # 让宿主收到 SIGTERM 后一时退不掉
    _, r = call(env['base'], '/api/close', env['tok'], {'sid': f"host:{env['reg']['id']}"})
    assert r['ok']
    _, r = call(env['base'], '/api/sessions', env['tok'])
    assert not any(x.get('host_id') == env['reg']['id'] for x in r['sessions'])   # 真机实测：关闭途中冒出“启动中”
    os.kill(env['reg']['child_pid'], signal.SIGCONT)
    env['term'].p.wait(timeout=5)                                           # 宿主进程退出
    _, r = call(env['base'], '/api/sessions', env['tok'])
    assert not any(x.get('host_id') == env['reg']['id'] for x in r['sessions'])


def test_close_refuses_pid_that_is_not_a_host(env, monkeypatch):
    """登记文件里的进程号被别的进程复用时不能误杀。"""
    monkeypatch.setattr(palm_hub, '_is_host_pid', lambda pid: False)
    assert call(env['base'], '/api/close', env['tok'], {'sid': 'sid-host'})[1]['reason'] == 'not_hosted'
    assert env['term'].p.poll() is None


def test_usage_window_past_reset_shows_reset_not_stale_pct(env, tmp_path, monkeypatch):
    """实际遇到过：缓存停在两天前的 5h 99%。重置时间已过：旧数作废，但也不能当 0——
    无头会话照样用额度却不刷状态栏。报未知，等手动刷新。"""
    rl = tmp_path / 'rl'
    rl.mkdir()
    past, far = int(time.time()) - 60, int(time.time()) + 3600
    (rl / 'rl.json').write_text(json.dumps({'five_hour': {'used_percentage': 99, 'resets_at': past},
                                                   'seven_day': {'used_percentage': 81, 'resets_at': far}}))
    monkeypatch.setattr(palm_hub, 'RL_DIR', rl)
    u = env['hub'].usage()['main']
    assert u['h5'] == {'pct': None, 'resets_at': None, 'reset': True}
    assert u['d7'] == {'pct': 81, 'resets_at': far}


def test_yolo_toggles_bridge_and_rows_carry_flag(env):
    """长按会话卡“YOLO 模式”：沿用Agents Island 桥的会话级 YOLO（工具审批秒放行，选择题 / 计划照常问），
    手机与桌面岛同一个开关；会话行带 yolo 标记。"""
    _, r = call(env['base'], '/api/yolo', env['tok'], {'sid': 'sid-host', 'on': True})
    assert r['ok']
    assert env['fb'].posts[-1] == ('/api/session_yolo', {'session_id': 'sid-host', 'on': True})
    env['fb'].state['yolo_sessions'] = ['sid-host']
    env['hub']._bridge_cache = (0.0, None)
    _, r = call(env['base'], '/api/sessions', env['tok'])
    rows = {x['sid']: x for x in r['sessions']}
    assert rows['sid-host']['yolo'] is True and rows['sid-live']['yolo'] is False
    assert call(env['base'], '/api/yolo', env['tok'], {'sid': 'nope', 'on': True})[1]['reason'] == 'no_session'


FAKE_PROBE = r'''
import json, os, sys
open(os.environ['PROBE_LOG'], 'a').write(json.dumps({'argv': sys.argv[1:], 'cfg': os.environ.get('CLAUDE_CONFIG_DIR')}) + '\n')
if os.environ.get('PROBE_MODE') == 'none':
    print(json.dumps({'type': 'result', 'subtype': 'success'})); sys.exit(0)
print(json.dumps({'type': 'system', 'subtype': 'init'}))
print(json.dumps({'type': 'rate_limit_event', 'rate_limit_info': {'unifiedWindows': {
    'five_hour': {'utilization': 0.37, 'resetsAt': 4102444800}, 'seven_day': {'utilization': 0.805, 'resetsAt': 4102444900}}}}))
print(json.dumps({'type': 'result', 'subtype': 'success'}))
'''


def test_refresh_usage_probes_account_headless(env, tmp_path, monkeypatch):
    """长按额度卡“刷新使用率”：手动起一次无头会话（haiku、一个字、不留记录），
    读 stream-json 里的 rate_limit_event 拿实时 5h / 7d，写进中枢自己的缓存；总览取较新的那份。"""
    fake = tmp_path / 'fake_claude.py'
    fake.write_text(FAKE_PROBE)
    log = tmp_path / 'probe.log'
    monkeypatch.setenv('PROBE_LOG', str(log))
    real = palm_hub.PROBE_ARGV                                             # 真命令：haiku、不留会话记录、要 stream-json
    assert real[:2] == ['claude', '-p'] and '--no-session-persistence' in real and 'haiku' in real and 'stream-json' in real
    monkeypatch.setattr(palm_hub, 'PROBE_ARGV', [sys.executable, str(fake)])
    rl = tmp_path / 'rl'
    rl.mkdir()
    old = rl / 'rl.json'
    old.write_text(json.dumps({'five_hour': {'used_percentage': 99, 'resets_at': 4102444800}}))
    os.utime(old, (time.time() - 86400, time.time() - 86400))                # 一天前的状态栏缓存
    monkeypatch.setattr(palm_hub, 'RL_DIR', rl)
    hub = env['hub']
    assert hub.refresh_usage('main', wait=True)['ok']
    u = hub.usage()['main']
    assert u['h5'] == {'pct': 37, 'resets_at': 4102444800} and u['d7']['pct'] == 81 and u['at'] > time.time() - 60
    probe = json.loads(log.read_text().splitlines()[0])
    assert probe['cfg'] == str(env['cfg_main'])                              # 跑在配置目录下
    assert hub.refresh_usage('main')['reason'] == 'cooldown'                    # 手动也限频：60 秒一次
    old.write_text(json.dumps({'five_hour': {'used_percentage': 12, 'resets_at': 4102444800}}))   # 之后状态栏又写了：取新的
    assert hub.usage()['main']['h5']['pct'] == 12


def test_refresh_usage_without_event_keeps_old_and_reports(env, tmp_path, monkeypatch):
    fake = tmp_path / 'fake_claude.py'
    fake.write_text(FAKE_PROBE)
    monkeypatch.setenv('PROBE_LOG', str(tmp_path / 'probe.log'))
    monkeypatch.setenv('PROBE_MODE', 'none')
    monkeypatch.setattr(palm_hub, 'PROBE_ARGV', [sys.executable, str(fake)])
    monkeypatch.setattr(palm_hub, 'RL_DIR', tmp_path)
    hub = env['hub']
    hub.refresh_usage('main', wait=True)                                     # 没拿到事件：不写缓存、报原因
    assert 'main' not in hub.usage()
    assert hub.probe_state()['main']['error'] == 'no_rate_limit_event' and not hub.probe_state()['main']['running']
