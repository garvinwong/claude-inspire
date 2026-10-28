"""测试用的假会话：像终端界面程序一样进入原始模式，把收到的每块字节以十六进制回显。
收到 QUIT 以 FAKE_EXIT（默认 7）退出；窗口大小变化时打印 SIZE 行。"""
import fcntl
import os
import signal
import struct
import termios
import tty


def size():
    r, c = struct.unpack('HHHH', fcntl.ioctl(0, termios.TIOCGWINSZ, b'\0' * 8))[:2]
    return f'{r}x{c}'


tty.setraw(0)
signal.signal(signal.SIGWINCH, lambda *_: os.write(1, f'SIZE {size()}\r\n'.encode()))
os.write(1, f"READY id={os.environ.get('PALM_HOST_ID', '')} size={size()}\r\n".encode())
seen = b''
while True:
    try:
        d = os.read(0, 4096)
    except InterruptedError:
        continue
    if not d:
        break
    os.write(1, b'GOT ' + d.hex().encode() + b'\r\n')
    seen += d
    if b'QUIT' in seen:
        os._exit(int(os.environ.get('FAKE_EXIT', '7')))
