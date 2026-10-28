#!/usr/bin/env python3
"""掌上灵感 · 会话中枢 palm-hub

职责：会话发现与合并、会话事件、图片、注入与按键（经会话宿主）、审批转发（经 Agents Island 桥）。
本机接口只听 127.0.0.1:5612；手机经内置的局域网信箱（lanbox.py，端到端加密信封）与中枢收发。

为什么要令牌：WSL 的 127.0.0.1 端口会被转发到 Windows 一侧，不设令牌则 Windows 上任何程序都能往会话里注入命令。
令牌在 ~/.palm/hub.token（权限 600，首次启动生成），请求头 X-Palm-Token。

会话来源（三路合并，按会话号去重）：
  1. Agents Island 桥 /api/state 的 claude 会话（在跑与历史，带记录文件路径）；
  2. 宿主登记表 ~/.palm/run/*.json（可插话）；子进程号 → <账号目录>/sessions/<进程号>.json 得会话号；
  3. 桥不在时兜底：sessions/*.json 里进程还活着的会话。
控制级别：host（经宿主，可插话）/ live（在跑，可看可审批）/ history（已结束，可续接，第 4 步）。

接口（除 health 外都要令牌）：
  GET  /api/health
  GET  /api/sessions[?scope=all]            默认只列 host 与 live；scope=all 连历史
  GET  /api/events?sid=&tail= | &since= | &before=
  GET  /api/image?id=
  GET  /api/pending                         Claude 的待审批与选择题
  POST /api/inject   {sid, text, force?, submit?}
  POST /api/keys     {sid, keys:[...]}
  POST /api/decision {id, decision, reason?, answers?}   转发Agents Island 桥（先应者赢）
  POST /api/inject 加 queue:true            电脑端正在输入时由中枢排队，输入框清空后自动发出（10 分钟过期）
                   加 clear:true            先清空电脑输入框里没发的字再发（电脑上 Ctrl+Y 可找回）；force:true 与草稿合并发出
  GET  /api/outbox                          排队中的消息及状态；POST /api/outbox/cancel {qid}
  POST /api/outbox/flush {qid, mode}        排队的这条马上发：mode=clear 先清空电脑草稿 / force 与草稿合并
  POST /api/keep_alive {sid, on}            关终端也不结束该会话
  POST /api/close {sid}                     关闭经宿主跑的会话（记录保留）
  POST /api/yolo {sid, on}                  会话级 YOLO（经Agents Island 桥）
  POST /api/usage/refresh {}                手动刷新使用率（无头探一次，60 秒限一次）
  POST /api/resume {sid, fork?}             续接历史会话（查活性）
  POST /api/new {cwd, model?, effort?, permission_mode?, prompt?}   新建会话（后台宿主）
  GET  /api/new_options                     新建页选项：各账号缺省模型 / 思考程度、可否选 Fable、缺省目录、最近目录
  GET  /api/recent_dirs                     新建会话可选的最近目录
  GET  /api/screen?sid=                     终端视图整屏快照（只对经宿主的会话；配合 /api/keys 作答）
  GET  /api/commands?sid=                   斜杠命令面板：内置 + 技能 + 自定义命令（complete.py）
  GET  /api/files?sid=&q=                   @ 文件补全：会话目录内的相对路径（只读，不读内容）
  POST /api/open_on_pc {sid}               在电脑上弹 Windows Terminal 执行 palm attach 接上该会话（仅经宿主的会话）
  GET  /api/link                            信箱链路状态（palm doctor 用）：最近取件距今秒数、收发计数
  GET  /api/stats?days=1|7|30               仪表盘：token / 工作时长 / 工具 / 会话排行 + 额度走势（usage.py）
"""
import argparse
import base64
import hmac
import json
import os
import re
import secrets
import shlex
import shutil
import signal
import socket
import sys
import threading
import subprocess
import time
import urllib.error
import uuid
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, str(Path(__file__).resolve().parent))
from transcript import ImageStore, MetaCache, Transcript  # noqa: E402
from complete import FileIndex, commands as list_commands  # noqa: E402
from usage import QuotaLog, UsageStats  # noqa: E402

from risk import classify as _classify, ttl as _approval_ttl  # noqa: E402

try:                                                   # 终端视图依赖 pyte；没装时其余功能照常
    from terminal import TermViews
except ImportError:                                    # pragma: no cover
    TermViews = None

VERSION = '0.1.0'
DEFAULT_PORT = 5612
HOST_PY = Path(__file__).resolve().parents[1] / 'host' / 'palm_host.py'
BG_MAX = 6                 # 后台宿主上限（RISKS R19）
QUEUE_TTL = 600.0          # 排队消息过期
QUEUE_RETRY = 1.5
NEW_MODES = ('default', 'acceptEdits', 'plan', 'auto', 'dontAsk')   # 手机不许开 bypassPermissions
EFFORTS = ('low', 'medium', 'high', 'xhigh', 'max')                  # claude --effort 的取值（v2.1.310 --help）
RL_DIR = Path(os.environ.get('PALM_RL_DIR') or '~/.agents-island').expanduser()   # 可选：Agents Island 状态栏钩子缓存的额度
RL_FILE = 'rl.json'
ACCOUNT = 'main'           # 单账号；接口仍带 account 字段，留作以后扩展
PC_ACTIVE_SECONDS = 120    # 宿主这么久内有本机按键 = 人在电脑前
PROBE_ARGV = ['claude', '-p', '回复一个字：好', '--model', 'haiku', '--output-format', 'stream-json', '--verbose',
              '--no-session-persistence']   # 刷新使用率：无头探一次（只在手机上人点时跑）
PROBE_COOLDOWN = 60
PROBE_TIMEOUT = 120
CLOSING_HIDE = 30         # 叫关之后这么多秒内不再列出该宿主（退出途中的空档）
BRIDGE_TTL = 1.5          # 桥状态缓存秒数（手机会频繁拉，桥每秒也在被岛拉）
BODY_MAX = 128 * 1024


def in_root(path: str, root: str) -> bool:
    """path 是否在 root 之内：按真实路径、按路径分隔符判断（~/demo 不收 ~/demo2）。"""
    if not path or not root:
        return False
    p = os.path.realpath(os.path.expanduser(path))
    return p == root or p.startswith(root + os.sep)


def account_label(config_dir: str) -> str:
    """单账号：一律 main（接口保留 account 字段）。"""
    return ACCOUNT


def dir_tag(path: str) -> str:
    """新建页目录类型标签：git 工作树（.git 是文件）、仓库（.git 是目录）、目录。"""
    g = os.path.join(path, '.git')
    if os.path.isfile(g):
        return '工作树'
    return '仓库' if os.path.exists(os.path.join(path, '.git')) else '目录'


def project_slug(cwd: str) -> str:
    return re.sub(r'[^A-Za-z0-9-]', '-', cwd or '')


def spawn_env(config_dir, palm_home, base=None) -> dict:
    """拉起会话用的环境：去掉本会话 / 宿主的痕迹；默认目录 ~/.claude 不设 CLAUDE_CONFIG_DIR（设了会改读写 .claude.json 的位置）。
    中枢跑在 systemd 下没有 TERM，不补的话后台会话整屏无颜色（真机实测：Logo 白块）；伪终端由 pyte 还原，固定 256 色 + 真彩色。"""
    env = {k: v for k, v in (os.environ if base is None else base).items()
           if not (k.startswith('CLAUDE_CODE_') or k in ('CLAUDECODE', 'PALM_HOST_ID', 'CLAUDE_CONFIG_DIR', 'NO_COLOR'))}
    env.update(TERM='xterm-256color', COLORTERM='truecolor')
    main_dir = str(Path('~/.claude').expanduser())
    if config_dir and str(Path(config_dir).expanduser()) != main_dir:
        env['CLAUDE_CONFIG_DIR'] = str(Path(config_dir).expanduser())
    env['PALM_HOME_DIR'] = str(palm_home)
    return env


def _cut_text(v, n):
    return v if not isinstance(v, str) or len(v) <= n else v[:n] + f'…（共 {len(v)} 字，已截断）'


def slim_approval(e: dict, now: float) -> dict:
    """手机审批卡用：人话摘要、风险、剩余时间，加差异预览要的参数、选择题、计划全文（都截断）。"""
    kind = e.get('kind') or 'perm'
    ti = e.get('tool_input') if isinstance(e.get('tool_input'), dict) else {}
    human, risk = _classify(e)
    arrived = e.get('_arrived')
    age = int(now - arrived) if isinstance(arrived, (int, float)) else None
    ttl = _approval_ttl(e)
    out = {'id': str(e['id']), 'kind': kind, 'tool': str(e.get('tool_name') or ''), 'sid': e.get('session_id') or '',
           'title': e.get('title') or '', 'project': e.get('project') or '', 'human': human, 'risk': risk,
           'age': age, 'ttl': ttl, 'expires_in': max(0, ttl - age) if age is not None else None,
           'input': {k: _cut_text(v, 4000) for k, v in list(ti.items())[:12] if k not in ('questions', 'plan')}}
    if kind == 'ask':
        qs = []
        for q in (ti.get('questions') or [])[:4]:
            if isinstance(q, dict):
                qs.append({'question': _cut_text(str(q.get('question') or ''), 1000), 'header': str(q.get('header') or ''),
                           'multiSelect': bool(q.get('multiSelect')),
                           'options': [{'label': str(o.get('label') or ''), 'description': _cut_text(str(o.get('description') or ''), 500)}
                                       for o in (q.get('options') or [])[:8] if isinstance(o, dict)]})
        out['questions'] = qs
    if kind == 'plan':
        out['plan'] = _cut_text(str(ti.get('plan') or ''), 8000)
    return out


def _alive(pid) -> bool:
    try:
        os.kill(int(pid), 0)
        return True
    except (OSError, ValueError, TypeError):
        return False


def _is_host_pid(pid) -> bool:
    """进程号确实是会话宿主（登记文件里的号可能已被别的进程复用）。"""
    try:
        return b'palm_host' in Path(f'/proc/{int(pid)}/cmdline').read_bytes()
    except (OSError, ValueError, TypeError):
        return False


def _read_json(p: Path):
    try:
        return json.loads(p.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return None


class Hub:
    def __init__(self, home=None, bridge=None, config_dirs=None):
        self.home = Path(home or os.environ.get('PALM_HOME_DIR') or '~/.palm').expanduser()
        self.run_dir = self.home / 'run'
        self.images = ImageStore(self.home / 'cache' / 'img')
        self.bridge = (bridge or os.environ.get('PALM_BRIDGE_URL') or 'http://127.0.0.1:5599').rstrip('/')
        # 单账号：CLAUDE_CONFIG_DIR，没设就是 ~/.claude（与 Claude Code 自己的规则一致）
        dirs = config_dirs or [os.environ.get('CLAUDE_CONFIG_DIR') or '~/.claude']
        self.config_dirs = [str(Path(d).expanduser()) for d in dirs[:1] if d]
        # 演示模式（拍截图 / 演示用）：只列工作目录在此目录下的会话、待办、目录与排行，真实工作内容不入镜
        demo = os.environ.get('PALM_DEMO_ROOT') or ''
        self.demo_root = os.path.realpath(os.path.expanduser(demo)) if demo else ''
        self.token = self._load_token()
        self._bridge_cache = (0.0, None)
        self._lock = threading.Lock()
        self.outbox = {}                               # qid → 排队消息
        self._ob_lock = threading.Lock()
        self._ob_thread = None
        self.termviews = TermViews() if TermViews else None
        self.file_index = FileIndex()
        self.meta = MetaCache()                        # 会话的模型与思考程度（对话页指标）
        self._launch = {}                              # 记录文件 → 启动目录
        self._closing = {}                             # 宿主号 → 叫关时间
        self._probes = {}                              # 账号 → 刷新使用率的状态 {running, at, error}
        self.link = None                               # 连信箱（main 里有配对配置才建）
        self.stats_db = UsageStats(self.config_dirs, account_label, self.home / 'cache' / 'usage.json')
        self.quota_log = QuotaLog(self.home / 'cache' / 'quota.jsonl')
        self._stats_thread = None

    # 令牌 ------------------------------------------------------------
    def _load_token(self) -> str:
        p = self.home / 'hub.token'
        try:
            t = p.read_text(encoding='utf-8').strip()
            if len(t) >= 32:
                return t
        except OSError:
            pass
        self.home.mkdir(parents=True, exist_ok=True)
        t = secrets.token_urlsafe(32)
        old = os.umask(0o177)
        try:
            p.write_text(t + '\n', encoding='utf-8')
        finally:
            os.umask(old)
        return t

    def check_token(self, value: str) -> bool:
        return bool(value) and hmac.compare_digest(value.encode(), self.token.encode())

    # 来源 ------------------------------------------------------------
    def bridge_state(self):
        with self._lock:
            at, st = self._bridge_cache
            if st is not None and time.time() - at < BRIDGE_TTL:
                return st
        try:
            with urllib.request.urlopen(f'{self.bridge}/api/state', timeout=2) as r:
                st = json.loads(r.read())
        except (OSError, ValueError, urllib.error.URLError):
            st = None
        with self._lock:
            self._bridge_cache = (time.time(), st)
        return st

    def hosts(self) -> list:
        out = []
        for p in sorted(self.run_dir.glob('*.json')) if self.run_dir.is_dir() else []:
            reg = _read_json(p)
            if not isinstance(reg, dict) or not _alive(reg.get('host_pid')) or not _alive(reg.get('child_pid')):
                continue
            if reg.get('id') in self._closing and time.time() - self._closing[reg['id']] < CLOSING_HIDE:
                continue                      # 刚叫关的：退出途中会话文件先删，别冒成“启动中”（真机实测）
            sf = _read_json(Path(reg.get('config_dir') or '') / 'sessions' / f"{reg.get('child_pid')}.json") or {}
            reg['session_id'] = sf.get('sessionId') or ''
            reg['cc_status'] = sf.get('status') or ''
            reg['cc_name'] = sf.get('name') or ''
            out.append(reg)
        return out

    def live_session_files(self) -> list:
        """桥不在时的兜底：sessions/*.json 里进程还活着的。"""
        out = []
        for cd in self.config_dirs:
            for p in (Path(cd) / 'sessions').glob('*.json'):
                d = _read_json(p)
                if isinstance(d, dict) and d.get('sessionId') and _alive(d.get('pid')):
                    d['config_dir'] = cd
                    out.append(d)
        return out

    # 合并 ------------------------------------------------------------
    def sessions(self, scope='live') -> list:
        rows = {}
        st = self.bridge_state()
        if st:
            for s in (st.get('sessions') or {}).get('claude') or []:
                sid = s.get('session_id')
                if not sid:
                    continue
                cdir = s.get('config_dir') or self.config_dir_of_file(s.get('file') or '')   # 桥只给在跑会话报目录
                s = dict(s, config_dir=cdir or '')
                rows[sid] = {
                    'sid': sid, 'title': s.get('title') or s.get('last_prompt') or '', 'cwd': s.get('cwd') or '',
                    'account': account_label(s.get('config_dir') or ''), 'config_dir': s.get('config_dir') or '',
                    'status': s.get('status') or '', 'last_tool': s.get('last_tool') or '',
                    'age_seconds': s.get('age_seconds'), 'context_pct': s.get('context_pct'),
                    'file': s.get('file') or '', 'pid': str(s.get('pid') or ''), 'kind': s.get('kind') or '',
                    'control': 'live' if s.get('is_live') else 'history', 'host_id': '',
                }
        else:
            for d in self.live_session_files():
                sid = d['sessionId']
                rows[sid] = {'sid': sid, 'title': d.get('name') or '', 'cwd': d.get('cwd') or '',
                             'account': account_label(d['config_dir']), 'config_dir': d['config_dir'],
                             'status': d.get('status') or '', 'pid': str(d.get('pid') or ''), 'kind': d.get('kind') or '',
                             'file': '', 'control': 'live', 'host_id': ''}
        hosts = self.hosts()
        # 幽灵条目：桥按进程号认会话活着；宿主外壳里先前跑过的旧会话，登记的进程号是外壳本身，
        # 外壳还在（里面换成了别的会话）它们就一直显示“在跑 · 只能看”。外壳里现在不是它 → 归到历史（仍可续接）
        shell_now = {str(h.get('host_pid')): h['session_id'] for h in hosts}
        for sid, row in rows.items():
            if row.get('control') == 'live' and row.get('pid') in shell_now and shell_now[row['pid']] != sid:
                row['control'] = 'history'
        for h in hosts:
            sid = h['session_id']
            if not sid:
                # 还没写登记文件：多半停在“信任此目录”等启动对话框（默认选项是“否，退出”）。
                # 用 host:<宿主号> 列出来，手机在终端视图里作答（10-26 实测）
                rows[f"host:{h['id']}"] = {
                    'sid': f"host:{h['id']}", 'title': h.get('name') or '启动中（可能在等确认）', 'cwd': h.get('cwd') or '',
                    'account': account_label(h.get('config_dir') or ''), 'config_dir': h.get('config_dir') or '',
                    'status': 'starting', 'pid': str(h.get('child_pid')), 'file': '', 'control': 'host',
                    'host_id': h['id'], 'age_seconds': 0}
                continue
            row = rows.get(sid) or {'sid': sid, 'title': h.get('cc_name') or '', 'cwd': h.get('cwd') or '',
                                    'account': account_label(h.get('config_dir') or ''), 'config_dir': h.get('config_dir') or '',
                                    'status': h.get('cc_status') or '', 'pid': str(h.get('child_pid')), 'file': ''}
            row.update(control='host', host_id=h['id'], host_status=h.get('cc_status') or '',
                       keep_alive=bool(h.get('keep_alive')), attached=bool(h.get('attached')))
            rows[sid] = row
        out = list(rows.values())
        # 会话目录以启动目录为准：记录里每条消息的 cwd 跟着命令里的 cd 变，桥取最新一条；
        # 中途 cd 进别处后，手机显示那个临时目录，续接也在那里拉起，CLI 问“是否信任此目录”
        for r in out:
            if r['sid'].startswith('host:'):
                continue
            launch = self.launch_dir(self.transcript_path(r))
            if launch:
                r['cwd'] = launch
        if scope != 'all':
            out = [r for r in out if r['control'] in ('host', 'live')]
        for r in out:                                   # 在跑的会话带模型与思考程度（只在记录增长时重读）
            if r['control'] != 'history' and not r['sid'].startswith('host:'):
                r.update(self.meta.get(self.transcript_path(r)))
        yolo = set((st or {}).get('yolo_sessions') or [])     # Agents Island 桥的会话级 YOLO（手机长按面板与桌面岛同一开关）
        for r in out:
            r['yolo'] = r['sid'] in yolo
        order = {'host': 0, 'live': 1, 'history': 2}
        out.sort(key=lambda r: (order.get(r['control'], 3), r.get('age_seconds') or 0))
        if self.demo_root:
            out = [r for r in out if in_root(r.get('cwd') or '', self.demo_root)]
        return out

    def launch_dir(self, path) -> str:
        """会话记录里第一条带 cwd 的消息 = 启动目录；只读文件开头，按路径缓存（启动目录不会变）。"""
        key = str(path)
        if key in self._launch:
            return self._launch[key]
        found = ''
        try:
            with open(path, 'rb') as f:
                head = f.read(256 * 1024)
            for raw in head.split(b'\n'):
                if b'"cwd"' not in raw:
                    continue
                try:
                    found = str(json.loads(raw).get('cwd') or '')
                except ValueError:
                    continue
                if found:
                    break
        except OSError:
            return ''                                  # 记录还没写出来：下次再读，不缓存
        if found and not Path(found).is_dir():
            found = ''
        self._launch[key] = found
        return found

    def config_dir_of_file(self, path: str):
        """记录文件在哪个账号目录的 projects/ 下，就是哪个账号。"""
        rp = os.path.realpath(path) if path else ''
        for cd in self.config_dirs:
            base = os.path.realpath(cd) + os.sep + 'projects' + os.sep
            if rp.startswith(base):
                return cd
        return None

    def find(self, sid: str):
        return next((r for r in self.sessions('all') if r['sid'] == sid), None)

    def transcript_path(self, row) -> Path:
        if row.get('file') and Path(row['file']).is_file():
            return Path(row['file'])
        cd = Path(row.get('config_dir') or '~/.claude').expanduser()
        guess = cd / 'projects' / project_slug(row.get('cwd') or '') / f"{row['sid']}.jsonl"
        if guess.is_file():
            return guess
        hit = next(iter((cd / 'projects').glob(f"*/{row['sid']}.jsonl")), None)
        return hit or guess

    def events(self, sid, tail=200, since=None, before=None) -> dict:
        row = self.find(str(sid or ''))
        if not row:
            return {'ok': False, 'reason': 'no_session'}
        path = self.transcript_path(row)
        if not path.is_file():
            return {'ok': True, 'sid': row['sid'], 'events': [], 'cursor': 0, 'size': 0}
        try:
            kw = {'tail': max(1, min(int(tail), 1000))}
            if since is not None:
                kw['since'] = int(since)
            elif before is not None:
                kw['before'] = int(before)
        except (TypeError, ValueError):
            return {'ok': False, 'reason': 'bad_cursor'}
        return {'ok': True, 'sid': row['sid'], **Transcript(path, self.images).read(**kw)}

    def _probe_file(self, acct: str = ACCOUNT) -> Path:
        return self.home / 'cache' / 'rl-probe.json'

    def usage(self) -> dict:
        """各账号 5 小时 / 7 天额度；at = 数据时间，太旧的由手机标灰。来源两份取较新的：
        Agents Island状态栏钩子写的缓存（该账号有会话刷状态栏时才写），与手机“刷新使用率”无头探一次的结果（中枢自己的缓存）。"""
        out = {}
        for acct, name in ((ACCOUNT, RL_FILE),):
            cands = []
            for p in (RL_DIR / name, self._probe_file(acct)):
                d = _read_json(p)
                if isinstance(d, dict):
                    cands.append((p.stat().st_mtime, d))
            if not cands:
                continue
            mtime, d = max(cands, key=lambda x: x[0])
            row = {'at': int(mtime)}
            now = time.time()
            for k, short in (('five_hour', 'h5'), ('seven_day', 'd7')):
                w = d.get(k) if isinstance(d.get(k), dict) else {}
                if w.get('used_percentage') is None:
                    continue
                r = w.get('resets_at')
                # 缓存只在该账号有会话刷状态栏时写（长时间没开交互会话就会停在旧数）。重置时间已过：旧数作废，
                # 但也不能当 0——无头会话（claude -p）照样在用、却不刷状态栏。
                # 报未知（pct None + reset），手机显示“待刷新”，长按额度卡实测
                if isinstance(r, (int, float)) and r <= now:
                    row[short] = {'pct': None, 'resets_at': None, 'reset': True}
                else:
                    row[short] = {'pct': w.get('used_percentage'), 'resets_at': r}
            out[acct] = row
        return out

    def refresh_usage(self, account: str, wait=False) -> dict:
        """手机长按额度卡“刷新使用率”：后台起一次无头会话（haiku、一个字、不留记录），读实时 5h / 7d。
        只在人点的时候跑、每个账号 60 秒最多一次——订阅制 CLI 禁止定时探测（曾致账号停用 CLI），这里不做任何定时。"""
        account = ACCOUNT
        cd = self.config_dirs[0]
        with self._lock:
            st = self._probes.setdefault(account, {'running': False, 'at': 0.0, 'error': ''})
            if st['running']:
                return {'ok': True, 'running': True}
            left = PROBE_COOLDOWN - (time.time() - st['at'])
            if left > 0:
                return {'ok': False, 'reason': 'cooldown', 'wait_s': int(left) + 1}
            st.update(running=True, at=time.time(), error='')
        t = threading.Thread(target=self._probe, args=(account, cd), daemon=True)
        t.start()
        if wait:
            t.join()
        return {'ok': True, 'running': True}

    def _probe(self, account: str, cd: str):
        err = 'no_rate_limit_event'
        try:
            work = self.home / 'cache' / 'probe'
            work.mkdir(parents=True, exist_ok=True)
            r = subprocess.run(PROBE_ARGV, cwd=work, env=spawn_env(cd, self.home), stdin=subprocess.DEVNULL,
                               capture_output=True, text=True, timeout=PROBE_TIMEOUT)
            for line in r.stdout.splitlines():
                try:
                    ev = json.loads(line)
                except ValueError:
                    continue
                win = ((ev.get('rate_limit_info') or {}).get('unifiedWindows') or {}) if ev.get('type') == 'rate_limit_event' else {}
                if not win:
                    continue
                out = {}
                for k in ('five_hour', 'seven_day'):
                    w = win.get(k) or {}
                    if w.get('utilization') is not None:
                        out[k] = {'used_percentage': int(float(w['utilization']) * 100 + 0.5), 'resets_at': w.get('resetsAt')}
                if out:
                    f = self._probe_file(account)
                    tmp = f.with_suffix('.tmp')
                    tmp.write_text(json.dumps(out), encoding='utf-8')
                    os.replace(tmp, f)
                    err = ''
                    break
        except subprocess.TimeoutExpired:
            err = 'timeout'
        except (OSError, ValueError) as e:
            err = f'probe_failed: {e}'
        with self._lock:
            self._probes[account].update(running=False, error=err)

    def probe_state(self) -> dict:
        with self._lock:
            return {k: dict(v) for k, v in self._probes.items()}

    # 仪表盘 ------------------------------------------------------------
    def start_stats(self, every=60):
        """后台线程：先把近 30 天记录扫一遍（约 4.6GB、半分钟），之后每分钟增量 + 记一次额度。"""
        def loop():
            while True:
                try:
                    self.stats_db.refresh()
                    self.quota_log.sample(self.usage())
                except Exception as e:                 # 统计出错不影响会话功能
                    print(f'用量统计出错：{e}', flush=True)
                time.sleep(every)
        self._stats_thread = threading.Thread(target=loop, daemon=True)
        self._stats_thread.start()

    def stats(self, days=7) -> dict:
        try:
            days = min(max(int(days), 1), 30)
        except (TypeError, ValueError):
            days = 7
        db = self.stats_db
        if self._stats_thread is None:                 # 没开后台线程（测试、单跑）：当场扫
            db.refresh()
        elif not db.ready:
            done, total = db.progress
            return {'ok': True, 'ready': False, 'progress': round(done / total, 2) if total else 0}
        elif time.time() - db.last_refresh > 10:
            db.refresh(blocking=False)                 # 增量很快（零点几秒）；后台正在扫就用现有结果
        out = db.summary(days)
        quota = self.usage()
        self.quota_log.sample(quota)                   # 十分钟内只记一次
        since = time.time() - min(days, 7) * 86400
        trend = self.quota_log.read(since)
        step = max(1, len(trend) // 168)               # 最多 168 个点，单个信封装得下
        out.update(ok=True, quota=quota, trend=trend[::step])
        if self.demo_root and isinstance(out.get('sessions'), list):
            out['sessions'] = [r for r in out['sessions'] if in_root(r.get('cwd') or '', self.demo_root)]
        return out

    def pc_active(self) -> bool:
        """任一宿主在 PC_ACTIVE_SECONDS 秒内有本机按键：手机不响提醒。"""
        for h in self.hosts():
            st = self.host_call(h['session_id'] or f"host:{h['id']}", {'op': 'status'})
            age = (st.get('local') or {}).get('last_key_age')
            if isinstance(age, (int, float)) and age < PC_ACTIVE_SECONDS:
                return True
        return False

    def pending(self) -> list:
        """Claude 的待审批 / 选择题 / 计划审阅（来源看 agent_source；只管 Claude）。"""
        st = self.bridge_state() or {}
        now = time.time()
        rows = [e for e in st.get('pending') or []
                if isinstance(e, dict) and e.get('id')
                and str(e.get('agent_source') or 'claude').lower() == 'claude']
        if self.demo_root:
            sids = {r['sid'] for r in self.sessions('all')}
            rows = [e for e in rows if e.get('session_id') in sids]
        return [slim_approval(e, now) for e in rows]

    # 动作 ------------------------------------------------------------
    def host_of(self, sid: str):
        """会话号 → 宿主；也认 host:<宿主号>（还没拿到会话号的启动中会话）。"""
        hosts = self.hosts()
        if sid.startswith('host:'):
            return next((x for x in hosts if x['id'] == sid[5:]), None)
        return next((x for x in hosts if x['session_id'] == sid), None)

    def host_call(self, sid: str, req: dict) -> dict:
        h = self.host_of(sid)
        if not h:
            return {'ok': False, 'reason': 'not_hosted'}
        try:
            c = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            c.settimeout(3)
            c.connect(h['sock'])
            c.sendall(json.dumps(req, ensure_ascii=False).encode() + b'\n')
            buf = b''
            while b'\n' not in buf:
                chunk = c.recv(65536)
                if not chunk:
                    break
                buf += chunk
            c.close()
            return json.loads(buf.split(b'\n', 1)[0])
        except (OSError, ValueError) as e:
            return {'ok': False, 'reason': f'host_unreachable: {e}'}

    # 排队（电脑端正在输入时）--------------------------------
    def _inject_clear(self, sid: str, req: dict) -> dict:
        """先清空电脑草稿再发。新宿主认 clear；10-27 之前起的旧宿主不认（照样回 local_draft）——
        退一步用“接入端输入”送清空键（旧宿主收到 Ctrl+U 会把草稿计数归零），再正常注入。"""
        r = self.host_call(sid, dict(req, clear=True, force=False))
        if r.get('reason') in ('local_typing', 'local_draft'):
            n = int(r.get('draft_chars') or 0)
            k = min(200, max(8, n // 20 + 8))
            seq = base64.b64encode(b'\x15' * k + b'\x0b' * k).decode()
            if self.host_call(sid, {'op': 'input', 'b64': seq}).get('ok'):
                r = self.host_call(sid, dict(req, clear=False, force=False))
                if r.get('ok'):
                    r = dict(r, cleared=n)
        return r

    def inject(self, body: dict) -> dict:
        sid = str(body.get('sid') or '')
        req = {'op': 'inject', 'text': str(body.get('text') or ''), 'force': bool(body.get('force')),
               'clear': False, 'submit': body.get('submit', True) is not False}
        if body.get('clear'):
            return self._inject_clear(sid, req)
        r = self.host_call(sid, req)
        if r.get('reason') in ('local_typing', 'local_draft') and body.get('queue'):
            qid = uuid.uuid4().hex[:10]
            with self._ob_lock:
                self.outbox[qid] = {'qid': qid, 'sid': sid, 'req': req, 'state': 'waiting', 'created': time.time(),
                                    'reason': r['reason']}
            self._ensure_outbox_thread()
            return {'ok': False, 'queued': True, 'qid': qid, 'reason': r['reason'], 'draft_chars': r.get('draft_chars'),
                    'idle_s': r.get('idle_s')}
        return r

    def _ensure_outbox_thread(self):
        if self._ob_thread and self._ob_thread.is_alive():
            return
        self._ob_thread = threading.Thread(target=self._outbox_loop, daemon=True)
        self._ob_thread.start()

    def _outbox_loop(self):
        while True:
            time.sleep(QUEUE_RETRY)
            with self._ob_lock:
                todo = [m for m in self.outbox.values() if m['state'] == 'waiting']
            if not todo:
                return
            for m in todo:
                if time.time() - m['created'] > QUEUE_TTL:
                    m['state'] = 'expired'
                    continue
                r = self.host_call(m['sid'], m['req'])
                if r.get('ok'):
                    m['state'], m['sent_at'] = 'sent', time.time()
                elif r.get('reason') in ('local_typing', 'local_draft'):
                    m['reason'] = r['reason']
                else:
                    m['state'], m['reason'] = 'failed', r.get('reason')
            with self._ob_lock:                        # 结束超过 10 分钟的记录清掉
                for k in [k for k, m in self.outbox.items() if m['state'] != 'waiting'
                          and time.time() - m['created'] > QUEUE_TTL * 2]:
                    self.outbox.pop(k, None)

    def outbox_list(self) -> list:
        with self._ob_lock:
            return [{k: v for k, v in m.items() if k != 'req'} | {'text': m['req']['text'][:200]}
                    for m in self.outbox.values()]

    def outbox_flush(self, qid: str, mode: str) -> dict:
        """排队的这条马上发：mode=clear 先清空电脑输入框里没发的字（电脑上 Ctrl+Y 可找回），mode=force 与草稿合并发出。
        实际遇到过：出门前电脑输入框留了字，手机消息一直排队、会话进不去。"""
        if mode not in ('clear', 'force'):
            return {'ok': False, 'reason': 'bad_mode'}
        with self._ob_lock:
            m = self.outbox.get(qid)
            if not m or m['state'] != 'waiting':
                return {'ok': False, 'reason': 'not_waiting'}
            m['state'] = 'sending'
        r = self._inject_clear(m['sid'], m['req']) if mode == 'clear' else self.host_call(m['sid'], dict(m['req'], force=True))
        with self._ob_lock:
            if r.get('ok'):
                m['state'], m['sent_at'] = 'sent', time.time()
            else:
                m['state'], m['reason'] = 'waiting', r.get('reason')
        return r

    def outbox_cancel(self, qid: str) -> dict:
        with self._ob_lock:
            m = self.outbox.get(qid)
            if not m or m['state'] != 'waiting':
                return {'ok': False, 'reason': 'not_waiting'}
            m['state'] = 'cancelled'
        return {'ok': True}

    # 续接与新建----------------------------------------------
    def config_dir_of(self, account: str = ACCOUNT):
        return self.config_dirs[0] if self.config_dirs else None

    def _bg_count(self) -> int:
        return sum(1 for h in self.hosts() if h.get('detached'))

    def _spawn(self, argv, cwd, config_dir, name) -> dict:
        if self._bg_count() >= BG_MAX:
            return {'ok': False, 'reason': 'too_many_background', 'limit': BG_MAX}
        hid = uuid.uuid4().hex[:12]
        env = spawn_env(config_dir, self.home)
        try:
            subprocess.Popen([sys.executable, str(HOST_PY), '--detached', '--id', hid, '--size', '40x120',
                              '--name', name[:60], '--', *argv],
                             cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, start_new_session=True)
        except OSError as e:
            return {'ok': False, 'reason': f'spawn_failed: {e}'}
        reg = self.run_dir / f'{hid}.json'
        for _ in range(50):
            if reg.exists():
                return {'ok': True, 'host_id': hid}
            time.sleep(0.1)
        return {'ok': False, 'reason': 'host_not_started', 'host_id': hid}

    def resume(self, body: dict) -> dict:
        sid, fork = str(body.get('sid') or ''), bool(body.get('fork'))
        if not re.fullmatch(r'[0-9a-fA-F-]{8,64}', sid):
            return {'ok': False, 'reason': 'bad_sid'}
        row = self.find(sid)
        if not row:
            return {'ok': False, 'reason': 'no_session'}
        if not fork:
            live_ids = {d.get('sessionId') for d in self.live_session_files()}
            if row['control'] in ('host', 'live') or sid in live_ids:
                return {'ok': False, 'reason': 'session_live', 'hint': '到该会话里直接操作，或分叉续接'}
        cwd = row.get('cwd') if row.get('cwd') and Path(row['cwd']).is_dir() else str(Path.home())
        argv = ['claude', '--resume', sid] + (['--fork-session'] if fork else [])
        return self._spawn(argv, cwd, row.get('config_dir'), f"续接 {row.get('title') or sid[:8]}")

    def new_session(self, body: dict) -> dict:
        config_dir = self.config_dir_of()
        cwd = str(body.get('cwd') or '')
        home = str(Path.home())
        real = os.path.realpath(cwd) if cwd else ''
        if not (real and os.path.isdir(real) and (real == home or real.startswith(home + os.sep))):
            return {'ok': False, 'reason': 'bad_cwd'}
        argv = ['claude']
        model = str(body.get('model') or '')
        if model:
            if not re.fullmatch(r'[A-Za-z0-9.\-\[\]]{1,40}', model):
                return {'ok': False, 'reason': 'bad_model'}
            argv += ['--model', model]
        effort = str(body.get('effort') or '')
        if effort:
            if effort not in EFFORTS:
                return {'ok': False, 'reason': 'bad_effort'}
            argv += ['--effort', effort]
        mode = str(body.get('permission_mode') or '')
        if mode:
            if mode not in NEW_MODES:
                return {'ok': False, 'reason': 'bad_permission_mode'}
            argv += ['--permission-mode', mode]
        prompt = str(body.get('prompt') or '')
        if prompt:
            if len(prompt) > 20000:
                return {'ok': False, 'reason': 'prompt_too_long'}
            argv += ['--', prompt]
        return self._spawn(argv, real, config_dir, f'手机新建 {Path(real).name}')

    def new_options(self) -> dict:
        """手机新建页：“默认”写明是什么（settings.json 的 model / effortLevel）。"""
        accounts = []
        for cd in self.config_dirs:
            st = _read_json(Path(cd) / 'settings.json')
            st = st if isinstance(st, dict) else {}
            accounts.append({'account': ACCOUNT, 'model': str(st.get('model') or ''), 'effort': str(st.get('effortLevel') or ''),
                             'fable': True})
        d = Path(os.environ.get('PALM_DEFAULT_DIR') or self.demo_root or Path.home()).expanduser()
        default = str(d) if d.is_dir() else str(Path.home())
        dirs = self.recent_dirs()
        return {'ok': True, 'accounts': accounts, 'efforts': list(EFFORTS), 'default_dir': default, 'dirs': dirs,
                'dir_tags': {x: dir_tag(x) for x in [default, *dirs]}}

    def recent_dirs(self, limit=15) -> list:
        seen, out = set(), []
        home = str(Path.home())
        rows = sorted(self.sessions('all'), key=lambda r: r.get('age_seconds') or 0)
        for r in rows:
            d = r.get('cwd') or ''
            real = os.path.realpath(d) if d else ''
            if not (real == home or real.startswith(home + os.sep)):
                continue                                   # 新建只允许家目录下（/tmp 的临时目录列出来也会被拒，真机实测）
            if d and d not in seen and os.path.isdir(d):
                seen.add(d)
                out.append(d)
            if len(out) >= limit:
                break
        return out

    # 在电脑上打开（手机上开的会话，回到电脑想接着用，原来只能自己敲 palm attach）--------
    @staticmethod
    def _win_env() -> dict:
        """后台服务没有 WSL 互操作变量，调不动 Windows 程序：取最新的互操作套接字补上。"""
        env = dict(os.environ)
        if not env.get('WSL_INTEROP'):
            socks = sorted(Path('/run/WSL').glob('*_interop'), key=lambda p: p.stat().st_mtime, reverse=True) \
                if Path('/run/WSL').is_dir() else []
            if socks:
                env['WSL_INTEROP'] = str(socks[0])
        return env

    @staticmethod
    def _distro() -> str:
        name = os.environ.get('WSL_DISTRO_NAME') or ''
        if not name:
            try:
                out = subprocess.run(['wslpath', '-w', '/'], capture_output=True, text=True, timeout=5).stdout.strip()
                name = out.strip('\\').split('\\')[-1] if out.startswith('\\\\') else ''
            except (OSError, subprocess.SubprocessError):
                name = ''
        return name

    @staticmethod
    def _find_wt():
        users = Path('/mnt/c/Users')
        return next(iter(sorted(users.glob('*/AppData/Local/Microsoft/WindowsApps/wt.exe'))), None) if users.is_dir() else None

    def close(self, sid: str) -> dict:
        """关闭经宿主跑的会话（长按会话卡就能关）：给宿主发 SIGTERM，宿主转给会话进程，
        会话退出后宿主随之退出。“保持后台”只挡关终端的 SIGHUP，挡不住这个。记录留着，历史里可续接。"""
        h = self.host_of(str(sid or ''))
        if not h or not _is_host_pid(h.get('host_pid')):
            return {'ok': False, 'reason': 'not_hosted'}
        try:
            os.kill(int(h['host_pid']), signal.SIGTERM)
        except OSError as e:
            return {'ok': False, 'reason': f'kill_failed: {e}'}
        self._closing[h['id']] = time.time()
        return {'ok': True, 'host_id': h['id']}

    @staticmethod
    def _is_wsl() -> bool:
        try:
            return 'microsoft' in Path('/proc/version').read_text().lower()
        except OSError:
            return False

    def _terminal_argv(self, title: str, cmd: str):
        """按平台给出“开一个终端窗口执行 cmd”的命令；找不到可用终端返回 None。
        PALM_TERMINAL 可自定义，如 "gnome-terminal -- bash -lc {cmd}"（{cmd} 会替换成带引号的命令）。"""
        custom = os.environ.get('PALM_TERMINAL') or ''
        if custom:
            return [x.replace('{cmd}', cmd) for x in shlex.split(custom)]
        if self._is_wsl():
            wt = self._find_wt()
            if not wt:
                return None
            distro = self._distro()
            return [str(wt), '-w', 'new', '--title', title, 'wsl.exe'] + (['-d', distro] if distro else []) + \
                ['--cd', '~', '--', 'bash', '-lic', cmd]
        if sys.platform == 'darwin':
            script = 'tell application "Terminal" to do script "' + cmd.replace('\\', '\\\\').replace('"', '\\"') + '"'
            return ['osascript', '-e', script, '-e', 'tell application "Terminal" to activate']
        if not (os.environ.get('DISPLAY') or os.environ.get('WAYLAND_DISPLAY')):
            return None                                # 没有图形会话（如 systemd 服务没继承到）：交给用户手动执行
        for term, pre in (('x-terminal-emulator', ['-e']), ('gnome-terminal', ['--']), ('konsole', ['-e']),
                          ('xfce4-terminal', ['-x']), ('kitty', []), ('alacritty', ['-e']), ('xterm', ['-e'])):
            if shutil.which(term):
                return [term, *pre, 'bash', '-lc', cmd]
        return None

    def open_on_pc(self, sid: str) -> dict:
        """在电脑上弹一个终端窗口，执行 palm attach 接上这个会话；关窗口只是断开，会话照常在后台跑。"""
        row = self.find(str(sid or ''))
        if not row:
            return {'ok': False, 'reason': 'no_session'}
        hid = row.get('host_id') or ''
        if not hid or not re.fullmatch(r'[0-9a-f]{6,32}', hid):
            return {'ok': False, 'reason': 'not_hosted'}
        cmd = f'palm attach {hid}'
        title = re.sub(r'[;"\\]', ' ', (row.get('title') or '会话'))[:40]     # wt 用分号分隔子命令，标题里去掉
        argv = self._terminal_argv(f'Claude Inspire · {title}', cmd)
        if not argv:
            return {'ok': False, 'reason': 'no_terminal', 'command': cmd}       # 手机上显示命令，让用户自己在电脑上执行
        try:
            subprocess.Popen(argv, cwd='/mnt/c' if self._is_wsl() and Path('/mnt/c').is_dir() else None,
                             env=self._win_env() if self._is_wsl() else None,
                             stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             start_new_session=True)
        except OSError as e:
            return {'ok': False, 'reason': f'launch_failed: {e}', 'command': cmd}
        return {'ok': True, 'host_id': hid}

    def screen(self, sid: str) -> dict:
        if not self.termviews:
            return {'ok': False, 'reason': 'pyte_missing'}
        h = self.host_of(sid)
        if not h:
            return {'ok': False, 'reason': 'not_hosted'}
        return {'ok': True, **self.termviews.get(h).snapshot()}

    def commands(self, sid: str) -> dict:
        row = self.find(sid)
        if not row:
            return {'ok': False, 'reason': 'no_session'}
        return {'ok': True, 'items': list_commands(row.get('cwd') or '', row.get('config_dir') or '')}

    def files(self, sid: str, q: str) -> dict:
        row = self.find(sid)
        if not row:
            return {'ok': False, 'reason': 'no_session'}
        return {'ok': True, 'items': self.file_index.search(row.get('cwd') or '', str(q or '')[:100])}

    def yolo(self, sid: str, on: bool) -> dict:
        """会话级 YOLO：交给Agents Island 桥——它对该会话的工具审批秒放行，选择题 / 计划照常问。"""
        if not self.find(sid):
            return {'ok': False, 'reason': 'no_session'}
        req = urllib.request.Request(f'{self.bridge}/api/session_yolo', data=json.dumps({'session_id': sid, 'on': bool(on)}).encode(),
                                     headers={'Content-Type': 'application/json'}, method='POST')
        try:
            with urllib.request.urlopen(req, timeout=3) as r:
                json.loads(r.read() or b'{}')
        except (OSError, ValueError) as e:
            return {'ok': False, 'reason': f'bridge_unreachable: {e}'}
        with self._lock:
            self._bridge_cache = (0.0, None)           # 下一次总览立刻反映开关
        return {'ok': True, 'yolo': bool(on)}

    def decision(self, body: dict) -> dict:
        payload = {k: body[k] for k in ('id', 'decision', 'reason', 'answers') if k in body}
        if payload.get('decision') not in ('allow', 'deny', 'always') or not payload.get('id'):
            return {'ok': False, 'reason': 'bad_decision'}
        req = urllib.request.Request(f'{self.bridge}/api/decision', data=json.dumps(payload).encode(),
                                     headers={'Content-Type': 'application/json'}, method='POST')
        try:
            with urllib.request.urlopen(req, timeout=3) as r:
                return json.loads(r.read() or b'{}') | {'ok': True}
        except urllib.error.HTTPError as e:
            return {'ok': False, 'reason': f'bridge_{e.code}'}
        except (OSError, ValueError) as e:
            return {'ok': False, 'reason': f'bridge_unreachable: {e}'}


def make_handler(hub: Hub):
    class H(BaseHTTPRequestHandler):
        server_version = 'palm-hub'

        def log_message(self, *a):
            pass

        def _json(self, obj, code=200):
            body = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authed(self) -> bool:
            if hub.check_token(self.headers.get('X-Palm-Token') or ''):
                return True
            self._json({'ok': False, 'reason': 'unauthorized'}, 401)
            return False

        def do_GET(self):
            u = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(u.query).items()}
            if u.path == '/api/health':
                return self._json({'ok': True, 'version': VERSION})
            if not self._authed():
                return
            if u.path == '/api/sessions':
                return self._json({'ok': True, 'sessions': hub.sessions(q.get('scope', 'live'))})
            if u.path == '/api/pending':
                return self._json({'ok': True, 'pending': hub.pending()})
            if u.path == '/api/outbox':
                return self._json({'ok': True, 'outbox': hub.outbox_list()})
            if u.path == '/api/recent_dirs':
                return self._json({'ok': True, 'dirs': hub.recent_dirs()})
            if u.path == '/api/new_options':
                return self._json(hub.new_options())
            if u.path == '/api/screen':
                return self._json(hub.screen(q.get('sid', '')))
            if u.path == '/api/commands':
                return self._json(hub.commands(q.get('sid', '')))
            if u.path == '/api/files':
                return self._json(hub.files(q.get('sid', ''), q.get('q', '')))
            if u.path == '/api/stats':
                return self._json(hub.stats(q.get('days', 7)))
            if u.path == '/api/link':
                return self._json({'ok': True, 'paired': hub.link is not None, **(hub.link.status() if hub.link else {})})
            if u.path == '/api/events':
                r = hub.events(q.get('sid', ''), q.get('tail', 200), q.get('since'), q.get('before'))
                return self._json(r, 200 if r.get('ok') else 404)
            if u.path == '/api/image':
                p = hub.images.find(q.get('id', ''))
                if not p:
                    return self._json({'ok': False, 'reason': 'no_image'}, 404)
                data = p.read_bytes()
                self.send_response(200)
                self.send_header('Content-Type', {'png': 'image/png', 'jpg': 'image/jpeg', 'gif': 'image/gif',
                                                  'webp': 'image/webp'}.get(p.suffix[1:], 'application/octet-stream'))
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            self._json({'ok': False, 'reason': 'not_found'}, 404)

        def do_POST(self):
            if not self._authed():
                return
            n = int(self.headers.get('Content-Length') or 0)
            if n > BODY_MAX:
                return self._json({'ok': False, 'reason': 'too_large'}, 413)
            try:
                body = json.loads(self.rfile.read(n) or b'{}')
                assert isinstance(body, dict)
            except (ValueError, AssertionError):
                return self._json({'ok': False, 'reason': 'bad_json'}, 400)
            if self.path == '/api/inject':
                return self._json(hub.inject(body))
            if self.path == '/api/usage/refresh':
                return self._json(hub.refresh_usage(str(body.get('account') or '')))
            if self.path == '/api/yolo':
                return self._json(hub.yolo(str(body.get('sid') or ''), bool(body.get('on'))))
            if self.path == '/api/close':
                return self._json(hub.close(str(body.get('sid') or '')))
            if self.path == '/api/open_on_pc':
                return self._json(hub.open_on_pc(str(body.get('sid') or '')))
            if self.path == '/api/outbox/flush':
                return self._json(hub.outbox_flush(str(body.get('qid') or ''), str(body.get('mode') or '')))
            if self.path == '/api/outbox/cancel':
                return self._json(hub.outbox_cancel(str(body.get('qid') or '')))
            if self.path == '/api/keep_alive':
                return self._json(hub.host_call(str(body.get('sid') or ''), {'op': 'keep_alive', 'on': bool(body.get('on'))}))
            if self.path == '/api/resume':
                return self._json(hub.resume(body))
            if self.path == '/api/new':
                return self._json(hub.new_session(body))
            if self.path == '/api/keys':
                keys = body.get('keys') if isinstance(body.get('keys'), list) else []
                return self._json(hub.host_call(str(body.get('sid') or ''), {'op': 'keys', 'keys': keys[:32]}))
            if self.path == '/api/decision':
                return self._json(hub.decision(body))
            self._json({'ok': False, 'reason': 'not_found'}, 404)

    return H


def make_server(hub: Hub, host='127.0.0.1', port=DEFAULT_PORT) -> ThreadingHTTPServer:
    srv = ThreadingHTTPServer((host, port), make_handler(hub))
    srv.daemon_threads = True
    return srv


def main():
    ap = argparse.ArgumentParser(description='掌上灵感 · 会话中枢')
    ap.add_argument('--port', type=int, default=int(os.environ.get('PALM_HUB_PORT') or DEFAULT_PORT))
    args = ap.parse_args()
    hub = Hub()
    hub.start_stats()
    srv = make_server(hub, '127.0.0.1', args.port)
    print(f'palm-hub {VERSION} 监听 127.0.0.1:{args.port}', flush=True)
    devices = hub.home / 'mailbox.json'
    if devices.exists():                             # 已配对：起局域网信箱，中枢自己也经它和手机收发
        import lanbox as mailbox
        bind = os.environ.get('PALM_LAN_BIND') or '0.0.0.0'
        port = int(os.environ.get('PALM_LAN_PORT') or mailbox.DEFAULT_PORT)
        msrv, _ = mailbox.make_server(bind, port, devices, hub.home / 'mailbox-blobs')
        threading.Thread(target=msrv.serve_forever, daemon=True).start()
        print(f'局域网信箱监听 {bind}:{port}', flush=True)
    from relay_link import load_link                 # 有配对配置（palm pair 生成）才连信箱
    link = hub.link = load_link(hub)
    if link:
        link.run()
        print(f'已连信箱 {link.url}（本机 {link.me} ↔ {link.peer}）', flush=True)
    else:
        print('未配对：只开本机接口（运行 palm pair 配对手机）', flush=True)
    srv.serve_forever()


if __name__ == '__main__':
    main()
