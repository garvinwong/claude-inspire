"""palm doctor 体检测试：全通过、各项故障能被认出来（中枢 / 桥 / 中转 / 配对 / 权限），只读不改东西。"""
import base64
import json
import os
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'hub'))
import doctor  # noqa: E402

PALM_DIR = Path(__file__).resolve().parents[1]


class Fake:
    """一个端口同时扮中枢（/api/health、/api/link）、灵动岛桥（/api/state）、中转（/v2/peers）。"""

    def __init__(self):
        self.link = {'ok': True, 'paired': True, 'url': 'http://relay', 'poll_age': 3, 'in': 5, 'out': 9, 'bad': 0}
        self.peers_code, self.phone_age = 200, 12
        fake = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                if self.path == '/api/health':
                    body, code = {'ok': True, 'version': '9.9'}, 200
                elif self.path == '/api/link':
                    body, code = (fake.link, 200) if self.headers.get('X-Palm-Token') == 't' * 40 else ({}, 401)
                elif self.path == '/api/state':
                    body, code = {'sessions': {'claude': [{}, {}]}}, 200
                elif self.path == '/v2/peers':
                    body, code = {'peers': {'phone': fake.phone_age}}, fake.peers_code
                else:
                    body, code = {}, 404
                data = json.dumps(body).encode()
                self.send_response(code)
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        self.srv = ThreadingHTTPServer(('127.0.0.1', 0), H)
        self.port = self.srv.server_address[1]
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()


@pytest.fixture
def setup(tmp_path):
    fake = Fake()
    home, palm = tmp_path / 'home', tmp_path / 'home' / '.palm'
    palm.mkdir(parents=True)
    (home / '.bashrc').write_text('alias ll=ls\n[ -f x/palm.sh ] && source x/palm.sh\n', encoding='utf-8')
    (palm / 'hub.token').write_text('t' * 40 + '\n')
    link = palm / 'link.json'
    link.write_text(json.dumps({'relay_url': f'http://127.0.0.1:{fake.port}', 'token': 'x', 'me': 'palm-pc', 'peer': 'phone',
                                'pair_key_b64': base64.b64encode(b'k' * 32).decode()}))
    link.chmod(0o600)
    kw = dict(home=home, palm_home=palm, palm_dir=PALM_DIR, port=fake.port, bridge=f'http://127.0.0.1:{fake.port}',
              inbox=tmp_path / 'inbox', service=False)
    yield fake, kw, palm
    fake.srv.shutdown()


def levels(rows):
    return {item: level for level, item, _ in rows}


def test_all_pass(setup):
    fake, kw, _ = setup
    rows = doctor.run(**kw)
    assert all(level == doctor.OK for level, _, _ in rows), rows
    assert {'启动包装', '中枢接口', '灵动岛桥', '手机配对', '中转链路', '手机', '附件收件', '经宿主的会话'} <= set(levels(rows))


def test_detects_failures(setup, tmp_path):
    fake, kw, palm = setup
    (Path(kw['home']) / '.bashrc').write_text('# source palm.sh 注释掉了\n', encoding='utf-8')
    fake.link['poll_age'] = 300                                  # 中枢取件卡住
    fake.peers_code = 401                                        # 本机令牌被吊销
    (palm / 'link.json').chmod(0o644)
    lv = levels(doctor.run(**dict(kw, bridge='http://127.0.0.1:9')))
    assert lv['启动包装'] == doctor.WARN and lv['灵动岛桥'] == doctor.FAIL
    assert lv['中转链路'] == doctor.FAIL and lv['手机'] == doctor.FAIL and lv['手机配对'] == doctor.WARN


def test_hub_down_and_unpaired(setup):
    fake, kw, palm = setup
    (palm / 'link.json').rename(palm / 'link.json.bak')
    lv = levels(doctor.run(**dict(kw, port=9)))
    assert lv['中枢接口'] == doctor.FAIL and lv['手机配对'] == doctor.WARN and '中转链路' not in lv


def test_old_hub_without_link_api_is_flagged(setup):
    fake, kw, _ = setup
    fake.link = None                                             # 旧版中枢没有 /api/link → 回 null
    lv = levels(doctor.run(**kw))
    assert lv['中转链路'] == doctor.FAIL


def test_background_hosts_counted(setup):
    fake, kw, palm = setup
    run = palm / 'run'
    run.mkdir()
    for i in range(2):
        (run / f'h{i}.json').write_text(json.dumps({'host_pid': os.getpid(), 'detached': True}))
    (run / 'dead.json').write_text(json.dumps({'host_pid': 999999999, 'detached': True}))
    level, _, detail = doctor.check_hosts(palm, bg_max=2)
    assert level == doctor.WARN and '后台 2 个' in detail


def test_long_poll_in_flight_counts_as_alive(setup):
    """10-26 实测：中枢刚重启时第一次长轮询（最长 25 秒）还没返回，poll_age 为空，被误报“从未成功取件”。"""
    fake, kw, _ = setup
    fake.link.update(poll_age=None, in_flight=3)
    assert levels(doctor.run(**kw))['中转链路'] == doctor.OK
    fake.link.update(poll_age=None, in_flight=None)              # 真连不上：没有在途请求、也没成功过
    assert levels(doctor.run(**kw))['中转链路'] == doctor.FAIL
    fake.link.update(poll_age=None, in_flight=90)                # 在途请求挂住超时
    assert levels(doctor.run(**kw))['中转链路'] == doctor.FAIL
