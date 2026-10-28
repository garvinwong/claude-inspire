"""掌上灵感 · 会话记录 → 统一事件

读 Claude Code 的会话记录 JSONL，转成手机对话视图用的事件。逐行容错：坏行、未知类型一律跳过，不抛错。
图片（用户贴图、工具结果里的图）按内容哈希落到缓存目录，事件里只带编号，不带 Base64。

事件（字段 k 区分类型）：
  user / command / command_output / queued      用户侧：输入、斜杠命令、命令输出、忙时排队的消息
  text / thinking                               助手文字、思考
  tool / tool_result                            工具调用（摘要）与结果（截断，可带图片编号）
  todo                                          待办清单（TodoWrite）
  helper                                        Claude 调用的助手（Codex / Kimi / Gemini / 其他账号），一期只展示
  image                                         图片（用户贴图）
  title / mode / system                         标题、权限模式、压缩等系统事件
每个事件带 id（行 uuid + 块序号）与 ts（ISO 时间）。

读取方式（游标 = 文件字节偏移，只停在完整行末尾）：
  read(tail=N)            取最近 N 条，返回 cursor（接着用 since 增量读）与 before（往前翻页用）
  read(since=偏移)         从偏移读到文件末尾的新事件
  read(before=偏移, tail=N) 取偏移之前的 N 条（往前翻页）
"""
import base64
import hashlib
import json
import os
import re
from pathlib import Path
from typing import Dict, List, Optional

TAIL_BYTES = 2 * 1024 * 1024      # 尾读窗口
SINCE_MAX = 4 * 1024 * 1024       # 增量读单次上限
TEXT_MAX = 20000
RESULT_MAX = 3000
INPUT_MAX = 800
SKIP_TYPES = {'api-request', 'api-request-blob', 'api-request-shape', 'attachment', 'file-history-snapshot',
              'file-history-delta', 'atis-latch', 'last-prompt', 'agent-name', 'bridge-session', 'cost-state'}
HELPERS = [
    (re.compile(r'\bcodex\s+(exec|e)\b'), 'codex'),
    (re.compile(r'\bkimi\b[^|;&]*\s(-p|--prompt)\b'), 'kimi'),
    (re.compile(r'\bgemini\b[^|;&]*\s(-p|--prompt)\b|\bagy\b[^|;&]*--print'), 'gemini'),
]
CMD_RE = re.compile(r'<command-name>(.*?)</command-name>', re.S)
ARGS_RE = re.compile(r'<command-args>(.*?)</command-args>', re.S)
STDOUT_RE = re.compile(r'<local-command-stdout>(.*?)</local-command-stdout>', re.S)
MAGIC = [(b'\x89PNG', 'image/png', 'png'), (b'\xff\xd8', 'image/jpeg', 'jpg'),
         (b'GIF8', 'image/gif', 'gif'), (b'RIFF', 'image/webp', 'webp')]
EXT = {'image/png': 'png', 'image/jpeg': 'jpg', 'image/gif': 'gif', 'image/webp': 'webp'}


def _cut(s, n):
    s = s if isinstance(s, str) else json.dumps(s, ensure_ascii=False)
    return s if len(s) <= n else s[:n] + f'…（共 {len(s)} 字，已截断）'


class ImageStore:
    """图片按内容哈希落盘；同一张图只写一次。"""

    def __init__(self, root: Path):
        self.root = Path(root)

    def put(self, source: dict) -> Optional[dict]:
        if not isinstance(source, dict) or source.get('type') != 'base64':
            return None
        try:
            raw = base64.b64decode(source.get('data') or '', validate=False)
        except (ValueError, TypeError):
            return None
        if not raw:
            return None
        mime = source.get('media_type') or ''
        if mime not in EXT:
            mime = next((m for sig, m, _ in MAGIC if raw.startswith(sig)), 'application/octet-stream')
        ext = EXT.get(mime, 'bin')
        iid = hashlib.sha256(raw).hexdigest()[:32]
        path = self.root / f'{iid}.{ext}'
        if not path.exists():
            self.root.mkdir(parents=True, exist_ok=True)
            tmp = path.with_suffix('.tmp')
            tmp.write_bytes(raw)
            os.replace(tmp, path)
        return {'img': iid, 'mime': mime, 'bytes': len(raw)}

    def find(self, iid: str) -> Optional[Path]:
        if not re.fullmatch(r'[0-9a-f]{32}', iid or ''):
            return None
        for ext in set(EXT.values()) | {'bin'}:
            p = self.root / f'{iid}.{ext}'
            if p.exists():
                return p
        return None


def tool_summary(name: str, inp: dict) -> dict:
    """工具调用的人话摘要：手机上一眼看懂在干什么，细节截断。"""
    inp = inp if isinstance(inp, dict) else {}
    g = inp.get
    if name == 'Bash':
        return {'command': _cut(g('command') or '', 600), 'desc': _cut(g('description') or '', 200)}
    if name in ('Read', 'NotebookRead'):
        return {'path': g('file_path') or g('notebook_path') or '', 'range': [g('offset'), g('limit')]}
    if name == 'Edit':
        return {'path': g('file_path') or '', 'old': _cut(g('old_string') or '', INPUT_MAX),
                'new': _cut(g('new_string') or '', INPUT_MAX), 'all': bool(g('replace_all'))}
    if name == 'Write':
        return {'path': g('file_path') or '', 'head': _cut(g('content') or '', INPUT_MAX)}
    if name in ('Grep', 'Glob'):
        return {'pattern': g('pattern') or '', 'path': g('path') or ''}
    if name == 'WebFetch':
        return {'url': g('url') or ''}
    if name == 'WebSearch':
        return {'query': g('query') or ''}
    if name in ('Task', 'Agent'):
        return {'desc': g('description') or '', 'agent': g('subagent_type') or '', 'prompt': _cut(g('prompt') or '', INPUT_MAX)}
    if name == 'AskUserQuestion':
        return {'questions': g('questions') or []}
    if name == 'ExitPlanMode':
        return {'plan': _cut(g('plan') or '', TEXT_MAX)}
    return {'input': _cut(inp, 500)}


def helper_of(command: str) -> Optional[str]:
    for rx, who in HELPERS:
        if rx.search(command or ''):
            return who
    return None


def _result_text(content) -> (str, List[dict]):
    """工具结果：文字拼起来，图片单独拿出来。"""
    if isinstance(content, str):
        return content, []
    texts, imgs = [], []
    for b in content if isinstance(content, list) else []:
        if not isinstance(b, dict):
            continue
        if b.get('type') == 'text':
            texts.append(b.get('text') or '')
        elif b.get('type') == 'image':
            imgs.append(b.get('source') or {})
    return '\n'.join(texts), imgs


def parse_line(d: dict, images: ImageStore) -> List[dict]:
    t = d.get('type')
    if not t or t in SKIP_TYPES:
        return []
    uid, ts = d.get('uuid') or '', d.get('timestamp') or ''
    base = lambda i, **kw: dict({'id': f'{uid}:{i}', 'ts': ts}, **kw)   # noqa: E731
    if t in ('ai-title', 'custom-title'):
        title = d.get('aiTitle') or d.get('customTitle') or d.get('title') or ''
        return [{'id': f'title:{title}', 'ts': ts, 'k': 'title', 'text': title}] if title else []
    if t == 'queue-operation':
        if d.get('operation') == 'enqueue' and d.get('content'):
            return [{'id': f"q:{ts}", 'ts': ts, 'k': 'queued', 'text': _cut(str(d['content']), TEXT_MAX)}]
        return []
    if t == 'permission-mode':
        mode = d.get('permissionMode') or d.get('mode')
        return [{'id': f'mode:{ts}:{mode}', 'ts': ts, 'k': 'mode', 'mode': mode}] if mode else []
    if t == 'system':
        sub = d.get('subtype') or ''
        if sub == 'compact_boundary':
            return [base(0, k='system', text='上下文已压缩')]
        if d.get('level') == 'error':
            return [base(0, k='system', text=_cut(d.get('content') or sub or '出错', 1000), level='error')]
        return []
    msg = d.get('message') or {}
    content = msg.get('content')
    out = []
    if t == 'user':
        if d.get('isMeta'):
            return []
        if isinstance(content, str):
            content = [{'type': 'text', 'text': content}]
        for i, b in enumerate(content if isinstance(content, list) else []):
            if not isinstance(b, dict):
                continue
            bt = b.get('type')
            if bt == 'text':
                txt = b.get('text') or ''
                if txt.lstrip().startswith('<system-reminder>'):
                    continue
                m = CMD_RE.search(txt)
                if m:
                    a = ARGS_RE.search(txt)
                    out.append(base(i, k='command', text=(m.group(1).strip() + ' ' + (a.group(1).strip() if a else '')).strip()))
                    continue
                m = STDOUT_RE.search(txt)
                if m:
                    if m.group(1).strip():
                        out.append(base(i, k='command_output', text=_cut(m.group(1).strip(), RESULT_MAX)))
                    continue
                if txt.strip():
                    out.append(base(i, k='user', text=_cut(txt, TEXT_MAX)))
            elif bt == 'image':
                info = images.put(b.get('source') or {})
                if info:
                    out.append(base(i, k='image', role='user', **info))
            elif bt == 'tool_result':
                text, imgs = _result_text(b.get('content'))
                ev = base(i, k='tool_result', tool_use_id=b.get('tool_use_id') or '',
                          is_error=bool(b.get('is_error')), text=_cut(text, RESULT_MAX))
                infos = [x for x in (images.put(s) for s in imgs) if x]
                if infos:
                    ev['images'] = infos
                out.append(ev)
        return out
    if t == 'assistant':
        for i, b in enumerate(content if isinstance(content, list) else []):
            if not isinstance(b, dict):
                continue
            bt = b.get('type')
            idx = f"{d.get('apiBlockIndex', '')}.{i}"
            if bt == 'text' and (b.get('text') or '').strip():
                out.append(base(idx, k='text', text=_cut(b['text'], TEXT_MAX)))
            elif bt == 'thinking' and (b.get('thinking') or '').strip():
                out.append(base(idx, k='thinking', text=_cut(b['thinking'], TEXT_MAX)))
            elif bt == 'tool_use':
                name, inp = b.get('name') or '', b.get('input') or {}
                if name == 'TodoWrite':
                    todos = [{'text': x.get('content') or x.get('activeForm') or '', 'status': x.get('status') or ''}
                             for x in (inp.get('todos') or []) if isinstance(x, dict)]
                    out.append(base(idx, k='todo', tool_use_id=b.get('id') or '', todos=todos))
                    continue
                ev = base(idx, k='tool', tool_use_id=b.get('id') or '', name=name, summary=tool_summary(name, inp))
                if name == 'Bash':
                    who = helper_of(inp.get('command') or '')
                    if who:
                        ev = base(idx, k='helper', tool_use_id=b.get('id') or '', agent=who,
                                  command=_cut(inp.get('command') or '', 1200), desc=inp.get('description') or '')
                out.append(ev)
        return out
    return []


class Transcript:
    def __init__(self, path, images: ImageStore):
        self.path = Path(path)
        self.images = images

    def _lines(self, start: int, end: int, aligned: bool):
        """读 [start, end) 字节里的完整行。aligned=False 表示 start 可能落在行中间，先丢掉残行。
        返回 ([(行首偏移, 行尾偏移, dict)], 第一整行的行首偏移)。最后一段没有换行的视为还在写，留到下次。"""
        with open(self.path, 'rb') as f:
            f.seek(start)
            data = f.read(max(0, end - start))
        pos = start
        if not aligned and start > 0:
            nl = data.find(b'\n')
            if nl < 0:
                return [], end
            pos, data = start + nl + 1, data[nl + 1:]
        first, out = pos, []
        for raw in data.split(b'\n')[:-1]:
            line_start, pos = pos, pos + len(raw) + 1
            try:
                d = json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                continue
            if isinstance(d, dict):
                out.append((line_start, pos, d))
        return out, first

    def _events(self, lines):
        """标题与权限模式每轮都会重复写一行：与上一条同值的丢掉，免得挤占窗口。"""
        evs, last = [], {}
        for line_start, _end, d in lines:
            try:
                parsed = parse_line(d, self.images)
            except Exception:                          # 单行解析失败只丢这一行
                continue
            for e in parsed:
                if e['k'] in ('title', 'mode'):
                    val = e.get('text') or e.get('mode')
                    if last.get(e['k']) == val:
                        continue
                    last[e['k']] = val
                evs.append((line_start, e))
        return evs

    def read(self, since: Optional[int] = None, tail: int = 200, before: Optional[int] = None) -> Dict:
        size = self.path.stat().st_size
        if since is not None:                          # 增量：since 是上次给出的行尾，必是行首
            since = max(0, min(int(since), size))
            lines, _ = self._lines(since, min(size, since + SINCE_MAX), aligned=True)
            return {'events': [e for _, e in self._events(lines)],
                    'cursor': lines[-1][1] if lines else since, 'size': size}
        end = size if before is None else max(0, min(int(before), size))
        start = max(0, end - TAIL_BYTES)
        lines, first = self._lines(start, end, aligned=(start == 0))
        evs = self._events(lines)
        more = first > 0
        if tail and len(evs) > tail:                   # 截到 tail 条，但不把同一行拆开
            cut = evs[-tail][0]
            evs = [x for x in evs if x[0] >= cut]
            more = True
        res = {'events': [e for _, e in evs], 'size': size, 'has_more_before': more,
               'before': (evs[0][0] if evs else first) if more else None}
        if before is None:
            res['cursor'] = lines[-1][1] if lines else first
        return res


META_TAIL = 512 * 1024


ACT_KEYS = ('path', 'command', 'desc', 'pattern', 'query', 'url')


def _activity(d: dict):
    """助手消息里最后一个工具调用 → {tool, summary(只留短字段), ts}；没有工具调用返回 None。"""
    blocks = (d.get('message') or {}).get('content')
    if not isinstance(blocks, list):
        return None
    for b in reversed(blocks):
        if isinstance(b, dict) and b.get('type') == 'tool_use':
            name = str(b.get('name') or '')
            summ = tool_summary(name, b.get('input') if isinstance(b.get('input'), dict) else {})
            small = {k: (_cut(v, 120) if isinstance(v, str) else v) for k, v in summ.items() if k in ACT_KEYS and v}
            return {'tool': name, 'summary': small, 'ts': d.get('timestamp') or ''}
    return None


class MetaCache:
    """会话的模型、思考程度与当前动作：记录尾部最近一次主线程 api-request 的 params（model、output_config.effort），
    没有就退到助手消息的 model；当前动作 = 最近一次工具调用（会话卡显示“读 HomeScreen.kt”这类）。
    按（大小、修改时间）缓存，记录增长了才重读。"""

    def __init__(self):
        self.cache = {}

    def get(self, path) -> dict:
        p = Path(path)
        try:
            st = p.stat()
        except OSError:
            return {'model': '', 'effort': '', 'activity': None}
        key = (st.st_size, st.st_mtime_ns)
        hit = self.cache.get(str(p))
        if hit and hit[0] == key:
            return hit[1]
        meta = self._scan(p, st.st_size)
        self.cache[str(p)] = (key, meta)
        return meta

    @staticmethod
    def _scan(p: Path, size: int) -> dict:
        try:
            with open(p, 'rb') as f:
                f.seek(max(0, size - META_TAIL))
                lines = f.read().split(b'\n')
        except OSError:
            return {'model': '', 'effort': '', 'activity': None}
        out = {'model': '', 'effort': '', 'activity': None}
        fallback, have_req, have_act = '', False, False
        for raw in reversed(lines):
            if b'"api-request"' not in raw and b'"assistant"' not in raw:
                continue
            try:
                d = json.loads(raw)
            except ValueError:
                continue
            t = d.get('type')
            if t == 'api-request' and not have_req and isinstance(d.get('params'), dict) \
                    and d.get('querySource', 'repl_main_thread') == 'repl_main_thread':   # 标题生成等旁路请求不算
                prm = d['params']
                oc = prm.get('output_config') if isinstance(prm.get('output_config'), dict) else {}
                out['model'], out['effort'], have_req = str(prm.get('model') or ''), str(oc.get('effort') or ''), True
            elif t == 'assistant':
                if not fallback:
                    fallback = str((d.get('message') or {}).get('model') or '')
                if not have_act:
                    act = _activity(d)
                    if act:
                        out['activity'], have_act = act, True
            if have_req and have_act:
                break
        if not out['model']:
            out['model'] = fallback
        return out
