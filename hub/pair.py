#!/usr/bin/env python3
"""掌上灵感 · 配对（palm pair）

用法：palm pair [--host 地址] [--qr] [--adb 序列号] [--name 电脑名] [--rotate]
  1. 本机生成两个设备令牌（中枢 palm-pc、手机 phone）与配对密钥（32 字节随机数）：
     令牌摘要写 ~/.palm/mailbox.json（局域网信箱的设备表），中枢侧配置写 ~/.palm/link.json，都是 600；
     已有就沿用，--rotate 全部换新（旧手机须重新配对）。
  2. 手机连电脑的地址：--host 指定（局域网 IP 或 Tailscale 地址）；不指定就自动挑一个（Tailscale 优先）。
  3. 手机侧配置：--qr 在终端显示二维码（深链接 claudeinspire://pair#…，用手机相机扫）；
     --adb 经 adb 发深链接给 App。App 弹窗确认后才保存。
令牌与密钥绝不以明文打印到终端；二维码只在本机屏幕上显示。
"""
import argparse
import base64
import hashlib
import ipaddress
import json
import os
import secrets
import socket
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from mailbox import DEFAULT_PORT  # noqa: E402

PKG = 'io.github.garvinwong.claudeinspire'
PC_NAME, PHONE_NAME = 'palm-pc', 'phone'
TAILSCALE = ipaddress.ip_network('100.64.0.0/10')


def write_private(path: Path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix('.tmp')
    old = os.umask(0o177)
    try:
        tmp.write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding='utf-8')
    finally:
        os.umask(old)
    os.replace(tmp, path)


def candidate_hosts() -> list:
    """本机可能被手机连到的 IPv4：Tailscale（100.64/10）在前，其次局域网私有地址。"""
    found = []
    try:
        out = subprocess.run(['hostname', '-I'], capture_output=True, text=True, timeout=5).stdout.split()
    except (OSError, subprocess.SubprocessError):
        out = []
    if not out:
        try:
            out = [ai[4][0] for ai in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)]
        except OSError:
            out = []
    for a in out:
        try:
            ip = ipaddress.ip_address(a)
        except ValueError:
            continue
        if ip.version == 4 and not ip.is_loopback and (ip in TAILSCALE or ip.is_private) and a not in found:
            found.append(a)
    return sorted(found, key=lambda a: 0 if ipaddress.ip_address(a) in TAILSCALE else 1)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description='掌上灵感配对')
    ap.add_argument('--host', help='手机连电脑用的地址（局域网 IP 或 Tailscale 地址）')
    ap.add_argument('--port', type=int, default=int(os.environ.get('PALM_LAN_PORT') or DEFAULT_PORT))
    ap.add_argument('--qr', action='store_true', help='在终端显示配对二维码')
    ap.add_argument('--adb', metavar='序列号', help='经 adb 把配对深链接发给手机上的 App')
    ap.add_argument('--rotate', action='store_true', help='令牌与密钥全部换新（旧手机须重新配对）')
    ap.add_argument('--name', help='手机上显示的电脑名（默认本机主机名）')
    args = ap.parse_args(argv)
    home = Path(os.environ.get('PALM_HOME_DIR') or '~/.palm').expanduser()
    link_path, dev_path, tok_path = home / 'link.json', home / 'mailbox.json', home / 'pair-tokens.json'
    try:
        toks = {} if args.rotate else json.loads(tok_path.read_text(encoding='utf-8'))
    except (OSError, ValueError):
        toks = {}
    fresh = not toks
    toks = {'pc': toks.get('pc') or secrets.token_urlsafe(32), 'phone': toks.get('phone') or secrets.token_urlsafe(32),
            'key': toks.get('key') or base64.b64encode(os.urandom(32)).decode()}
    write_private(tok_path, toks)
    write_private(dev_path, {'devices': {
        PC_NAME: {'role': 'pc', 'sha256': hashlib.sha256(toks['pc'].encode()).hexdigest()},
        PHONE_NAME: {'role': 'client', 'sha256': hashlib.sha256(toks['phone'].encode()).hexdigest()}}})
    write_private(link_path, {'relay_url': f'http://127.0.0.1:{args.port}', 'token': toks['pc'], 'me': PC_NAME,
                              'peer': PHONE_NAME, 'pair_key_b64': toks['key']})
    print(f"配对配置已写入 {home}（权限 600）{'，已生成新令牌与密钥' if fresh else ''}")
    host = args.host
    if not host:
        hosts = candidate_hosts()
        if not hosts:
            sys.exit('找不到本机的局域网 / Tailscale 地址，请用 --host 指定手机连电脑的地址')
        host = hosts[0]
        if len(hosts) > 1:
            print(f"本机有多个地址：{', '.join(hosts)}；用了 {host}（不对就加 --host 指定）")
    phone = {'v': 1, 'relay': f'http://{host}:{args.port}', 'token': toks['phone'], 'me': PHONE_NAME,
             'peer': PC_NAME, 'key': toks['key'], 'pc': args.name or socket.gethostname()}
    url = 'claudeinspire://pair#' + base64.urlsafe_b64encode(json.dumps(phone, ensure_ascii=False).encode()).decode().rstrip('=')
    if args.adb:
        adb = os.environ.get('PALM_ADB', 'adb')
        q = subprocess.run([adb, '-s', args.adb, 'shell', 'am', 'start', '-a', 'android.intent.action.VIEW',
                            '-d', f"'{url}'", PKG], text=True, capture_output=True, timeout=30)
        if q.returncode != 0 or 'Error' in q.stdout + q.stderr:
            sys.exit(f'adb 发送失败：{(q.stderr or q.stdout).strip()[-200:]}')
        print(f'已把配对信息发到手机（{args.adb}）：请在手机上点“确认配对”')
    if args.qr:
        try:
            import qrcode
        except ImportError:
            sys.exit('没装 qrcode（pip install qrcode）')
        q = qrcode.QRCode(border=2, error_correction=qrcode.constants.ERROR_CORRECT_L)
        q.add_data(url)
        q.print_ascii(invert=True)
        print('用手机相机扫上面的二维码（内含密钥，扫完请清屏）')
    if not args.adb and not args.qr:
        print('手机侧：加 --qr 显示二维码，或 --adb <序列号> 经 adb 发给手机')
    print(f'手机将连 http://{host}:{args.port}；首次配对或 --rotate 后要重启中枢：systemctl --user restart palm-hub')
    return 0


if __name__ == '__main__':
    sys.exit(main())
