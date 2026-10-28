#!/usr/bin/env python3
"""掌上灵感 · 体检 palm doctor

逐项检查：启动包装、会话中枢、Agents Island 桥、信箱与手机、终端视图依赖、附件收件目录、后台会话数。
只读：不改任何配置、不重启任何服务；每项给出“通过 / 注意 / 失败”和处理办法。退出码：有失败为 1。
"""
import base64
import importlib.util
import json
import os
import stat
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

OK, WARN, FAIL = '通过', '注意', '失败'


def _get(url, headers=None, timeout=3):
    req = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read() or b'{}')


def check_wrapper(home: Path, palm_dir: Path) -> tuple:
    rc = home / '.bashrc'
    try:
        hit = any('palm.sh' in ln and not ln.lstrip().startswith('#') for ln in rc.read_text(encoding='utf-8').splitlines())
    except OSError:
        hit = False
    if not (palm_dir / 'host' / 'palm_host.py').is_file():
        return FAIL, '启动包装', f'找不到 {palm_dir}/host/palm_host.py'
    if not hit:
        return WARN, '启动包装', '~/.bashrc 没有 source palm.sh：新开终端的 claude 不经宿主，手机只能看不能插话'
    return OK, '启动包装', '~/.bashrc 已启用（新开终端的 claude 经宿主）'


def check_service() -> tuple:
    try:
        r = subprocess.run(['systemctl', '--user', 'is-active', 'palm-hub'], capture_output=True, text=True, timeout=5)
        state = r.stdout.strip() or r.stderr.strip()
    except (OSError, subprocess.TimeoutExpired) as e:
        return WARN, '中枢服务', f'查不到 systemd 状态：{e}'
    if state == 'active':
        return OK, '中枢服务', 'palm-hub 常驻中（systemd --user）'
    return FAIL, '中枢服务', f'palm-hub 状态 {state}：systemctl --user restart palm-hub，日志 journalctl --user -u palm-hub -n 50'


def check_hub(port: int) -> tuple:
    try:
        d = _get(f'http://127.0.0.1:{port}/api/health')
        return OK, '中枢接口', f"127.0.0.1:{port} 版本 {d.get('version', '?')}"
    except (OSError, ValueError, urllib.error.URLError) as e:
        return FAIL, '中枢接口', f'127.0.0.1:{port} 连不上（{e}）'


def check_bridge(url: str) -> tuple:
    try:
        st = _get(f'{url}/api/state')
        n = len((st.get('sessions') or {}).get('claude') or [])
        return OK, 'Agents Island 桥', f'{url} 在线，{n} 个 Claude 会话'
    except (OSError, ValueError, urllib.error.URLError) as e:
        return FAIL, 'Agents Island 桥', f'{url} 连不上（{e}）：会话总览只剩兜底、审批收不到；先查 Agents Island 桥是否在跑（bash launch/start_bridge.sh）'


def check_relay(palm_home: Path, port: int) -> list:
    """配对配置 + 中枢的链路状态（/api/link）+ 信箱那边看到的手机最近取件时间。"""
    p = palm_home / 'link.json'
    if not p.is_file():
        return [(WARN, '手机配对', '没有 ~/.palm/link.json：还没配对，运行 palm pair --adb <序列号> 或 palm pair --qr')]
    out = []
    mode = stat.S_IMODE(p.stat().st_mode)
    try:
        cfg = json.loads(p.read_text(encoding='utf-8'))
        base64.b64decode(cfg['pair_key_b64'])
        cfg['relay_url'], cfg['token']
    except (OSError, ValueError, KeyError) as e:
        return [(FAIL, '手机配对', f'link.json 读不了或缺项（{e}）：palm pair --rotate 重新配对')]
    out.append((OK if mode & 0o077 == 0 else WARN, '手机配对',
                f"本机 {cfg.get('me', 'palm-pc')} ↔ {cfg.get('peer', 'phone')}" + ('' if mode & 0o077 == 0 else f'；link.json 权限 {oct(mode)}，应为 600')))
    try:
        tok = (palm_home / 'hub.token').read_text(encoding='utf-8').strip()
        st = _get(f'http://127.0.0.1:{port}/api/link', {'X-Palm-Token': tok})
    except (OSError, ValueError, urllib.error.URLError) as e:
        st = {'error': str(e)}
    if not isinstance(st, dict):
        st = {'error': '中枢回的不是链路状态（旧版中枢没有 /api/link，重启 palm-hub）'}
    age, flying = st.get('poll_age'), st.get('in_flight')
    alive = (age is not None and age <= 60) or (flying is not None and flying <= 40)   # 长轮询最长 25 秒 + 余量
    if 'error' in st:
        out.append((FAIL, '信箱链路', f"问不到中枢的链路状态（{st['error']}）"))
    elif not st.get('paired'):
        out.append((FAIL, '信箱链路', '中枢没读到配对配置：配对后要 systemctl --user restart palm-hub'))
    elif not alive:
        out.append((FAIL, '信箱链路', f"中枢 {'从未' if age is None else f'{age} 秒前才'}成功取件：{st.get('url')} 连不上或令牌失效，看中枢日志"))
    else:
        when = f'{age} 秒前取过件' if age is not None else f'长连接中（{flying} 秒）'
        out.append((OK, '信箱链路', f"{st.get('url')} 在线，中枢{when}（收 {st.get('in', 0)}、发 {st.get('out', 0)}、丢弃 {st.get('bad', 0)}）"))
    try:
        peer = (_get(cfg['relay_url'].rstrip('/') + '/v2/peers', {'Authorization': f"Bearer {cfg['token']}"}, timeout=8)
                .get('peers') or {}).get(cfg.get('peer', 'phone'))
    except urllib.error.HTTPError as e:
        out.append((FAIL, '手机', f"信箱回 {e.code}：本机令牌被吊销或信箱配置变了，palm pair --rotate"))
        return out
    except (OSError, ValueError, urllib.error.URLError) as e:
        out.append((WARN, '手机', f'问不到信箱（{e}）'))
        return out
    if peer is None:
        out.append((WARN, '手机', '信箱没见过手机取件：在手机上打开掌上灵感'))
    else:
        mins = peer // 60
        when = f'{peer} 秒前' if peer < 120 else (f'{mins} 分钟前' if mins < 120 else f'{mins // 60} 小时前')
        out.append((OK, '手机', f'最近一次取件 {when}' + ('（App 在线）' if peer <= 40 else '（只在打开 App 时在线，属正常）')))
    return out


def check_pyte() -> tuple:
    if importlib.util.find_spec('pyte') is None:
        return WARN, '终端视图', '没装 pyte：pip install --user --break-system-packages pyte==0.8.2（其余功能不受影响）'
    return OK, '终端视图', 'pyte 已装'


def check_inbox(inbox: Path) -> tuple:
    try:
        inbox.mkdir(parents=True, exist_ok=True)
        ok = os.access(inbox, os.W_OK)
    except OSError:
        ok = False
    if not ok:
        return FAIL, '附件收件', f'{inbox} 不可写：手机发来的附件存不下'
    n = sum(1 for f in inbox.iterdir() if f.is_file())
    return OK, '附件收件', f'{inbox}（{n} 个文件，7 天自动清）'


def check_hosts(palm_home: Path, bg_max: int) -> tuple:
    term = bg = 0
    for p in (palm_home / 'run').glob('*.json') if (palm_home / 'run').is_dir() else []:
        try:
            d = json.loads(p.read_text(encoding='utf-8'))
            os.kill(int(d['host_pid']), 0)
        except (OSError, ValueError, KeyError, TypeError):
            continue
        bg += 1 if d.get('detached') else 0
        term += 0 if d.get('detached') else 1
    level = WARN if bg >= bg_max else OK
    return level, '经宿主的会话', f'终端 {term} 个、后台 {bg} 个（后台上限 {bg_max}）' + ('：已满，手机续接 / 新建会被拒' if bg >= bg_max else '')


def run(home=None, palm_home=None, palm_dir=None, port=None, bridge=None, inbox=None, bg_max=6, service=True) -> list:
    home = Path(home or Path.home())
    palm_home = Path(palm_home or os.environ.get('PALM_HOME_DIR') or home / '.palm')
    palm_dir = Path(palm_dir or Path(__file__).resolve().parents[1])
    port = int(port or os.environ.get('PALM_HUB_PORT') or 5612)
    bridge = (bridge or os.environ.get('PALM_BRIDGE_URL') or 'http://127.0.0.1:5599').rstrip('/')
    inbox = Path(inbox or os.environ.get('PALM_INBOX_DIR') or home / '.palm' / 'inbox')
    rows = [check_wrapper(home, palm_dir)]
    if service:
        rows.append(check_service())
    rows += [check_hub(port), check_bridge(bridge), *check_relay(palm_home, port), check_pyte(), check_inbox(inbox),
             check_hosts(palm_home, bg_max)]
    return rows


def main():
    rows = run()
    width = max(len(r[1]) for r in rows)
    for level, item, detail in rows:
        print(f'[{level}] {item}{"　" * (width - len(item))}  {detail}')
    fails = sum(1 for r in rows if r[0] == FAIL)
    warns = sum(1 for r in rows if r[0] == WARN)
    print(f'\n共 {len(rows)} 项：失败 {fails}，注意 {warns}')
    sys.exit(1 if fails else 0)


if __name__ == '__main__':
    main()
