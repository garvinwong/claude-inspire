"""测试用的假终端界面：带颜色与中文的“选择模型”菜单；下方向键移动选中项（反色）；窗口变化时整屏重画；q 退出。"""
import os
import signal
import tty

items = ['Opus', 'Sonnet', 'Haiku']
sel = [0]


def draw(*_):
    out = '\x1b[2J\x1b[H\x1b[31m红色标题\x1b[0m 选择模型\r\n'
    for i, it in enumerate(items):
        out += ('\x1b[7m❯ ' + it + '\x1b[0m') if i == sel[0] else ('  ' + it)
        out += '\r\n'
    os.write(1, out.encode())


tty.setraw(0)
signal.signal(signal.SIGWINCH, draw)
draw()
while True:
    try:
        d = os.read(0, 64)
    except InterruptedError:
        continue
    if not d or d == b'q':
        break
    if d == b'\x1b[B':
        sel[0] = min(len(items) - 1, sel[0] + 1)
        draw()
