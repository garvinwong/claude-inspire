"""用量统计（仪表盘）：会话记录里的 token、工作时长、工具调用，按天汇总。

数据来源：<账号目录>/projects/*/<sid>.jsonl 与子代理记录 <sid>/subagents/*.jsonl。
- token：助手消息的 message.usage（输入 / 输出 / 缓存写 / 缓存读）。同一条回复按内容块拆成多行写入，
  每行带同一份 usage，按 message.id 去重（同一回复的行是连着写的，记住上一个 id 即可）。
- 工作时长：相邻两条消息间隔不超过 GAP 秒算在干活，连成区间；挂着不动的会话不计。
  “工作时长”取所有会话区间的并集（并行会话不重复算），“会话时长合计”是各主会话区间之和。
- 工具：tool_use 块按名字计数。

记录很大（近 30 天约 4.6GB），每个文件记住读到的字节位置，只读新增部分；结果存 cache/usage.json，
中枢重启后接着读。文件变小（被改写）就从头重读。
"""
import json
import os
import threading
import time
from datetime import datetime, timedelta
from pathlib import Path

GAP = 300                     # 秒：相邻消息间隔不超过它才算连续工作
KEEP_DAYS = 35                # 只看最近这么多天改过的记录
CACHE_VER = 1


def _epoch(ts: str):
    try:
        return datetime.fromisoformat(ts.replace('Z', '+00:00')).timestamp()
    except (AttributeError, ValueError):
        return None


def day_of(t: float) -> str:
    return datetime.fromtimestamp(t).strftime('%Y-%m-%d')    # 本机时区


def _new_state(sid, account, sub):
    return {'v': CACHE_VER, 'sid': sid, 'account': account, 'sub': sub, 'offset': 0, 'size': 0, 'mtime': 0,
            'last_id': '', 'title': '', 'cwd': '', 'spans': [], 'days': {}, 'tools': {}}


def _bucket(st, day):
    return st['days'].setdefault(day, {'in': 0, 'out': 0, 'cw': 0, 'cr': 0, 'msgs': 0, 'models': {}})


def _touch(st, t):
    """把时刻 t 并进活动区间：离上一个区间末尾不超过 GAP 就延长，否则开新区间。"""
    sp = st['spans']
    if sp and 0 <= t - sp[-1][1] <= GAP:
        sp[-1][1] = t
    elif not sp or t > sp[-1][1]:
        sp.append([t, t])


def feed(st: dict, raw: bytes):
    """处理一行记录，更新文件状态。只解析可能有用的行（助手消息、用户消息、标题）。"""
    if b'"assistant"' not in raw and b'"user"' not in raw and b'title"' not in raw:
        return
    try:
        d = json.loads(raw)
    except ValueError:
        return
    if not isinstance(d, dict):
        return
    typ = d.get('type')
    if typ in ('custom-title', 'ai-title'):
        t = d.get('customTitle') or d.get('aiTitle')
        if t and (typ == 'custom-title' or not st.get('custom')):
            st['title'], st['custom'] = str(t)[:80], st.get('custom') or typ == 'custom-title'
        return
    if typ not in ('assistant', 'user'):
        return
    t = _epoch(d.get('timestamp') or '')
    if t is None:
        return
    if d.get('cwd') and not st['cwd']:
        st['cwd'] = str(d['cwd'])
    _touch(st, t)
    if typ != 'assistant':
        return
    msg = d.get('message') if isinstance(d.get('message'), dict) else {}
    day = day_of(t)
    for b in msg.get('content') or []:
        if isinstance(b, dict) and b.get('type') == 'tool_use':
            tk = st['tools'].setdefault(day, {})
            name = str(b.get('name') or '?')
            tk[name] = tk.get(name, 0) + 1
    mid = msg.get('id') or d.get('requestId') or ''
    u = msg.get('usage')
    if not isinstance(u, dict) or (mid and mid == st['last_id']):
        return
    st['last_id'] = mid
    bk = _bucket(st, day)
    for k, src in (('in', 'input_tokens'), ('out', 'output_tokens'),
                   ('cw', 'cache_creation_input_tokens'), ('cr', 'cache_read_input_tokens')):
        v = u.get(src)
        if isinstance(v, int) and v > 0:
            bk[k] += v
    bk['msgs'] += 1
    model = str(msg.get('model') or '')
    if model and not model.startswith('<'):          # <synthetic> 等占位不算
        bk['models'][model] = bk['models'].get(model, 0) + 1


def scan(path: Path, st: dict, size: int):
    """从 st['offset'] 读到文件末尾最后一个完整行。"""
    if size < st['offset']:                          # 文件被改写：从头来
        keep = (st['sid'], st['account'], st['sub'])
        st.clear()
        st.update(_new_state(*keep))
    with open(path, 'rb') as f:
        f.seek(st['offset'])
        buf = f.read(size - st['offset'])
    end = buf.rfind(b'\n') + 1                        # 残行（正在写的那行）留到下次
    for raw in buf[:end].split(b'\n'):
        if raw:
            feed(st, raw)
    st['offset'] += end


def _union(spans):
    out = []
    for a, b in sorted(spans):
        if out and a <= out[-1][1]:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return out


def _split_by_day(spans, lo, hi):
    """区间按本地日期切开，返回 {day: 秒}；只计 [lo, hi) 时间段内。"""
    out = {}
    for a, b in spans:
        a, b = max(a, lo), min(b, hi)
        while a < b:
            d0 = datetime.fromtimestamp(a)
            nxt = datetime(d0.year, d0.month, d0.day).timestamp() + 86400
            seg = min(b, nxt) - a
            key = d0.strftime('%Y-%m-%d')
            out[key] = out.get(key, 0) + seg
            a = min(b, nxt)
    return out


class UsageStats:
    def __init__(self, config_dirs, label, cache_path: Path):
        self.config_dirs = list(config_dirs)
        self.label = label                           # config_dir → 账号名（单账号恒为 main）
        self.cache_path = Path(cache_path)
        self.files = {}
        self.lock = threading.Lock()
        self.ready = False
        self.progress = (0, 0)
        self._dirty = False
        self._busy = threading.Lock()                 # 同一时刻只跑一轮扫描
        self.last_refresh = 0.0
        self._load()

    # 缓存 ------------------------------------------------------------
    def _load(self):
        try:
            d = json.loads(self.cache_path.read_text(encoding='utf-8'))
            if d.get('v') == CACHE_VER:
                self.files = d.get('files') or {}
        except (OSError, ValueError):
            self.files = {}

    def _save(self):
        if not self._dirty:
            return
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.cache_path.with_suffix('.tmp')
        tmp.write_text(json.dumps({'v': CACHE_VER, 'files': self.files}, ensure_ascii=False), encoding='utf-8')
        os.replace(tmp, self.cache_path)
        self._dirty = False

    # 扫描 ------------------------------------------------------------
    def _candidates(self, now):
        cut = now - KEEP_DAYS * 86400
        for cd in self.config_dirs:
            root = Path(cd) / 'projects'
            if not root.is_dir():
                continue
            acct = self.label(cd)
            for p in root.glob('*/*.jsonl'):
                yield p, p.stem, acct, False, cut
            for p in root.glob('*/*/subagents/*.jsonl'):
                yield p, p.parent.parent.name, acct, True, cut

    def refresh(self, now=None, blocking=True):
        """增量更新所有近期记录；返回本轮读了多少字节（别人正在扫且 blocking=False 时返回 -1）。"""
        if not self._busy.acquire(blocking):
            return -1
        try:
            return self._refresh(now or time.time())
        finally:
            self.last_refresh = time.time()
            self._busy.release()

    def _refresh(self, now):
        todo = []
        for p, sid, acct, sub, cut in self._candidates(now):
            try:
                s = p.stat()
            except OSError:
                continue
            if s.st_mtime < cut:
                continue
            st = self.files.get(str(p))
            if st and st.get('size') == s.st_size and st.get('mtime') == s.st_mtime_ns:
                continue
            todo.append((p, sid, acct, sub, s))
        total = sum(max(0, s.st_size - (self.files.get(str(p)) or {}).get('offset', 0)) for p, *_, s in todo)
        done = 0
        for p, sid, acct, sub, s in todo:
            st = self.files.get(str(p)) or _new_state(sid, acct, sub)
            before = st['offset']
            try:
                scan(p, st, s.st_size)
            except OSError:
                continue
            st['size'], st['mtime'] = s.st_size, s.st_mtime_ns
            with self.lock:
                self.files[str(p)] = st
                self._dirty = True
            done += max(0, st['offset'] - before)
            self.progress = (done, total)
        with self.lock:
            for k in [k for k, v in self.files.items() if v.get('spans') and v['spans'][-1][1] < now - KEEP_DAYS * 86400]:
                del self.files[k]                   # 太旧的不留
            self.ready = True
            self._save()
        return done

    # 汇总 ------------------------------------------------------------
    def summary(self, days=7, now=None) -> dict:
        now = now or time.time()
        today = datetime.fromtimestamp(now).date()
        keys = [(today - timedelta(days=i)).strftime('%Y-%m-%d') for i in range(days - 1, -1, -1)]
        lo = datetime.combine(today - timedelta(days=days - 1), datetime.min.time()).timestamp()
        with self.lock:
            files = list(self.files.values())
        per_day = {k: {'in': 0, 'out': 0, 'cw': 0, 'cr': 0, 'msgs': 0, 'tools': 0, 'acct': {}} for k in keys}
        tools, models, sess, all_spans = {}, {}, {}, []
        for st in files:
            all_spans += st['spans']
            row = sess.setdefault(st['sid'], {'sid': st['sid'], 'account': st['account'], 'title': '', 'cwd': '',
                                               'tokens': 0, 'active_s': 0, 'days': set()})
            if not st['sub']:
                row['title'], row['cwd'] = st.get('title') or row['title'], st.get('cwd') or row['cwd']
                act = _split_by_day(st['spans'], lo, now + 1)
                row['active_s'] += sum(act.values())
                row['days'] |= set(act)
            for day, bk in st['days'].items():
                if day not in per_day:
                    continue
                pd, tok = per_day[day], bk['in'] + bk['out'] + bk['cw'] + bk['cr']
                for k in ('in', 'out', 'cw', 'cr', 'msgs'):
                    pd[k] += bk[k]
                pd['acct'][st['account']] = pd['acct'].get(st['account'], 0) + tok
                row['tokens'] += tok
                row['days'].add(day)
                for m, n in bk['models'].items():
                    models[m] = models.get(m, 0) + n
            for day, tk in st['tools'].items():
                if day in per_day:
                    for name, n in tk.items():
                        tools[name] = tools.get(name, 0) + n
                        per_day[day]['tools'] += n
        work = _split_by_day(_union(all_spans), lo, now + 1)
        sessions_by_day = {k: 0 for k in keys}
        for r in sess.values():
            for d in r['days']:
                if d in sessions_by_day:
                    sessions_by_day[d] += 1
        out_days = []
        for k in keys:
            pd = per_day[k]
            out_days.append({'date': k, 'tokens': pd['in'] + pd['out'] + pd['cw'] + pd['cr'],
                             'in': pd['in'], 'out': pd['out'], 'cw': pd['cw'], 'cr': pd['cr'], 'msgs': pd['msgs'],
                             'tools': pd['tools'], 'acct': pd['acct'], 'work_s': int(work.get(k, 0)),
                             'sessions': sessions_by_day[k]})
        tot = {k: sum(d[k] for d in out_days) for k in ('tokens', 'in', 'out', 'cw', 'cr', 'msgs', 'tools', 'work_s')}
        tot['sessions'] = sum(1 for r in sess.values() if r['days'] & set(keys))
        prompt = tot['in'] + tot['cw'] + tot['cr']
        tot['cache_hit'] = round(tot['cr'] / prompt, 4) if prompt else 0
        top = sorted((r for r in sess.values() if r['days'] & set(keys) and r['tokens']),
                     key=lambda r: -r['tokens'])[:8]
        return {
            'ready': self.ready, 'days': out_days, 'total': tot,
            'tools': sorted(tools.items(), key=lambda kv: -kv[1])[:6],
            'models': sorted(models.items(), key=lambda kv: -kv[1])[:4],
            'sessions': [{k: r[k] for k in ('sid', 'account', 'title', 'cwd', 'tokens')} | {'active_s': int(r['active_s'])}
                         for r in top],
        }


class QuotaLog:
    """额度走势：每 SAMPLE 秒记一次各账号 5h / 7d 用量（来自状态栏缓存或手动刷新），存 cache/quota.jsonl，留 7 天。"""
    SAMPLE = 600
    KEEP = 7 * 86400

    def __init__(self, path: Path):
        self.path = Path(path)
        self.last = 0.0
        try:
            tail = self.path.read_bytes().rstrip().rsplit(b'\n', 1)[-1]
            self.last = float(json.loads(tail).get('t') or 0)
        except (OSError, ValueError, AttributeError):
            pass

    def sample(self, usage: dict, now=None) -> bool:
        now = now or time.time()
        if now - self.last < self.SAMPLE:
            return False
        row = {'t': int(now)}
        for acct, u in (usage or {}).items():
            row[acct] = [(u.get(k) or {}).get('pct') for k in ('h5', 'd7')]
        rows = [r for r in self.read() if now - r['t'] < self.KEEP] + [row]
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix('.tmp')
        tmp.write_text(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in rows), encoding='utf-8')
        os.replace(tmp, self.path)
        self.last = now
        return True

    def read(self, since=0) -> list:
        out = []
        try:
            for raw in self.path.read_text(encoding='utf-8').splitlines():
                try:
                    r = json.loads(raw)
                except ValueError:
                    continue
                if isinstance(r, dict) and r.get('t', 0) >= since:
                    out.append(r)
        except OSError:
            pass
        return out
