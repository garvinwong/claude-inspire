"""会话记录解析测试：用合成的 JSONL，覆盖各类事件、图片落盘、游标增量、翻页、残行、坏行。"""
import base64
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'hub'))
import transcript as T  # noqa: E402

PNG = base64.b64encode(b'\x89PNG\r\n\x1a\n' + b'0' * 40).decode()
JPG = base64.b64encode(b'\xff\xd8\xff' + b'1' * 40).decode()


def line(**kw):
    kw.setdefault('uuid', f"u{line.n}")
    kw.setdefault('timestamp', f'2026-10-26T09:00:{line.n:02d}Z')
    line.n += 1
    return json.dumps(kw, ensure_ascii=False)


line.n = 0


def user(content, **kw):
    return line(type='user', message={'role': 'user', 'content': content}, **kw)


def asst(*blocks, idx=0):
    return line(type='assistant', apiBlockIndex=idx, message={'role': 'assistant', 'content': list(blocks)})


def write(tmp_path, lines, tail_newline=True):
    p = tmp_path / 's.jsonl'
    p.write_text('\n'.join(lines) + ('\n' if tail_newline else ''), encoding='utf-8')
    return p


def read(p, tmp_path, **kw):
    return T.Transcript(p, T.ImageStore(tmp_path / 'img')).read(**kw)


def kinds(r):
    return [e['k'] for e in r['events']]


def test_event_kinds_cover_a_turn(tmp_path):
    p = write(tmp_path, [
        user('<local-command-caveat>x</local-command-caveat>', isMeta=True),
        user('<command-name>/model</command-name>\n<command-args>haiku</command-args>'),
        user('<local-command-stdout>Set model to Haiku</local-command-stdout>'),
        user('帮我看看这张图'),
        user([{'type': 'image', 'source': {'type': 'base64', 'media_type': 'image/jpeg', 'data': JPG}}]),
        asst({'type': 'thinking', 'thinking': '先看图'}),
        asst({'type': 'text', 'text': '好的'}, idx=1),
        asst({'type': 'tool_use', 'id': 't1', 'name': 'Bash', 'input': {'command': 'ls -la', 'description': '列目录'}}),
        user([{'type': 'tool_result', 'tool_use_id': 't1', 'content': 'a\nb'}]),
        asst({'type': 'tool_use', 'id': 't2', 'name': 'TodoWrite', 'input': {'todos': [{'content': '写测试', 'status': 'in_progress'}]}}),
        asst({'type': 'tool_use', 'id': 't3', 'name': 'Bash', 'input': {'command': 'codex exec "审一下" < /dev/null'}}),
        line(type='ai-title', aiTitle='看图'),
        line(type='queue-operation', operation='enqueue', content='忙时追加的话'),
        line(type='api-request-blob', message={'content': [{'type': 'text', 'text': '大块'}]}),
        line(type='system', subtype='compact_boundary'),
    ])
    r = read(p, tmp_path)
    assert kinds(r) == ['command', 'command_output', 'user', 'image', 'thinking', 'text', 'tool', 'tool_result',
                        'todo', 'helper', 'title', 'queued', 'system']
    ev = {e['k']: e for e in r['events']}
    assert ev['command']['text'] == '/model haiku'
    assert ev['tool']['summary'] == {'command': 'ls -la', 'desc': '列目录'}
    assert ev['todo']['todos'] == [{'text': '写测试', 'status': 'in_progress'}]
    assert ev['helper']['agent'] == 'codex'
    assert ev['image']['mime'] == 'image/jpeg' and len(ev['image']['img']) == 32


def test_images_go_to_disk_not_into_events_and_dedupe(tmp_path):
    src = {'type': 'base64', 'data': PNG}                     # 工具结果里的图常不带类型：按文件头认
    p = write(tmp_path, [
        user([{'type': 'tool_result', 'tool_use_id': 't', 'content': [{'type': 'text', 'text': '截图'},
                                                                     {'type': 'image', 'source': src}]}]),
        user([{'type': 'image', 'source': src}]),
    ])
    r = read(p, tmp_path)
    assert PNG not in json.dumps(r)
    a = r['events'][0]['images'][0]
    b = r['events'][1]
    assert a['img'] == b['img'] and a['mime'] == 'image/png'
    store = T.ImageStore(tmp_path / 'img')
    assert store.find(a['img']).read_bytes().startswith(b'\x89PNG')
    assert len(list((tmp_path / 'img').iterdir())) == 1
    assert store.find('../../etc/passwd') is None


def test_cursor_incremental_and_partial_last_line(tmp_path):
    p = write(tmp_path, [user('一'), user('二')])
    r = read(p, tmp_path)
    assert [e['text'] for e in r['events']] == ['一', '二']
    with open(p, 'a', encoding='utf-8') as f:
        f.write(user('三') + '\n' + user('四')[:15])            # 第四行还没写完
    r2 = read(p, tmp_path, since=r['cursor'])
    assert [e['text'] for e in r2['events']] == ['三']
    with open(p, 'a', encoding='utf-8') as f:
        f.write(user('四')[15:] + '\n')
    line.n -= 1
    r3 = read(p, tmp_path, since=r2['cursor'])
    assert [e['text'] for e in r3['events']] == ['四']
    assert r3['cursor'] == p.stat().st_size


def test_line_without_newline_is_held_and_cursor_never_passes_eof(tmp_path):
    p = write(tmp_path, [user('整行但还没换行')], tail_newline=False)
    r = read(p, tmp_path)
    assert r['events'] == [] and r['cursor'] <= p.stat().st_size
    r = read(p, tmp_path, since=0)
    assert r['events'] == [] and r['cursor'] == 0
    with open(p, 'a', encoding='utf-8') as f:
        f.write('\n' + user('下一行') + '\n')
    r = read(p, tmp_path, since=r['cursor'])
    assert [e['text'] for e in r['events']] == ['整行但还没换行', '下一行']
    assert r['cursor'] == p.stat().st_size


def test_tail_and_paging_do_not_lose_or_repeat(tmp_path):
    p = write(tmp_path, [user(f'第{i}条') for i in range(30)])
    r = read(p, tmp_path, tail=10)
    assert [e['text'] for e in r['events']] == [f'第{i}条' for i in range(20, 30)]
    assert r['has_more_before'] and r['before']
    seen = [e['text'] for e in r['events']]
    while r.get('before'):
        r = read(p, tmp_path, before=r['before'], tail=7)
        seen = [e['text'] for e in r['events']] + seen
    assert seen == [f'第{i}条' for i in range(30)]


def test_tail_window_never_splits_one_line(tmp_path):
    p = write(tmp_path, [user('前'), asst({'type': 'text', 'text': 'A'}, {'type': 'text', 'text': 'B'})])
    r = read(p, tmp_path, tail=1)
    assert [e['text'] for e in r['events']] == ['A', 'B']


def test_bad_lines_and_unknown_types_are_skipped(tmp_path):
    p = write(tmp_path, ['{坏行', json.dumps([1, 2]), line(type='mystery'), user('好')])
    assert [e['text'] for e in read(p, tmp_path)['events']] == ['好']


def test_repeated_title_and_mode_collapse(tmp_path):
    p = write(tmp_path, [line(type='ai-title', aiTitle='甲'), user('x'), line(type='ai-title', aiTitle='甲'),
                         line(type='permission-mode', permissionMode='auto'), line(type='permission-mode', permissionMode='auto'),
                         line(type='ai-title', aiTitle='乙')])
    assert [(e['k'], e.get('text') or e.get('mode')) for e in read(p, tmp_path)['events']] == \
        [('title', '甲'), ('user', 'x'), ('mode', 'auto'), ('title', '乙')]


def test_long_text_is_truncated_with_note(tmp_path):
    p = write(tmp_path, [user([{'type': 'tool_result', 'tool_use_id': 't', 'content': 'x' * 10000}])])
    t = read(p, tmp_path)['events'][0]['text']
    assert len(t) < 3100 and '已截断' in t


def test_helper_detection():
    assert T.helper_of('codex exec -s workspace-write "y" < /dev/null') == 'codex'
    assert T.helper_of('kimi -p "总结一下"') == 'kimi'
    assert T.helper_of('agy --print "z"') == 'gemini'
    assert T.helper_of('gemini -p "q"') == 'gemini'
    assert T.helper_of('ls -la') is None


def test_session_meta_model_and_effort_from_tail(tmp_path):
    """对话页五个指标：模型与思考程度取记录里最近一次 API 请求，没有就退到助手消息的 model。"""
    f = tmp_path / 's.jsonl'
    big = 'x' * 600_000                                                   # 尾读窗口之外的老内容
    f.write_text('\n'.join([
        line(type='api-request', params={'model': 'claude-sonnet-5-5', 'output_config': {'effort': 'low'}}),
        user(big),
        line(type='api-request', params={'model': 'claude-opus-5-5', 'output_config': {'effort': 'xhigh'}}),
        line(type='assistant', message={'role': 'assistant', 'model': 'claude-opus-5-5', 'content': []}),
        '{坏行',
    ]) + '\n', encoding='utf-8')
    meta = T.MetaCache()
    assert {k: meta.get(f)[k] for k in ('model', 'effort')} == {'model': 'claude-opus-5-5', 'effort': 'xhigh'}
    with open(f, 'a', encoding='utf-8') as fp:                            # 会话里 /model 切了：增长后重读
        fp.write(line(type='api-request', params={'model': 'claude-fable-5-1', 'output_config': {}}) + '\n')
    assert {k: meta.get(f)[k] for k in ('model', 'effort')} == {'model': 'claude-fable-5-1', 'effort': ''}
    g = tmp_path / 'g.jsonl'
    g.write_text(line(type='assistant', message={'role': 'assistant', 'model': 'claude-haiku-5-5', 'content': []}) + '\n')
    assert {k: meta.get(g)[k] for k in ('model', 'effort')} == {'model': 'claude-haiku-5-5', 'effort': ''}
    assert meta.get(tmp_path / 'none.jsonl') == {'model': '', 'effort': '', 'activity': None}


def test_session_meta_carries_last_tool_activity(tmp_path):
    """会话卡显示此刻在做什么：最近一次工具调用的工具名、精简参数、时间。"""
    f = tmp_path / 'a.jsonl'
    f.write_text('\n'.join([
        line(type='api-request', params={'model': 'claude-opus-5-5', 'output_config': {'effort': 'high'}}),
        asst({'type': 'tool_use', 'id': 't1', 'name': 'Bash', 'input': {'command': 'ls', 'description': '列目录'}}),
        line(type='user', message={'role': 'user', 'content': [{'type': 'tool_result', 'tool_use_id': 't1', 'content': 'x'}]}),
        asst({'type': 'text', 'text': '看一下文件'}, {'type': 'tool_use', 'id': 't2', 'name': 'Read',
                                                    'input': {'file_path': '/w/app/HomeScreen.kt', 'offset': 3}}),
    ]) + '\n', encoding='utf-8')
    meta = T.MetaCache().get(f)
    assert meta['model'] == 'claude-opus-5-5' and meta['effort'] == 'high'
    act = meta['activity']
    assert act['tool'] == 'Read' and act['summary'] == {'path': '/w/app/HomeScreen.kt'} and act['ts']
    g = tmp_path / 'b.jsonl'
    g.write_text(asst({'type': 'text', 'text': '只说话'}) + '\n', encoding='utf-8')
    assert T.MetaCache().get(g)['activity'] is None
