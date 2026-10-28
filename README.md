# Claude Inspire · 掌上灵感

> 非 Anthropic 官方项目，与 Anthropic 无关联。Claude 与 Claude Code 是 Anthropic 的商标。

[English](README.en.md)

用安卓手机接管电脑上正在跑的 Claude Code 会话：看进度、插话、审批、续接，和电脑终端同时在线。离开工位也不会让 Claude 卡在一个“要不要继续？”上干等。

<p align="center">
<img src="docs/screenshots/home.png" width="240"> <img src="docs/screenshots/session.png" width="240"> <img src="docs/screenshots/approval.png" width="240">
</p>
<p align="center">
<img src="docs/screenshots/actions.png" width="240"> <img src="docs/screenshots/stats.png" width="240"> <img src="docs/screenshots/settings.png" width="240">
</p>
<p align="center"><sub>首页 · 对话 · 审批 · 长按会话 · 数据 · 设置（截图为演示数据）</sub></p>

## 能做什么

- **会话总览**：电脑上所有 Claude Code 会话一屏看完。在跑的卡片有微光提示，“正在…”一行写着它此刻在做什么；按最近活动排序。
- **插话**：在手机上直接给会话发消息、发图片和文件，电脑终端里同步出现。电脑输入框里有没发出的草稿时，手机会提示并可一键清掉再发。
- **审批**：工具权限请求、选择题、计划审阅从屏幕底部升起，单手可按；新来的会震动、响铃，App 不在前台时走系统通知（通知上可直接批准或“本会话始终允许”）。危险命令（强推、删除、sudo 等）批准须长按；拒绝须长按。
- **终端视图**：看会话的真实终端画面，用小键盘发方向键、回车、Esc。
- **长按会话**：在电脑上弹出终端窗口接上该会话、中断当前操作、保持后台、YOLO 模式（工具审批自动放行，选择题与计划照常问）、滑动关闭会话。
- **续接与新建**：把历史会话续接或分叉到电脑后台继续聊；在手机上选目录、模型、思考程度新建会话。
- **用量**：5 小时 / 7 天额度条；长按额度卡手动刷新（电脑上无头跑一次 Claude Code 读取实时用量）。
- **数据页**：每日 token、工作时长、常用工具、模型占比。

## 工作原理

```
 安卓 App ──(局域网 / Tailscale，端到端加密)──▶ 局域网信箱 ─┐
                                                          │  电脑（Linux / WSL / macOS）
                         palm-hub（会话中枢，127.0.0.1） ◀─┘
                           │            │
             palm-host（伪终端包住 claude）   Agents Island 桥（会话状态与审批钩子）
```

- **palm-host**：`claude` 经它启动，它用伪终端包住 Claude Code，让手机和电脑终端能同时输入、同时看到输出。
- **palm-hub**：会话中枢，汇总会话、审批、用量，内置一个局域网信箱。手机和中枢之间的每条消息都用配对时生成的密钥端到端加密，信箱只看得到密文。
- **会话列表与审批**来自 [Agents Island](https://github.com/garvinwong/agents-island) 的桥和钩子（同一作者的开源项目，MIT）。只需要桥和钩子，不必装它的桌面界面。

## 环境要求

- 电脑：Linux、Windows 的 WSL2 或 macOS；Python 3.10+；已登录的 [Claude Code](https://docs.anthropic.com/en/docs/claude-code)。
- 已安装并运行 Agents Island 的桥与 Claude Code 钩子（见其 README）。
- 手机：安卓 10 及以上。
- 构建 App：JDK 17、Android SDK 35。

## 安装

1. **装 Agents Island 的桥与钩子**（只要桥和钩子，不必装桌面界面）
   ```bash
   git clone https://github.com/garvinwong/agents-island.git ~/agents-island
   cd ~/agents-island
   python3 scripts/install_hooks.py --permission-request   # 审批与终端权限框都经桥转给手机
   bash launch/start_bridge.sh                              # 起桥（127.0.0.1:5599）
   ```

2. **取代码、装依赖**
   ```bash
   git clone https://github.com/garvinwong/claude-inspire.git ~/claude-inspire
   python3 -m pip install --user -r ~/claude-inspire/requirements.txt
   ```
   依赖为 `cryptography`、`pyte`、`qrcode`。新版 Debian / Ubuntu 会拒绝 `pip install`（PEP 668）：可加 `--break-system-packages`，或用系统包（`sudo apt install python3-cryptography python3-pyte python3-qrcode`）。中枢服务用系统自带的 `/usr/bin/python3` 运行，依赖须装给它，不要只装进虚拟环境。

3. **让 `claude` 经宿主启动**：在 `~/.bashrc` 末尾加一行，然后新开终端。
   ```bash
   source ~/claude-inspire/host/palm.sh
   ```
   它定义了 `claude` 包装和 `palm` 命令（都是 shell 函数，只在加载了这一行的终端里可用）。只有交互式启动才经宿主；`claude -p` 之类不受影响。临时绕过：`PALM_HOST=0 claude`。目前只在 bash 下实测；zsh 写法已兼容但未实测。

4. **装会话中枢**（systemd 用户服务）
   ```bash
   bash ~/claude-inspire/systemd/install.sh
   loginctl enable-linger $USER   # 可选：不登录也常驻
   ```
   没有 systemd 的系统（如 macOS）请自行常驻运行 `python3 ~/claude-inspire/hub/palm_hub.py`；下文的 `systemctl` 重启命令相应改为重启这个进程。

5. **构建并安装 App**
   ```bash
   cd ~/claude-inspire/android
   echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # 换成你的 Android SDK 位置（或设 ANDROID_HOME）
   ./gradlew assembleRelease
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```
   默认用调试密钥签名，只适合自己装。界面目前只有中文。

6. **配对**
   ```bash
   palm pair --qr          # 用手机相机扫终端里的二维码，App 里点“确认配对”
   systemctl --user restart palm-hub
   ```
   本机有多个地址时用 `--host` 指定手机能连到的那个，如 `palm pair --qr --host 192.168.1.20`。

7. **在外面也能用**：电脑和手机都装 [Tailscale](https://tailscale.com/) 并登录同一账号，用电脑的 Tailscale 地址（100.x.x.x）配对即可。

8. **保持连接**：配对后打开 App 会请求“允许在后台运行”，请允许；否则部分手机锁屏后会断开连接，审批收不到。设置页“锁屏后保持连接”显示当前状态。

### WSL 用户注意

WSL2 默认用 NAT 网络，手机连不到 WSL 里的地址（`palm pair` 自动挑到的也会是这类内部地址）。任选其一：
- 在 Windows 的 `%UserProfile%\.wslconfig` 里设置 `[wsl2]` 下 `networkingMode=mirrored`，然后 `wsl --shutdown` 重启，再用电脑的局域网地址配对；
- 或在 WSL 里装 Tailscale，用它的地址（100.x.x.x）配对。

## 常用命令

| 命令 | 作用 |
|---|---|
| `palm` / `palm ls` | 列出经宿主运行的会话 |
| `palm attach <宿主号>` | 在当前终端接上一个后台会话（关窗口只是断开） |
| `palm pair [--qr] [--host 地址] [--name 电脑名] [--adb 序列号] [--rotate]` | 配对手机；`--name` 是手机上显示的电脑名；`--adb` 经 adb 把配对发给手机；`--rotate` 换新令牌与密钥 |
| `palm hub` | 查看中枢服务状态（systemd） |
| `palm doctor` | 体检：宿主、中枢、桥、配对是否就绪 |

环境变量：

| 变量 | 默认 | 作用 |
|---|---|---|
| `PALM_LAN_BIND` / `PALM_LAN_PORT` | `0.0.0.0` / `5613` | 信箱监听地址。建议设为电脑的局域网或 Tailscale 地址，别让它听在公网网卡上 |
| `PALM_HUB_PORT` | `5612` | 中枢本机接口端口（只听 127.0.0.1） |
| `PALM_BRIDGE_URL` | `http://127.0.0.1:5599` | Agents Island 桥地址 |
| `PALM_HOME_DIR` | `~/.palm` | 配对、令牌、缓存、收件目录所在 |
| `PALM_INBOX_DIR` | `~/.palm/inbox` | 手机发来的附件存放处（仅本人可读，7 天自动清） |
| `PALM_RL_DIR` | `~/.agents-island` | 可选：Agents Island 状态栏钩子写的额度缓存 |
| `PALM_TERMINAL` | 自动 | “在电脑上打开”用的终端命令，如 `gnome-terminal -- bash -lc {cmd}` |
| `PALM_ADB` | `adb` | `palm pair --adb` 用的 adb 路径 |

## 安全

- 令牌与密钥只在本机生成：`~/.palm/pair-tokens.json`、`link.json` 存明文（权限 600，只有你自己能读）；信箱的设备表 `mailbox.json` 只存令牌摘要。
- 手机与电脑之间的消息与附件全部端到端加密（AES-256-GCM，带防重放序号与时间窗）。信箱用 http 传输，看不到内容。但要注意：同一网络里有人截获了令牌，虽然解不开、也伪造不了消息，却能抢先把手机的信箱取空（审批会“收不到”）、删掉待取的附件、看到收发的时间与大小。所以**只在可信的网络里用，外出走 Tailscale**；发现异常就 `palm pair --rotate` 作废旧令牌。
- 中枢本机接口只听 127.0.0.1，并要求令牌（`~/.palm/hub.token`）。信箱默认听所有网卡，电脑有公网地址时请用 `PALM_LAN_BIND` 限定。
- 手机发来的附件存在 `~/.palm/inbox/`（目录 700、文件 600），7 天后自动删除。
- 手机上不能开启 `bypassPermissions`。YOLO 模式只对你指定的会话生效，可随时关闭。
- “刷新使用率”只在你手动点时运行，每 60 秒最多一次。**请不要把它改成定时调用**：对订阅制 CLI 做定时探测可能导致账号被限制。

## 协议

[PolyForm Noncommercial 1.0.0](LICENSE.md)：个人使用、学习、研究，以及公益、教育、政府等非营利机构使用免费；非商业目的的转发与分发也可以，但须附上本协议和署名声明。**商业用途须先取得作者授权**——包括在营利性公司里使用、集成进商业产品或服务、收费分发。请通过 [GitHub](https://github.com/garvinwong) 联系。

第三方组件见 [THIRD_PARTY.md](THIRD_PARTY.md)。

---

Powered by [Garvin Wong](https://github.com/garvinwong)
