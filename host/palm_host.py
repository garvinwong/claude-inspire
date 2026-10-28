#!/usr/bin/env python3
"""掌上灵感 · 会话宿主 palm-host

用法：palm_host.py [--name 名称] [--id 宿主号] [--detached] [--size 行x列] -- <命令> [参数...]
      一般不直接调用：交互式由 palm.sh 的 claude 包装函数调用；后台模式由会话中枢调用（手机续接 / 新建）。
      --detached：没有本机终端（后台宿主），电脑可用 palm attach 接入；默认就“保持后台”。

做什么：
  1. 用伪终端拉起命令，把键盘与屏幕字节原样双向透传（原理同 script 命令），终端体验不变；
  2. 另开本机套接字（~/.palm/run/<宿主号>.sock，权限 600，只认同一用户），供会话中枢：
     注入文字（括号粘贴整块写入再补回车）、发按键、旁听屏幕字节、触发重绘、查状态；
  3. 仲裁：本机输入框有未发送内容时，注入默认被拒（中枢据此排队），可强制发送；
  4. 失败放行：伪终端建不起来就直接执行原命令；套接字或登记失败只记日志，透传照常。

子进程环境里带 PALM_HOST_ID，SessionStart 钩子据此把“宿主号 → 会话号”报给中枢。

套接字协议：每行一个 JSON 请求，每行一个 JSON 应答。
  {"op":"status"}
  {"op":"inject","text":"...","submit":true,"force":false,"clear":false}
      → {"ok":true[,"cleared":n]} 或 {"ok":false,"reason":"local_typing"|"local_draft","draft_chars":n,"idle_s":秒}
      force = 与电脑草稿合并发出；clear = 先清空电脑草稿再发（被清的字电脑上 Ctrl+Y 可找回）
  {"op":"keys","keys":["esc","shift_tab","down","enter","1"]}   名称见 KEYS；单个可见字符原样发送
  {"op":"redraw"}                 窗口高度临时减一再恢复，促使界面整屏重画
  {"op":"subscribe"}              先回 {"ev":"replay","b64":最近输出}，之后持续推 {"ev":"out","b64":...}，
                                  子进程退出时推 {"ev":"exit","code":n}
  {"op":"input","b64":...}        电脑接入端（palm attach）的键盘输入：按本机输入对待，参与仲裁
  {"op":"resize","rows":r,"cols":c}  只在没有本机终端时生效（后台宿主由接入端定大小）
  {"op":"keep_alive","on":true}   关掉本机终端时不结束会话，转为后台（之后可 palm attach）

关终端的语义：默认与今天一致——终端没了，子进程收到 SIGHUP 结束；开了“保持后台”才留下。
"""
import base64
import fcntl
import json
import os
import pty
import re
import select
import signal
import socket
import struct
import sys
import termios
import time
import tty
import uuid
from pathlib import Path

PALM_HOME = Path(os.environ.get('PALM_HOME_DIR') or '~/.palm').expanduser()
RUN_DIR = PALM_HOME / 'run'
LOG_FILE = PALM_HOME / 'host.log'
RING_MAX = 256 * 1024                 # 旁听者加入时补发的最近输出
CLIENT_BUF_MAX = 4 * 1024 * 1024      # 旁听者积压超过即断开，绝不拖慢会话
TYPING_WINDOW = float(os.environ.get('PALM_TYPING_WINDOW') or 10.0)   # 本机按键多少秒内算“正在输入”
PASTE_ENTER_DELAY = 0.08              # 括号粘贴结束到补回车的间隔
INJECT_MAX = 64 * 1024
PASTE_START, PASTE_END = b'\x1b[200~', b'\x1b[201~'

KEYS = {
    'esc': b'\x1b', 'enter': b'\r', 'tab': b'\t', 'shift_tab': b'\x1b[Z',
    'up': b'\x1b[A', 'down': b'\x1b[B', 'right': b'\x1b[C', 'left': b'\x1b[D',
    'backspace': b'\x7f', 'ctrl_c': b'\x03', 'space': b' ',
    'ctrl_u': b'\x15', 'ctrl_k': b'\x0b',
}


def clear_seq(draft_chars: int) -> bytes:
    """清空 Claude Code 输入框（10-27 用真实终端实测）：Ctrl+U 从光标往前删一行、Ctrl+K 往后删一行，
    各按若干下，光标停在哪都能清干净；空输入框多按没有副作用；被删的字电脑上可用 Ctrl+Y 找回。
    不用 Ctrl+C：输入框空着时它会打断正在跑的任务。"""
    n = min(200, max(8, draft_chars // 20 + 8))
    return b'\x15' * n + b'\x0b' * n


def log(msg: str):
    """只写日志文件，绝不往终端打字（会弄花用户屏幕）。"""
    try:
        PALM_HOME.mkdir(parents=True, exist_ok=True)
        with open(LOG_FILE, 'a', encoding='utf-8') as f:
            f.write(f'{time.strftime("%Y-%m-%d %H:%M:%S")} [{os.getpid()}] {msg}\n')
    except OSError:
        pass


# ── 本机输入跟踪（仲裁依据）─────────────────────────────

class LocalInput:
    """粗略估计本机输入框里还有多少未发送的字。

    宽字符按 UTF-8 首字节计 1；退格减 1；回车、Ctrl+C、Ctrl+U 清零；
    方向键等转义序列不计；终端粘贴（括号粘贴）里的内容照常计数，粘贴里的回车不算发送。
    这是估计，不求精确：用“上历史”调出的旧消息就数不到——所以只拿来挡注入，并且允许强制发送。
    """

    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.count = 0
        self.last_key = 0.0
        self._esc = b''          # 未完的转义序列
        self._in_paste = False

    def feed(self, data: bytes, local: bool = True):
        """local=False：手机发来的按键（退格、清行等）——照样改草稿计数，但不算“人在电脑前”（10-27 外出实见：
        手机上删了电脑草稿，计数却不变，消息一直排队）。"""
        if local:
            self.last_key = self.clock()
        for b in data:
            if self._esc:
                self._esc += bytes([b])
                seq = self._esc
                if seq == PASTE_START:
                    self._in_paste, self._esc = True, b''
                elif seq == PASTE_END:
                    self._in_paste, self._esc = False, b''
                elif PASTE_START.startswith(seq) or PASTE_END.startswith(seq):
                    pass                                                 # 可能是粘贴标记，继续收
                elif len(seq) == 2 and b not in (0x5b, 0x4f):            # ESC + 单字符（Alt 组合）
                    self._esc = b''
                elif seq[:2] == b'\x1b[' and len(seq) >= 3 and 0x40 <= b <= 0x7e:
                    self._esc = b''                                      # CSI 结束（方向键等）
                elif seq[:2] == b'\x1bO' and len(seq) >= 3:
                    self._esc = b''                                      # SS3（应用模式方向键）
                elif len(seq) > 16:
                    self._esc = b''
                continue
            if b == 0x1b:
                self._esc = b'\x1b'
                continue
            if b in (0x0d,) and not self._in_paste:
                self.count = 0
            elif b in (0x03, 0x15, 0x0b):
                self.count = 0
            elif b in (0x7f, 0x08):
                self.count = max(0, self.count - 1)
            elif b >= 0x20 or b == 0x0a or b == 0x0d:
                if b < 0x80 or (b & 0xC0) != 0x80:                       # UTF-8 续字节不重复计
                    self.count += 1
        if self._esc == b'\x1b':
            self._esc = b''                                              # 一次读到的单独 Esc 是按键本身

    def submitted(self):
        """注入的消息带回车发出：输入框里的内容随之一并提交。"""
        self.count = 0

    def state(self) -> dict:
        age = self.clock() - self.last_key if self.last_key else None
        typing = self.count > 0 and age is not None and age < TYPING_WINDOW
        return {'draft_chars': self.count, 'typing': typing,
                'last_key_age': None if age is None else round(age, 1)}


# ── 宿主 ────────────────────────────────────────────────────────

def _winsize(fd):
    try:
        return struct.unpack('HHHH', fcntl.ioctl(fd, termios.TIOCGWINSZ, b'\0' * 8))[:2]
    except OSError:
        return None


def _set_winsize(fd, rows, cols):
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack('HHHH', rows, cols, 0, 0))


class Client:
    def __init__(self, sock):
        self.sock = sock
        self.inbuf = b''
        self.outbuf = bytearray()
        self.subscribed = False


class Host:
    def __init__(self, argv, name='', host_id='', detached=False, size=None):
        self.argv = argv
        self.name = name
        self.id = host_id if re.fullmatch(r'[0-9a-f]{12}', host_id or '') else uuid.uuid4().hex[:12]
        self.detached = detached
        self.keep_alive = detached
        self.master = None
        self.child = None
        self.local = LocalInput()
        self.ring = bytearray()
        self.to_child = bytearray()
        self.timers = []                  # [(到期时刻, 动作)]
        self.clients = {}
        self.listener = None
        self.sock_path = RUN_DIR / f'{self.id}.sock'
        self.reg_path = RUN_DIR / f'{self.id}.json'
        self.rows, self.cols = size or (40, 120)
        self.attached = (not detached) and os.isatty(0)
        self.started = time.time()

    # 启动 ------------------------------------------------------------
    def spawn(self):
        size = _winsize(0) if self.attached else None
        if size and size[0] and size[1]:
            self.rows, self.cols = size
        env = dict(os.environ, PALM_HOST_ID=self.id)
        pid, fd = pty.fork()
        if pid == 0:                                  # 子进程：设好窗口大小后执行原命令
            try:
                _set_winsize(0, self.rows, self.cols)
            except OSError:
                pass
            try:
                os.execvpe(self.argv[0], self.argv, env)
            except OSError as e:
                os.write(2, f'palm-host: 无法执行 {self.argv[0]}: {e}\r\n'.encode())
            os._exit(127)
        self.child, self.master = pid, fd
        os.set_blocking(fd, False)

    def _short_sock_path(self) -> Path:
        """AF_UNIX 路径上限约 108 字节；登记目录太深时套接字退到 /tmp/palm-<uid>/（权限 700、须归本人）。"""
        if len(str(self.sock_path).encode()) <= 100:
            return self.sock_path
        d = Path(f'/tmp/palm-{os.getuid()}')
        d.mkdir(mode=0o700, exist_ok=True)
        st = os.lstat(d)
        if st.st_uid != os.getuid() or not os.path.isdir(d) or os.path.islink(d):
            raise OSError(f'{d} 不归本人或不是目录')
        os.chmod(d, 0o700)
        return d / f'{self.id}.sock'

    def open_socket(self):
        """套接字与登记表失败只记日志：透传照常（失败放行）。"""
        try:
            RUN_DIR.mkdir(parents=True, exist_ok=True)
            os.chmod(RUN_DIR, 0o700)
            self.sock_path = self._short_sock_path()
            s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            old = os.umask(0o177)
            try:
                s.bind(str(self.sock_path))
            finally:
                os.umask(old)
            s.listen(8)
            s.setblocking(False)
            self.listener = s
            self.write_registry()
        except OSError as e:
            log(f'套接字或登记失败，仅透传：{e}')
            self.listener = None

    def write_registry(self):
        info = {
            'id': self.id, 'name': self.name, 'host_pid': os.getpid(), 'child_pid': self.child,
            'argv': self.argv, 'cwd': os.getcwd(),
            'config_dir': os.environ.get('CLAUDE_CONFIG_DIR') or str(Path('~/.claude').expanduser()),
            'started_at': self.started, 'attached': self.attached, 'detached': self.detached,
            'keep_alive': self.keep_alive,
            'sock': str(self.sock_path), 'rows': self.rows, 'cols': self.cols,
        }
        tmp = self.reg_path.with_suffix('.tmp')
        tmp.write_text(json.dumps(info, ensure_ascii=False), encoding='utf-8')
        os.chmod(tmp, 0o600)
        os.replace(tmp, self.reg_path)

    def cleanup(self):
        for c in list(self.clients.values()):
            try:
                c.sock.close()
            except OSError:
                pass
        if self.listener:
            try:
                self.listener.close()
            except OSError:
                pass
        for p in (self.sock_path, self.reg_path):
            try:
                p.unlink()
            except OSError:
                pass

    # 主循环 ----------------------------------------------------------
    def run(self) -> int:
        wake_r, wake_w = os.pipe()
        os.set_blocking(wake_r, False)
        os.set_blocking(wake_w, False)
        signal.set_wakeup_fd(wake_w)
        self._winch = False
        self._fwd = []                               # 待转发给子进程的信号
        signal.signal(signal.SIGWINCH, lambda *_: setattr(self, '_winch', True))
        for sig in (signal.SIGTERM, signal.SIGHUP):
            signal.signal(sig, lambda s, _f: self._fwd.append(s))
        signal.signal(signal.SIGCHLD, lambda *_: None)   # 只为唤醒 select
        old_tty = None
        if self.attached:
            try:
                old_tty = termios.tcgetattr(0)
                tty.setraw(0)
            except termios.error:
                old_tty = None
        stdin_open = self.attached
        code = None
        try:
            while code is None:
                now = time.monotonic()
                self._run_timers(now)
                if self._winch:
                    self._winch = False
                    self._sync_winsize()
                while self._fwd:
                    sig = self._fwd.pop(0)
                    if sig == signal.SIGHUP and self.keep_alive:
                        self._go_background()
                        stdin_open = False
                        continue
                    try:
                        os.kill(self.child, sig)
                    except OSError:
                        pass
                rl = [self.master, wake_r]
                if stdin_open:
                    rl.append(0)
                if self.listener:
                    rl.append(self.listener)
                rl += [c.sock for c in self.clients.values()]
                wl = [self.master] if self.to_child else []
                wl += [c.sock for c in self.clients.values() if c.outbuf]
                timeout = 1.0
                if self.timers:
                    timeout = max(0.0, min(timeout, min(t for t, _ in self.timers) - now))
                try:
                    r, w, _ = select.select(rl, wl, [], timeout)
                except InterruptedError:
                    continue
                if wake_r in r:
                    try:
                        os.read(wake_r, 4096)
                    except OSError:
                        pass
                if self.master in r:
                    if not self._read_child():
                        code = self._reap(block=True)
                        break
                if 0 in r:
                    data = self._read_fd(0)
                    if data:
                        self.local.feed(data)
                        self.to_child += data
                    elif data == b'':
                        stdin_open = False           # 终端没了：等同今天关窗口
                        if self.keep_alive:
                            self._go_background()
                        else:
                            self._fwd.append(signal.SIGHUP)
                if self.master in w:
                    self._flush_child()
                if self.listener and self.listener in r:
                    self._accept()
                for c in list(self.clients.values()):
                    if c.sock in r:
                        self._client_read(c)
                    if c.sock in w and c.sock.fileno() in self.clients:
                        self._client_flush(c)
                if code is None:
                    code = self._reap(block=False)
            self._drain_child()                      # 子进程已退：把伪终端里剩下的输出读完再收尾
            self._broadcast({'ev': 'exit', 'code': code})
            for c in list(self.clients.values()):
                self._client_flush(c)
            return code
        finally:
            if old_tty is not None:
                try:
                    termios.tcsetattr(0, termios.TCSAFLUSH, old_tty)
                except termios.error:
                    pass
            self.cleanup()

    def _reap(self, block):
        try:
            pid, status = os.waitpid(self.child, 0 if block else os.WNOHANG)
        except ChildProcessError:
            return 0 if block else None
        if pid == 0:
            return None
        return os.waitstatus_to_exitcode(status) if status >= 0 else 1

    @staticmethod
    def _read_fd(fd):
        try:
            return os.read(fd, 65536)
        except BlockingIOError:
            return None
        except OSError:
            return b''

    def _read_child(self) -> bool:
        data = self._read_fd(self.master)
        if data is None:
            return True
        if not data:
            return False                              # 子进程关了伪终端（退出）
        if self.attached:
            try:
                os.write(1, data)                     # 本机屏幕：阻塞写，终端自己会消化
            except OSError:
                self.attached = False
        self.ring += data
        if len(self.ring) > RING_MAX:
            del self.ring[:len(self.ring) - RING_MAX]
        if any(c.subscribed for c in self.clients.values()):
            self._broadcast({'ev': 'out', 'b64': base64.b64encode(data).decode()})
        return True

    def _go_background(self):
        """本机终端没了但要保持会话：停止写屏，转后台宿主，等 palm attach。"""
        if self.attached:
            log(f'{self.id} 本机终端关闭，转为后台')
        self.attached = False
        self.detached = True
        try:
            self.write_registry()
        except OSError:
            pass

    def _drain_child(self):
        for _ in range(64):
            try:
                if not select.select([self.master], [], [], 0.05)[0]:
                    return
            except (OSError, ValueError):
                return
            if not self._read_child():
                return

    def _flush_child(self):
        try:
            n = os.write(self.master, self.to_child)
            del self.to_child[:n]
        except BlockingIOError:
            pass
        except OSError:
            self.to_child.clear()

    def _sync_winsize(self):
        size = _winsize(0) if self.attached else None
        if size and size[0] and size[1]:
            self.rows, self.cols = size
            try:
                _set_winsize(self.master, *size)
            except OSError:
                pass

    def _run_timers(self, now):
        due = [t for t in self.timers if t[0] <= now]
        if not due:
            return
        self.timers = [t for t in self.timers if t[0] > now]
        for _, action in sorted(due, key=lambda t: t[0]):
            try:
                action()
            except Exception as e:                    # 定时动作出错不能拖垮会话
                log(f'定时动作出错：{e}')

    # 套接字 ----------------------------------------------------------
    def _accept(self):
        try:
            s, _ = self.listener.accept()
        except OSError:
            return
        try:
            cred = s.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, struct.calcsize('3i'))
            _pid, uid, _gid = struct.unpack('3i', cred)
            if uid != os.getuid():
                s.close()
                return
        except OSError:
            s.close()
            return
        s.setblocking(False)
        self.clients[s.fileno()] = Client(s)

    def _drop(self, c):
        self.clients.pop(c.sock.fileno(), None)
        try:
            c.sock.close()
        except OSError:
            pass

    def _client_read(self, c):
        try:
            data = c.sock.recv(65536)
        except BlockingIOError:
            return
        except OSError:
            data = b''
        if not data:
            self._drop(c)
            return
        c.inbuf += data
        if len(c.inbuf) > INJECT_MAX * 2:
            self._drop(c)
            return
        while b'\n' in c.inbuf:
            line, c.inbuf = c.inbuf.split(b'\n', 1)
            if not line.strip():
                continue
            try:
                req = json.loads(line)
                resp = self.handle(req, c)
            except Exception as e:                    # 坏请求只回错，不影响会话
                resp = {'ok': False, 'reason': f'bad_request: {e}'}
            if resp is not None:
                self._send(c, resp)

    def _send(self, c, obj):
        c.outbuf += (json.dumps(obj, ensure_ascii=False) + '\n').encode()
        if len(c.outbuf) > CLIENT_BUF_MAX:
            self._drop(c)

    def _client_flush(self, c):
        try:
            n = c.sock.send(c.outbuf)
            del c.outbuf[:n]
        except BlockingIOError:
            pass
        except OSError:
            self._drop(c)

    def _broadcast(self, obj):
        for c in list(self.clients.values()):
            if c.subscribed:
                self._send(c, obj)

    # 请求处理 --------------------------------------------------------
    def handle(self, req, c):
        op = req.get('op')
        if op == 'status':
            return {'ok': True, 'id': self.id, 'rows': self.rows, 'cols': self.cols,
                    'attached': self.attached, 'detached': self.detached, 'keep_alive': self.keep_alive,
                    'local': self.local.state(),
                    'subscribers': sum(1 for x in self.clients.values() if x.subscribed)}
        if op == 'inject':
            text = str(req.get('text') or '')
            if not text or len(text.encode()) > INJECT_MAX:
                return {'ok': False, 'reason': 'bad_text'}
            st = self.local.state()
            cleared = 0
            if req.get('clear') and st['draft_chars'] > 0:      # 先清掉电脑输入框里没发的字，再发手机这条
                cleared = st['draft_chars']
                self.to_child += clear_seq(cleared)
            elif not req.get('force') and st['draft_chars'] > 0:
                return {'ok': False, 'reason': 'local_typing' if st['typing'] else 'local_draft',
                        'draft_chars': st['draft_chars'], 'idle_s': st['last_key_age']}
            clean = text.replace('\x1b', '')          # 粘贴内容里不许夹转义序列（防止提前结束粘贴）
            self.to_child += PASTE_START + clean.encode() + PASTE_END
            if req.get('submit', True):
                self.timers.append((time.monotonic() + PASTE_ENTER_DELAY,
                                    lambda: self.to_child.extend(b'\r')))
                self.local.submitted()
            elif cleared:
                self.local.count = 0
            return {'ok': True, 'cleared': cleared} if cleared else {'ok': True}
        if op == 'keys':
            seq = bytearray()
            for k in req.get('keys') or []:
                k = str(k)
                if k in KEYS:
                    seq += KEYS[k]
                elif len(k) == 1 and k.isprintable():
                    seq += k.encode()
                else:
                    return {'ok': False, 'reason': f'unknown_key: {k[:20]}'}
            self.to_child += seq
            self.local.feed(bytes(seq), local=False)     # 手机上的退格 / 清行也要改草稿计数
            return {'ok': True}
        if op == 'redraw':
            rows, cols = self.rows, self.cols
            try:
                _set_winsize(self.master, max(1, rows - 1), cols)
            except OSError:
                return {'ok': False, 'reason': 'resize_failed'}
            self.timers.append((time.monotonic() + 0.06, lambda: _set_winsize(self.master, rows, cols)))
            return {'ok': True}
        if op == 'input':
            try:
                data = base64.b64decode(str(req.get('b64') or ''), validate=True)
            except (ValueError, TypeError):
                return {'ok': False, 'reason': 'bad_b64'}
            if len(data) > INJECT_MAX:
                return {'ok': False, 'reason': 'too_large'}
            self.local.feed(data)
            self.to_child += data
            return {'ok': True}
        if op == 'resize':
            if self.attached:
                return {'ok': False, 'reason': 'local_terminal_owns_size'}
            try:
                rows, cols = int(req.get('rows')), int(req.get('cols'))
                assert 5 <= rows <= 500 and 20 <= cols <= 1000
                _set_winsize(self.master, rows, cols)
            except (TypeError, ValueError, AssertionError, OSError):
                return {'ok': False, 'reason': 'bad_size'}
            self.rows, self.cols = rows, cols
            return {'ok': True}
        if op == 'keep_alive':
            self.keep_alive = bool(req.get('on'))
            try:
                self.write_registry()
            except OSError:
                pass
            return {'ok': True, 'keep_alive': self.keep_alive}
        if op == 'subscribe':
            c.subscribed = True
            self._send(c, {'ev': 'replay', 'b64': base64.b64encode(bytes(self.ring)).decode()})
            return None
        return {'ok': False, 'reason': f'unknown_op: {op}'}


def main(argv=None) -> int:
    argv = list(sys.argv[1:] if argv is None else argv)
    opts = {'name': '', 'id': '', 'detached': False, 'size': None}
    while argv and argv[0] != '--':
        a = argv.pop(0)
        if a == '--detached':
            opts['detached'] = True
        elif a in ('--name', '--id', '--size') and argv:
            opts[a[2:]] = argv.pop(0)
        else:
            print(f'palm-host: 不认识的参数 {a}', file=sys.stderr)
            return 2
    if argv[:1] == ['--']:
        argv = argv[1:]
    if not argv:
        print('用法：palm_host.py [--name 名称] [--id 宿主号] [--detached] [--size 行x列] -- <命令> [参数...]', file=sys.stderr)
        return 2
    size = None
    if opts['size']:
        try:
            r, c = (int(x) for x in opts['size'].lower().split('x'))
            size = (r, c)
        except ValueError:
            size = None
    host = Host(argv, opts['name'], opts['id'], opts['detached'], size)
    try:
        host.spawn()
    except OSError as e:                              # 失败放行：伪终端起不来就直接执行原命令
        log(f'伪终端失败，直接执行：{e}')
        os.execvp(argv[0], argv)
    host.open_socket()
    try:
        return host.run()
    except Exception as e:                            # 主循环意外：记日志，等子进程自行结束
        log(f'主循环异常：{e!r}')
        try:
            _, status = os.waitpid(host.child, 0)
            return os.waitstatus_to_exitcode(status)
        except OSError:
            return 1


if __name__ == '__main__':
    sys.exit(main())
