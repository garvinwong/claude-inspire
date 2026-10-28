"""掌上灵感 · 斜杠命令与 @ 文件补全

只读：列命令名与说明、列会话目录下的文件相对路径，不读文件内容。

命令来源：
  1. 内置命令：Claude Code v2.1.310 实机敲 “/” 抄下的清单里，挑出手机上用得上的，配中文说明（2026-10-26）。
     menu=True 的会在终端里弹菜单，手机发出后切到终端视图作答。
  2. 技能与自定义命令：<会话目录>/.claude/{skills,commands}、<账号目录>/{skills,commands}、
     账号 settings.json 里已启用插件的 skills / commands（名字带 “插件名:” 前缀）。
文件：会话目录是 git 仓库时用 git ls-files（含未跟踪、守 .gitignore），否则有限度地遍历；按会话目录缓存 60 秒。
"""
import json
import os
import re
import subprocess
import threading
import time
from pathlib import Path

DESC_MAX = 120
FILE_CACHE_TTL = 60.0
FILE_SCAN_MAX = 200_000
WALK_MAX = 20_000
SKIP_DIRS = {'.git', 'node_modules', '__pycache__', '.venv', 'venv', 'build', 'dist', '.gradle', '.idea', 'target'}

# (命令, 中文说明, 是否弹终端菜单)
BUILTIN = [
    ('/clear', '清空上下文，开新会话（旧会话留在磁盘，可续接）', False),
    ('/compact', '压缩上下文：把已有对话总结成摘要（后面可加说明）', False),
    ('/context', '查看上下文占用', False),
    ('/usage', '本次花费、套餐额度与用量', False),
    ('/model', '切换模型', True),
    ('/effort', '设置推理强度', True),
    ('/fast', '开关快速模式', False),
    ('/plan', '进入计划模式，或查看本会话的计划', False),
    ('/goal', '设定目标：Claude 停下前先对照检查', False),
    ('/btw', '插问一个小问题，不打断主线', False),
    ('/subtask', '带着完整上下文派一个子代理，结果回到这里', False),
    ('/recap', '生成一句话会话回顾', False),
    ('/rename', '重命名本会话（后面跟新名字）', False),
    ('/branch', '从此处分出一条对话支线', False),
    ('/fork', '复制本对话到一个新的后台会话，这里继续', False),
    ('/rewind', '回退代码和 / 或对话到之前某一步', True),
    ('/resume', '切换到历史会话', True),
    ('/tasks', '查看和管理后台任务', True),
    ('/workflows', '查看运行中和已完成的工作流', True),
    ('/status', '状态：版本、模型、账号、连通性', True),
    ('/diff', '查看未提交的改动', True),
    ('/export', '导出当前对话', True),
    ('/memory', '编辑 CLAUDE.md 与记忆设置', True),
    ('/permissions', '管理工具权限的允许 / 拒绝规则', True),
    ('/mcp', '管理 MCP 服务', True),
    ('/hooks', '查看钩子配置', True),
    ('/skills', '列出可用技能', True),
    ('/config', '打开设置', True),
    ('/add-dir', '添加工作目录（后面跟路径）', False),
    ('/cd', '把本会话移到另一个工作目录（后面跟路径）', False),
    ('/reload-skills', '重新加载磁盘上新增或改动的技能', False),
    ('/release-notes', '查看版本更新说明', True),
    ('/help', '帮助与全部命令', True),
]


def _cut(s: str) -> str:
    s = ' '.join(str(s or '').split())
    return s if len(s) <= DESC_MAX else s[:DESC_MAX - 1] + '…'


def _front_matter(p: Path) -> dict:
    """读 Markdown 开头的 --- 块里 name / description 两项（单行值；不引第三方 YAML 库）。"""
    out = {}
    try:
        with open(p, encoding='utf-8', errors='replace') as f:
            head = f.read(8192)
    except OSError:
        return out
    m = re.match(r'---\s*\n(.*?)\n---', head, re.S)
    if m:
        for line in m.group(1).splitlines():
            kv = re.match(r'(name|description)\s*:\s*(.*)', line)
            if kv:
                out[kv.group(1)] = kv.group(2).strip().strip('"\'')
    else:
        first = next((ln.strip('# ').strip() for ln in head.splitlines() if ln.strip()), '')
        if first:
            out['description'] = first
    return out


def _skills(base: Path, prefix: str, src: str) -> list:
    out = []
    for p in sorted(base.glob('*/SKILL.md')) if base.is_dir() else []:
        fm = _front_matter(p)
        out.append({'name': f"/{prefix}{fm.get('name') or p.parent.name}", 'desc': _cut(fm.get('description')), 'src': src})
    return out


def _commands(base: Path, prefix: str, src: str) -> list:
    out = []
    for p in sorted(base.rglob('*.md')) if base.is_dir() else []:
        rel = p.relative_to(base).with_suffix('')
        out.append({'name': f"/{prefix}{':'.join(rel.parts)}", 'desc': _cut(_front_matter(p).get('description')), 'src': src})
    return out


def _plugins(config_dir: Path) -> list:
    """账号 settings.json 里启用的插件 → 安装目录（installed_plugins.json）。"""
    try:
        enabled = json.loads((config_dir / 'settings.json').read_text(encoding='utf-8')).get('enabledPlugins') or {}
        installed = json.loads((config_dir / 'plugins' / 'installed_plugins.json').read_text(encoding='utf-8')).get('plugins') or {}
    except (OSError, ValueError, AttributeError):
        return []
    out = []
    for key, on in enabled.items():
        if not on:
            continue
        for inst in installed.get(key) or []:
            if inst.get('scope', 'user') == 'user' and inst.get('installPath'):
                out.append((key.split('@')[0], Path(inst['installPath'])))
                break
    return out


def commands(cwd: str, config_dir: str) -> list:
    """内置 + 技能 + 自定义命令；同名以先出现的为准（内置优先）。"""
    rows = [{'name': n, 'desc': d, 'src': '内置', 'menu': m} for n, d, m in BUILTIN]
    cd = Path(config_dir or '~/.claude').expanduser()
    if cwd:
        proj = Path(cwd) / '.claude'
        rows += _commands(proj / 'commands', '', '项目命令') + _skills(proj / 'skills', '', '项目技能')
    rows += _commands(cd / 'commands', '', '我的命令') + _skills(cd / 'skills', '', '我的技能')
    for name, root in _plugins(cd):
        rows += _commands(root / 'commands', f'{name}:', f'插件 {name}') + _skills(root / 'skills', f'{name}:', f'插件 {name}')
    seen, out = set(), []
    for r in rows:
        if r['name'] not in seen:
            seen.add(r['name'])
            out.append(r)
    return out


class FileIndex:
    """会话目录 → 文件相对路径列表（缓存 60 秒）。"""

    def __init__(self, ttl=FILE_CACHE_TTL):
        self.ttl = ttl
        self.cache = {}
        self.lock = threading.Lock()

    def _scan(self, root: str) -> list:
        try:
            r = subprocess.run(['git', '-C', root, 'ls-files', '-co', '--exclude-standard', '-z'],
                               capture_output=True, timeout=10)
            if r.returncode == 0:
                return [p for p in r.stdout.decode('utf-8', 'replace').split('\0') if p][:FILE_SCAN_MAX]
        except (OSError, subprocess.TimeoutExpired):
            pass
        out = []
        for d, dirs, files in os.walk(root):
            dirs[:] = sorted(x for x in dirs if x not in SKIP_DIRS and not x.startswith('.'))
            rel = os.path.relpath(d, root)
            for f in sorted(files):
                out.append(f if rel == '.' else os.path.join(rel, f))
                if len(out) >= WALK_MAX:
                    return out
        return out

    def files(self, root: str) -> list:
        with self.lock:
            hit = self.cache.get(root)
            if hit and time.time() - hit[0] < self.ttl:
                return hit[1]
        files = self._scan(root)
        with self.lock:
            self.cache[root] = (time.time(), files)
        return files

    def search(self, root: str, q: str, limit=30) -> list:
        """文件名开头命中 > 文件名包含 > 路径包含；同档短路径在前。q 为空给最近层级的文件。"""
        if not root or not os.path.isdir(root):
            return []
        q = q.strip().lower()
        ranked = []
        for p in self.files(root):
            low = p.lower()
            base = low.rsplit('/', 1)[-1]
            if not q:
                rank = low.count('/')
            elif base.startswith(q):
                rank = 0
            elif q in base:
                rank = 1
            elif q in low:
                rank = 2
            else:
                continue
            ranked.append((rank, len(p), p))
        ranked.sort()
        return [p for _, _, p in ranked[:limit]]
