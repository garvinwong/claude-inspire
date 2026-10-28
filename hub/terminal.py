"""掌上灵感 · 终端视图

手机进入某会话的终端视图时，中枢才去旁听该会话宿主的屏幕字节，用 pyte（LGPL-3，只在本机用）还原当前整屏。
对话视图没有原生控件的终端交互（模型菜单、信任目录、MCP 同意等）都在这里看、用按键作答。

快照格式：{rows, cols, cursor: {x, y}, seq, lines: [[[文字, 前景, 背景, 粗体, 反色], ...], ...]}
  每行按“属性相同”合并成段；宽字符（中文）占两格，第二格是占位，已跳过。
空闲 IDLE 秒没人取快照就断开旁听；宿主窗口大小变了自动跟着改。
"""
import base64
import json
import socket
import threading
import time

import pyte

IDLE = 120.0
FIRST_WAIT = 0.6          # 第一次取快照时等重画到达


class TermView:
    def __init__(self, host: dict, idle=IDLE):
        self.host = host
        self.idle = idle
        self.rows, self.cols = int(host.get('rows') or 40), int(host.get('cols') or 120)
        self.screen = pyte.Screen(self.cols, self.rows)
        self.stream = pyte.ByteStream(self.screen)
        self.lock = threading.Lock()
        self.seq = 0
        self.last_access = time.time()
        self.alive = True
        self.error = ''
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.thread.start()

    def _call(self, req: dict) -> dict:
        try:
            c = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            c.settimeout(2)
            c.connect(self.host['sock'])
            c.sendall(json.dumps(req).encode() + b'\n')
            buf = b''
            while b'\n' not in buf:
                chunk = c.recv(65536)
                if not chunk:
                    break
                buf += chunk
            c.close()
            return json.loads(buf.split(b'\n', 1)[0])
        except (OSError, ValueError):
            return {}

    def _run(self):
        try:
            s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            s.settimeout(1.0)
            s.connect(self.host['sock'])
            s.sendall(b'{"op":"subscribe"}\n')
            self._call({'op': 'redraw'})              # 促使界面整屏重画，拿到完整当前屏
            buf = b''
            while self.alive and time.time() - self.last_access < self.idle:
                try:
                    chunk = s.recv(262144)
                except socket.timeout:
                    continue
                if not chunk:
                    break
                buf += chunk
                while b'\n' in buf:
                    line, buf = buf.split(b'\n', 1)
                    try:
                        ev = json.loads(line)
                    except ValueError:
                        continue
                    if ev.get('ev') == 'out':
                        with self.lock:
                            self.stream.feed(base64.b64decode(ev['b64']))
                            self.seq += 1
                    elif ev.get('ev') == 'exit':
                        self.alive = False
            s.close()
        except OSError as e:
            self.error = str(e)
        self.alive = False

    def _sync_size(self):
        st = self._call({'op': 'status'})
        r, c = st.get('rows'), st.get('cols')
        if r and c and (r, c) != (self.rows, self.cols):
            with self.lock:
                self.rows, self.cols = r, c
                self.screen.resize(r, c)
                self.seq += 1

    def snapshot(self) -> dict:
        first = self.seq == 0
        self.last_access = time.time()
        self._sync_size()
        if first:
            end = time.time() + FIRST_WAIT
            while self.seq == 0 and time.time() < end and self.alive:
                time.sleep(0.05)
        with self.lock:
            lines = []
            for y in range(self.rows):
                row = self.screen.buffer[y]
                segs, cur = [], None
                for x in range(self.cols):
                    ch = row[x]
                    if ch.data == '':                  # 宽字符的第二格
                        continue
                    attr = (ch.fg, ch.bg, bool(ch.bold), bool(ch.reverse))
                    if cur and cur[1:] == list(attr):
                        cur[0] += ch.data
                    else:
                        cur = [ch.data, *attr]
                        segs.append(cur)
                if segs and segs[-1][1:] == ['default', 'default', False, False]:
                    segs[-1][0] = segs[-1][0].rstrip()
                    if not segs[-1][0]:
                        segs.pop()
                lines.append(segs)
            return {'rows': self.rows, 'cols': self.cols, 'seq': self.seq, 'alive': self.alive,
                    'cursor': {'x': self.screen.cursor.x, 'y': self.screen.cursor.y}, 'lines': lines}

    @staticmethod
    def plain(snap: dict) -> str:
        return '\n'.join(''.join(seg[0] for seg in line) for line in snap['lines'])


class TermViews:
    """每个宿主最多一个旁听；过期或宿主没了就重建。"""

    def __init__(self, idle=IDLE):
        self.idle = idle
        self.views = {}
        self.lock = threading.Lock()

    def get(self, host: dict) -> TermView:
        with self.lock:
            v = self.views.get(host['id'])
            if v is None or not v.alive:
                v = TermView(host, self.idle)
                self.views[host['id']] = v
            return v
