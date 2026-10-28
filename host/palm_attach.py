#!/usr/bin/env python3
"""掌上灵感 · 电脑接入后台会话（palm attach）

用法：palm_attach.py <宿主号或其前缀>
  接上后键盘输入按“本机输入”交给会话（参与仲裁），屏幕跟随会话；窗口大小以本终端为准。
  Ctrl+\\ 断开（会话继续在后台跑，手机照常可用）。
"""
import base64
import fcntl
import json
import os
import select
import signal
import socket
import struct
import sys
import termios
import tty
from pathlib import Path

PALM_HOME = Path(os.environ.get('PALM_HOME_DIR') or '~/.palm').expanduser()
DETACH = b'\x1c'          # Ctrl+\


def find_host(prefix: str):
    hits = []
    for p in (PALM_HOME / 'run').glob('*.json'):
        try:
            d = json.loads(p.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            continue
        if d.get('id', '').startswith(prefix):
            hits.append(d)
    return hits


def send(sock, obj):
    sock.sendall(json.dumps(obj).encode() + b'\n')


def main(argv=None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) != 1:
        print('用法：palm attach <宿主号>（palm ls 查看）', file=sys.stderr)
        return 2
    hits = find_host(argv[0])
    if len(hits) != 1:
        print('没找到这个宿主' if not hits else f'前缀不唯一：{", ".join(h["id"] for h in hits)}', file=sys.stderr)
        return 1
    h = hits[0]
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    try:
        s.connect(h['sock'])
    except OSError as e:
        print(f'连不上宿主：{e}', file=sys.stderr)
        return 1
    tty_in = os.isatty(0)

    def size_msg():
        try:
            r, c = struct.unpack('HHHH', fcntl.ioctl(0, termios.TIOCGWINSZ, b'\0' * 8))[:2]
            return {'op': 'resize', 'rows': r, 'cols': c}
        except OSError:
            return None

    send(s, {'op': 'subscribe'})
    m = size_msg() if tty_in else None
    if m:
        send(s, m)
    send(s, {'op': 'redraw'})
    winch = [False]
    signal.signal(signal.SIGWINCH, lambda *_: winch.__setitem__(0, True))
    old = termios.tcgetattr(0) if tty_in else None
    if tty_in:
        tty.setraw(0)
    buf, code, msg = b'', 0, '已断开，会话仍在后台运行。'
    try:
        while True:
            if winch[0]:
                winch[0] = False
                m = size_msg()
                if m:
                    send(s, m)
            try:
                r, _, _ = select.select([0, s], [], [], 1.0)
            except InterruptedError:
                continue
            if 0 in r:
                data = os.read(0, 65536)
                if not data:
                    break
                if DETACH in data:
                    data = data.split(DETACH, 1)[0]
                    if data:
                        send(s, {'op': 'input', 'b64': base64.b64encode(data).decode()})
                    break
                send(s, {'op': 'input', 'b64': base64.b64encode(data).decode()})
            if s in r:
                chunk = s.recv(262144)
                if not chunk:
                    msg, code = '宿主已断开。', 1
                    break
                buf += chunk
                while b'\n' in buf:
                    line, buf = buf.split(b'\n', 1)
                    try:
                        ev = json.loads(line)
                    except ValueError:
                        continue
                    if ev.get('ev') == 'out':          # 补发的旧输出（replay）不画：等整屏重画更干净
                        os.write(1, base64.b64decode(ev['b64']))
                    elif ev.get('ev') == 'exit':
                        msg, code = f'会话已结束（退出码 {ev.get("code")}）。', 0
                        raise SystemExit
    except SystemExit:
        pass
    finally:
        if old is not None:
            termios.tcsetattr(0, termios.TCSAFLUSH, old)
        s.close()
    print(f'\r\n[掌上灵感] {msg}')
    return code


if __name__ == '__main__':
    sys.exit(main())
