"""用量统计测试（仪表盘）：合成记录覆盖去重、增量、残行、改写重读、子代理、时长并集、跨天、缓存续读、额度走势。"""
import json
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'hub'))
import usage as U  # noqa: E402

T0 = datetime(2026, 10, 27, 10, 0, 0).timestamp()      # 本地时间 10:00
NOW = T0 + 6 * 3600


def iso(t):
    return datetime.fromtimestamp(t).astimezone().isoformat()


def asst(t, mid, tools=(), usage=None, model='claude-opus-5-5'):
    content = [{'type': 'tool_use', 'name': n, 'input': {}} for n in tools] or [{'type': 'text', 'text': 'x'}]
    u = usage or {'input_tokens': 10, 'output_tokens': 100, 'cache_creation_input_tokens': 1000,
                  'cache_read_input_tokens': 5000}
    return json.dumps({'type': 'assistant', 'timestamp': iso(t), 'cwd': '/w',
                       'message': {'id': mid, 'model': model, 'content': content, 'usage': u}})


def user(t):
    return json.dumps({'type': 'user', 'timestamp': iso(t), 'message': {'content': 'hi'}})


def setup(tmp_path, accts=('main',)):
    dirs = []
    for a in accts:
        d = tmp_path / f'cd-{a}'
        (d / 'projects' / 'p').mkdir(parents=True)
        dirs.append(d)
    names = {str(d): a for d, a in zip(dirs, accts)}
    return dirs, U.UsageStats([str(d) for d in dirs], lambda cd: names[cd], tmp_path / 'cache' / 'usage.json')


def write(p, lines, tail='\n'):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text('\n'.join(lines) + tail, encoding='utf-8')


def test_dedup_same_message_split_into_lines(tmp_path):
    (d,), s = setup(tmp_path)
    write(d / 'projects/p/s1.jsonl', [user(T0), asst(T0 + 5, 'm1', ['Bash']), asst(T0 + 6, 'm1', ['Read']),
                                      asst(T0 + 7, 'm2')])
    s.refresh(NOW)
    r = s.summary(1, NOW)
    assert r['total']['msgs'] == 2
    assert r['total']['tokens'] == 2 * 6110
    assert dict(r['tools']) == {'Bash': 1, 'Read': 1}           # 工具按块计，不受去重影响
    assert r['total']['cache_hit'] == round(10000 / 12020, 4)


def test_incremental_and_partial_line(tmp_path):
    (d,), s = setup(tmp_path)
    p = d / 'projects/p/s1.jsonl'
    write(p, [asst(T0, 'm1')])
    with open(p, 'a') as f:
        f.write(asst(T0 + 10, 'm2')[:40])                       # 正在写的残行
    s.refresh(NOW)
    assert s.summary(1, NOW)['total']['msgs'] == 1
    write(p, [asst(T0, 'm1'), asst(T0 + 10, 'm2'), asst(T0 + 20, 'm3')])
    assert s.refresh(NOW) > 0
    assert s.summary(1, NOW)['total']['msgs'] == 3
    assert s.refresh(NOW) == 0                                  # 没变化不读


def test_rewritten_file_rescans(tmp_path):
    (d,), s = setup(tmp_path)
    p = d / 'projects/p/s1.jsonl'
    write(p, [asst(T0, 'm1'), asst(T0 + 1, 'm2'), asst(T0 + 2, 'm3')])
    s.refresh(NOW)
    write(p, [asst(T0, 'mx')])                                  # 变小了
    s.refresh(NOW)
    assert s.summary(1, NOW)['total']['msgs'] == 1


def test_work_time_gap_union_and_subagents(tmp_path):
    (d,), s = setup(tmp_path)
    write(d / 'projects/p/s1.jsonl', [user(T0), asst(T0 + 200, 'a'), asst(T0 + 400, 'b'),
                                      asst(T0 + 400 + U.GAP + 1, 'c')])      # 断开：后一段只有一个点
    write(d / 'projects/p/s2.jsonl', [user(T0 + 100), asst(T0 + 300, 'd')])     # 并行会话
    write(d / 'projects/p/s1/subagents/agent-x.jsonl', [user(T0 + 350), asst(T0 + 500, 'e')])
    s.refresh(NOW)
    r = s.summary(1, NOW)
    assert r['total']['work_s'] == 500                         # 并集 10:00~10:08:20，子代理把它延到 500 秒
    rows = {x['sid']: x for x in r['sessions']}
    assert rows['s1']['active_s'] == 400 and rows['s2']['active_s'] == 200    # 合计按主会话各算各的
    assert rows['s1']['tokens'] == 4 * 6110                    # 子代理 token 归到父会话
    assert r['total']['sessions'] == 2


def test_cross_midnight_and_days(tmp_path):
    (d,), s = setup(tmp_path)
    mid = datetime(2026, 10, 27, 0, 0, 0).timestamp()
    write(d / 'projects/p/s1.jsonl', [user(mid - 120), asst(mid - 60, 'a'), asst(mid + 60, 'b')])
    s.refresh(NOW)
    r = s.summary(2, NOW)
    assert [x['date'] for x in r['days']] == ['2026-10-26', '2026-10-27']
    assert [x['work_s'] for x in r['days']] == [120, 60]
    assert [x['msgs'] for x in r['days']] == [1, 1]
    assert [x['sessions'] for x in r['days']] == [1, 1]


def test_accounts_titles_and_cache_reload(tmp_path):
    (a, b), s = setup(tmp_path, ('main', 'work'))
    write(a / 'projects/p/s1.jsonl', [json.dumps({'type': 'ai-title', 'aiTitle': '自动标题'}),
                                      json.dumps({'type': 'custom-title', 'customTitle': '掌上灵感'}),
                                      json.dumps({'type': 'ai-title', 'aiTitle': '后来的自动标题'}),
                                      asst(T0, 'm1')])
    write(b / 'projects/p/s2.jsonl', [asst(T0, 'm2'), 'not json', asst(T0 + 1, 'm3', model='<synthetic>')])
    s.refresh(NOW)
    r = s.summary(1, NOW)
    assert r['days'][0]['acct'] == {'main': 6110, 'work': 12220}
    assert {x['sid']: x['title'] for x in r['sessions']}['s1'] == '掌上灵感'      # 手起的标题优先
    assert r['models'] == [('claude-opus-5-5', 2)]
    s2 = U.UsageStats(s.config_dirs, s.label, s.cache_path)    # 重启后从缓存续读，不重扫
    assert s2.refresh(NOW) == 0
    assert s2.summary(1, NOW)['total']['tokens'] == r['total']['tokens']


def test_quota_log_samples_and_trims(tmp_path):
    q = U.QuotaLog(tmp_path / 'quota.jsonl')
    u = {'main': {'h5': {'pct': 12}, 'd7': {'pct': 3}}, 'work': {'h5': {'pct': 99}}}
    assert q.sample(u, T0)
    assert not q.sample(u, T0 + 60)                            # 10 分钟内不重复记
    assert q.sample(u, T0 + 8 * 86400)                         # 7 天前的被剪掉
    rows = U.QuotaLog(tmp_path / 'quota.jsonl').read()
    assert len(rows) == 1 and rows[0]['main'] == [12, 3] and rows[0]['work'] == [99, None]
    assert U.QuotaLog(tmp_path / 'quota.jsonl').last == T0 + 8 * 86400
