# Claude Inspire

> Unofficial project. Not affiliated with or endorsed by Anthropic. Claude and Claude Code are trademarks of Anthropic.

[中文](README.md)

Take over the Claude Code sessions running on your computer from an Android phone: watch progress, chime in, approve tool requests, resume old sessions — while the desktop terminal stays live at the same time. Step away from your desk without leaving Claude stuck on a "may I continue?" prompt.

<p align="center">
<img src="docs/screenshots/home.png" width="240"> <img src="docs/screenshots/session.png" width="240"> <img src="docs/screenshots/approval.png" width="240">
</p>
<p align="center">
<img src="docs/screenshots/actions.png" width="240"> <img src="docs/screenshots/stats.png" width="240"> <img src="docs/screenshots/settings.png" width="240">
</p>
<p align="center"><sub>Home · Conversation · Approval · Session actions · Stats · Settings (demo data)</sub></p>

## Features

- **Session overview** — every Claude Code session on your machine on one screen, sorted by recent activity. Running sessions get a soft shimmer and a "now doing…" line.
- **Chime in** — send messages, photos and files to a session from the phone; they show up in the desktop terminal too. If you left an unsent draft in the desktop prompt, the phone tells you and can clear it before sending.
- **Approvals** — permission requests, multiple-choice questions and plan reviews slide up from the bottom, reachable with one thumb. New ones vibrate and ring; when the app is in the background they arrive as system notifications (approve or "always allow this session" right from the notification). Dangerous commands (force push, `rm -rf`, `sudo`…) require a long press to approve; rejecting always requires a long press.
- **Terminal view** — the session's real terminal screen, with a key pad for arrows, Enter and Esc.
- **Long-press a session** — open it in a terminal window on the computer, interrupt the current step, keep it alive in the background, YOLO mode (auto-approve tool requests; questions and plans still ask you), or slide to close it.
- **Resume & new** — resume or fork past sessions into a background session; start new ones with directory, model and effort.
- **Usage** — 5-hour / 7-day quota bars; long-press the card to refresh (runs one headless Claude Code call to read live usage).
- **Stats** — daily tokens, working hours, top tools, model mix.

## How it works

```
 Android app ──(LAN / Tailscale, end-to-end encrypted)──▶ LAN mailbox ─┐
                                                                       │  computer (Linux / WSL / macOS)
                          palm-hub (session hub, 127.0.0.1) ◀──────────┘
                            │                     │
          palm-host (pty wrapper around claude)   Agents Island bridge (session state & approval hooks)
```

- **palm-host** — `claude` starts through it; it wraps Claude Code in a pseudo-terminal so the phone and the desktop terminal can both type and both see output.
- **palm-hub** — collects sessions, approvals and usage, and runs a small built-in mailbox on your LAN. Every message between phone and hub is end-to-end encrypted with a key generated at pairing time; the mailbox only ever sees ciphertext.
- **Session list and approvals** come from the bridge and hooks of [Agents Island](https://github.com/garvinwong/agents-island) (same author, MIT). Only the bridge and hooks are needed, not its desktop UI.

## Requirements

- Computer: Linux, Windows WSL2 or macOS; Python 3.10+; a signed-in [Claude Code](https://docs.anthropic.com/en/docs/claude-code).
- Agents Island bridge and Claude Code hooks installed and running (see its README).
- Phone: Android 10 or later. **The app's interface is currently Chinese only.**
- To build the app: JDK 17, Android SDK 35.

## Install

1. **Install the Agents Island bridge and hooks** (only the bridge and hooks, not the desktop UI)
   ```bash
   git clone https://github.com/garvinwong/agents-island.git ~/agents-island
   cd ~/agents-island
   python3 scripts/install_hooks.py --permission-request   # route approvals and permission prompts through the bridge
   bash launch/start_bridge.sh                              # start the bridge (127.0.0.1:5599)
   ```

2. **Get the code and dependencies**
   ```bash
   git clone https://github.com/garvinwong/claude-inspire.git ~/claude-inspire
   python3 -m pip install --user -r ~/claude-inspire/requirements.txt
   ```
   Dependencies are `cryptography`, `pyte` and `qrcode`. Recent Debian/Ubuntu refuse `pip install` (PEP 668): add `--break-system-packages`, or use system packages (`sudo apt install python3-cryptography python3-pyte python3-qrcode`). The hub service runs with the system `/usr/bin/python3`, so install the dependencies for that interpreter, not only in a virtualenv.

3. **Start `claude` through the host** — add this line to the end of `~/.bashrc`, then open a new terminal:
   ```bash
   source ~/claude-inspire/host/palm.sh
   ```
   It defines the `claude` wrapper and the `palm` command (both are shell functions, available only in shells that sourced this line). Only interactive launches are wrapped; `claude -p` and friends are untouched. Bypass once with `PALM_HOST=0 claude`. Tested in bash; zsh is supported by the code but untested.

4. **Install the hub** (systemd user service)
   ```bash
   bash ~/claude-inspire/systemd/install.sh
   loginctl enable-linger $USER   # optional: keep running without a login session
   ```
   Without systemd (e.g. macOS), keep `python3 ~/claude-inspire/hub/palm_hub.py` running yourself, and restart that process wherever the steps below say `systemctl`.

5. **Build and install the app**
   ```bash
   cd ~/claude-inspire/android
   echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # your Android SDK location (or set ANDROID_HOME)
   ./gradlew assembleRelease
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
   The build is signed with the debug key — fine for your own phone.

6. **Pair**
   ```bash
   palm pair --qr          # scan the QR code with the phone camera, then tap "confirm" in the app
   systemctl --user restart palm-hub
   ```
   If the computer has several addresses, pick the one the phone can reach: `palm pair --qr --host 192.168.1.20`.

7. **Away from home** — install [Tailscale](https://tailscale.com/) on both computer and phone (same account) and pair with the computer's Tailscale address (100.x.x.x).

8. **Stay connected** — after pairing, the app asks to "run in the background"; allow it, otherwise some phones cut the connection when the screen is locked and approvals won't arrive. Settings → "Stay connected when locked" shows the current state.

### WSL users

WSL2 uses NAT by default, so the phone cannot reach addresses inside WSL (and `palm pair` will pick such an internal address). Either:
- set `networkingMode=mirrored` under `[wsl2]` in `%UserProfile%\.wslconfig`, run `wsl --shutdown`, then pair with the computer's LAN address; or
- install Tailscale inside WSL and pair with its address (100.x.x.x).

## Commands

| Command | What it does |
|---|---|
| `palm` / `palm ls` | List sessions running through the host |
| `palm attach <host-id>` | Attach this terminal to a background session (closing the window only detaches) |
| `palm pair [--qr] [--host ADDR] [--name NAME] [--adb SERIAL] [--rotate]` | Pair a phone; `--name` is the computer name shown on the phone; `--adb` sends the pairing link over adb; `--rotate` issues new tokens and key |
| `palm hub` | Show the hub service status (systemd) |
| `palm doctor` | Health check: host, hub, bridge, pairing |

Environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `PALM_LAN_BIND` / `PALM_LAN_PORT` | `0.0.0.0` / `5613` | Mailbox listen address. Prefer your LAN or Tailscale address; don't let it listen on a public interface |
| `PALM_HUB_PORT` | `5612` | Hub's local API port (127.0.0.1 only) |
| `PALM_BRIDGE_URL` | `http://127.0.0.1:5599` | Agents Island bridge |
| `PALM_HOME_DIR` | `~/.palm` | Pairing, tokens, caches, inbox |
| `PALM_INBOX_DIR` | `~/.palm/inbox` | Files sent from the phone (owner-only, auto-deleted after 7 days) |
| `PALM_RL_DIR` | `~/.agents-island` | Optional: quota cache written by the Agents Island status-line hook |
| `PALM_TERMINAL` | auto | Terminal command for "open on computer", e.g. `gnome-terminal -- bash -lc {cmd}` |
| `PALM_ADB` | `adb` | adb used by `palm pair --adb` |

## Security

- Tokens and the key are generated locally: `~/.palm/pair-tokens.json` and `link.json` hold them in clear (mode 600, readable only by you); the mailbox's device table `mailbox.json` stores only token hashes.
- All messages and attachments between phone and computer are end-to-end encrypted (AES-256-GCM with replay-protected sequence numbers and a time window). The mailbox speaks plain HTTP and cannot read content. Be aware, though: someone on the same network who captures a token can't read or forge messages, but *can* drain the phone's mailbox first (approvals then never arrive), delete pending attachments, and see when and how much is sent. **Use it only on trusted networks, and use Tailscale when away**; if anything looks off, run `palm pair --rotate`.
- The hub's local API listens on 127.0.0.1 only and requires a token (`~/.palm/hub.token`). The mailbox listens on all interfaces by default — restrict it with `PALM_LAN_BIND` if the computer has a public address.
- Files sent from the phone go to `~/.palm/inbox/` (directory 700, files 600) and are deleted after 7 days.
- The phone cannot start sessions in `bypassPermissions` mode. YOLO mode applies only to the session you pick and can be turned off any time.
- "Refresh usage" runs only when you tap it, at most once per 60 seconds. **Do not turn it into a scheduled job** — automated polling of subscription CLIs may get an account restricted.

## License

[PolyForm Noncommercial 1.0.0](LICENSE.md): free for personal use, study and research, and for charitable, educational and government organizations. Non-commercial sharing and redistribution are allowed as long as the license and notice travel with it. **Any commercial use needs a separate license from the author** — including use inside a for-profit company, bundling into a commercial product or service, or paid distribution. Contact via [GitHub](https://github.com/garvinwong).

Third-party components: see [THIRD_PARTY.md](THIRD_PARTY.md).

---

Powered by [Garvin Wong](https://github.com/garvinwong)
