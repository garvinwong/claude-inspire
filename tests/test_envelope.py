"""加密信封测试：往返、方向隔离、错钥、篡改、重放与乱序窗口、伪造不推高序号、时间窗、落盘、旧状态兼容、时钟倒退、固定向量。"""
import base64
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'hub'))
import envelope as E  # noqa: E402

KEY = bytes(range(32))
VECTOR = Path(__file__).resolve().parent / 'vectors' / 'envelope_v2.json'


class Clock:
    def __init__(self, t=1_800_000_000.0):
        self.t = t

    def __call__(self):
        return self.t


def pair(clock=None, state=None):
    clock = clock or Clock()
    return E.Sealer(KEY, E.PC2M, 'pc', clock), E.Opener(KEY, E.PC2M, state, clock), clock


def test_roundtrip_with_chinese_payload():
    s, o, _ = pair()
    env = s.seal('L4', {'sid': 'x', 'events': [{'k': 'text', 'text': '掌上灵感'}]})
    assert '掌上灵感' not in json.dumps(env, ensure_ascii=False)
    assert o.open(env) == ('L4', {'sid': 'x', 'events': [{'k': 'text', 'text': '掌上灵感'}]})


def test_directions_and_keys_are_separate():
    s, _, clock = pair()
    env = s.seal('L5', {'op': 'send_text'})
    with pytest.raises(E.EnvelopeError):
        E.Opener(KEY, E.M2PC, None, clock).open(env)              # 反方向的钥匙解不开
    with pytest.raises(E.EnvelopeError):
        E.Opener(bytes(32), E.PC2M, None, clock).open(env)         # 错配对密钥


@pytest.mark.parametrize('field,value', [('kind', 'L1'), ('seq', 1), ('ts', 1_800_000_001), ('dev', 'evil'), ('v', 3)])
def test_tampering_any_header_field_fails(field, value):
    s, o, _ = pair()
    env = s.seal('L5', {'op': 'interrupt'})
    env[field] = value
    with pytest.raises(E.EnvelopeError):
        o.open(env)


def test_tampering_ciphertext_fails():
    s, o, _ = pair()
    env = s.seal('L5', {'op': 'interrupt'})
    raw = bytearray(base64.b64decode(env['ct']))
    raw[0] ^= 1
    env['ct'] = base64.b64encode(bytes(raw)).decode()
    with pytest.raises(E.EnvelopeError):
        o.open(env)


def test_out_of_order_accepted_but_replay_rejected():
    """真机实测：手机三个请求并发发出，到达顺序与序号顺序不一致，后到的小序号被当成重放丢掉，手机空等超时。
    改为滑动窗口：窗口内乱序照收，同一序号只收一次。"""
    s, o, _ = pair()
    a, b = s.seal('L5', {'n': 1}), s.seal('L5', {'n': 2})
    assert o.open(b)[1] == {'n': 2}
    assert o.open(a)[1] == {'n': 1}                                # 乱序到达：照收
    for env in (a, b):
        with pytest.raises(E.EnvelopeError):
            o.open(env)                                            # 重放


def test_older_than_reorder_window_rejected():
    clock = Clock()
    s, o = E.Sealer(KEY, E.PC2M, 'pc', clock), E.Opener(KEY, E.PC2M, None, clock)
    old = s.seal('L5', {'n': 'old'})
    clock.t += E.REORDER // 1_000_000 + 5
    o.open(s.seal('L5', {'n': 'new'}))
    with pytest.raises(E.EnvelopeError):
        o.open(old)                                                # 比最新的早出窗口：拒收


def test_forged_envelope_cannot_push_sequence_forward():
    s, o, _ = pair()
    real = s.seal('L5', {'n': 1})
    forged = dict(real, seq=real['seq'] + 10**9)                   # 序号改大，但认证不过
    with pytest.raises(E.EnvelopeError):
        o.open(forged)
    assert o.open(real)[1] == {'n': 1}                             # 真信封照常收


def test_time_window():
    s, o, clock = pair()
    env = s.seal('L1', {})
    clock.t += E.WINDOW + 1
    with pytest.raises(E.EnvelopeError):
        o.open(env)


def test_state_persists_across_restart(tmp_path):
    state = tmp_path / 'replay.json'
    s, o, clock = pair(state=state)
    env = s.seal('L5', {'n': 1})
    o.open(env)
    assert oct(state.stat().st_mode & 0o777) == '0o600'
    o2 = E.Opener(KEY, E.PC2M, state, clock)                       # 中枢重启
    with pytest.raises(E.EnvelopeError):
        o2.open(env)
    a, b = s.seal('L5', {'n': 2}), s.seal('L5', {'n': 3})
    o2.open(b)
    o3 = E.Opener(KEY, E.PC2M, state, clock)                       # 再重启：窗口里收过的仍拒收、没收过的照收
    with pytest.raises(E.EnvelopeError):
        o3.open(b)
    assert o3.open(a)[1] == {'n': 2}


def test_legacy_state_file_is_treated_as_floor(tmp_path):
    """旧版状态文件只有 {设备: 最大序号}：升级后把它当下限，不因窗口放宽而多收旧信封。"""
    state = tmp_path / 'replay.json'
    s, _, clock = pair()
    a, b = s.seal('L5', {'n': 1}), s.seal('L5', {'n': 2})
    state.write_text(json.dumps({'pc': b['seq']}))
    o = E.Opener(KEY, E.PC2M, state, clock)
    for env in (a, b):
        with pytest.raises(E.EnvelopeError):
            o.open(env)
    assert o.open(s.seal('L5', {'n': 3}))[1] == {'n': 3}


def test_sequence_monotonic_even_if_clock_goes_back():
    clock = Clock()
    s = E.Sealer(KEY, E.PC2M, 'pc', clock)
    a = s.seal('L1', {})
    clock.t -= 60
    b = s.seal('L1', {})
    assert b['seq'] > a['seq']


def test_bad_format_and_unknown_kind():
    _, o, _ = pair()
    for bad in ({}, {'v': 2}, {'v': 2, 'dev': 'pc', 'kind': 'L9', 'seq': 1, 'ts': 1, 'n': 'AA==', 'ct': 'AA=='}):
        with pytest.raises(E.EnvelopeError):
            o.open(bad)
    with pytest.raises(ValueError):
        pair()[0].seal('L9', {})


def test_fixed_vector_for_android(monkeypatch):
    """固定密钥、随机数、时间 → 固定密文。手机端 Kotlin 单元测试读同一个文件对照（跨语言格式一致）。"""
    monkeypatch.setattr(E.os, 'urandom', lambda n: bytes([7]) * n)
    s = E.Sealer(KEY, E.M2PC, 'phone', Clock(1_800_000_000.0))
    env = s.seal('L5', {'op': 'send_text', 'text': '你好'})
    want = {'pair_key_b64': base64.b64encode(KEY).decode(), 'info': E.M2PC.decode(), 'salt': 'palm-inspire',
            'derived_key_b64': base64.b64encode(E.derive(KEY, E.M2PC)).decode(),
            'aad': E.aad_of(env['v'], env['dev'], env['kind'], env['seq'], env['ts']).decode(),
            'plaintext': json.dumps({'op': 'send_text', 'text': '你好'}, ensure_ascii=False), 'envelope': env}
    if not VECTOR.exists():                                        # 首次生成；之后任何格式变动都会让这里变红
        VECTOR.parent.mkdir(exist_ok=True)
        VECTOR.write_text(json.dumps(want, ensure_ascii=False, indent=1), encoding='utf-8')
    assert json.loads(VECTOR.read_text(encoding='utf-8')) == want
    assert E.Opener(KEY, E.M2PC, None, Clock(1_800_000_000.0)).open(env) == ('L5', {'op': 'send_text', 'text': '你好'})


def test_hkdf_matches_manual_rfc5869():
    """手机端不引第三方库，按 RFC 5869 用 HmacSHA256 手写 HKDF；这里用标准库同样手写一遍，必须一致。"""
    import hashlib
    import hmac
    prk = hmac.new(b'palm-inspire', KEY, hashlib.sha256).digest()
    okm = hmac.new(prk, E.M2PC + b'\x01', hashlib.sha256).digest()
    assert okm == E.derive(KEY, E.M2PC)
