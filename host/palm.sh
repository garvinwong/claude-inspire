# 掌上灵感 · 启动包装
# 启用：在 ~/.bashrc 或 ~/.zshrc 末尾加一行（路径换成你克隆本仓库的位置）：
#   source ~/claude-inspire/host/palm.sh
# 旁路：PALM_HOST=0 claude …    停用：删掉上面那一行，新开终端即可。
#
# 规则：只有“交互式（标准输入输出都是终端）且不是 -p / 子命令”时才经宿主启动；
#       任何前提不满足（宿主脚本不在、没有 python3、已在宿主里）一律直接执行原命令（失败放行）。

PALM_DIR="${PALM_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]:-${(%):-%x}}")" && pwd)}"   # bash 用 BASH_SOURCE，zsh 用 %x

_palm_should_wrap() {
  [[ "${PALM_HOST:-1}" == 0 ]] && return 1
  [[ -n "${PALM_HOST_ID:-}" ]] && return 1          # 已在宿主里（会话里的嵌套调用）
  [[ -t 0 && -t 1 ]] || return 1
  [[ -f "$PALM_DIR/palm_host.py" ]] || return 1
  command -v python3 >/dev/null 2>&1 || return 1
  local a
  for a in "$@"; do
    case "$a" in
      -p|--print|-h|--help|-v|--version|--bg|--background|--output-format|--output-format=*|--input-format|--input-format=*)
        return 1 ;;
    esac
  done
  case "${1:-}" in
    agents|attach|auth|auto-mode|doctor|gateway|import|install|logs|mcp|plugin|plugins|purge|respawn|rm|setup-token|stop|kill|ultrareview|update|upgrade|remote-control|config|migrate-installer)
      return 1 ;;
  esac
  return 0
}

claude() {
  if _palm_should_wrap "$@"; then
    python3 "$PALM_DIR/palm_host.py" -- claude "$@"
  else
    command claude "$@"
  fi
}

# palm ls：列出本机经宿主运行的会话
palm() {
  case "${1:-ls}" in
    ls)
      python3 - "${PALM_HOME_DIR:-$HOME/.palm}/run" <<'PY'
import json, os, sys, time
from pathlib import Path
run = Path(sys.argv[1])
rows = []
for p in sorted(run.glob('*.json')) if run.is_dir() else []:
    try:
        d = json.loads(p.read_text(encoding='utf-8'))
        os.kill(d['host_pid'], 0)
    except (OSError, ValueError, KeyError):
        continue
    mins = int((time.time() - d.get('started_at', time.time())) / 60)
    where = '后台' if d.get('detached') else '终端'
    rows.append(f"{d['id']}  {where}  {mins:>4} 分钟  {d.get('cwd', '')}")
print('\n'.join(rows) if rows else '（没有经宿主运行的会话）')
PY
      ;;
    attach) shift; python3 "$PALM_DIR/palm_attach.py" "$@" ;;
    pair) shift; python3 "$PALM_DIR/../hub/pair.py" "$@" ;;
    hub) systemctl --user --no-pager status palm-hub 2>&1 | head -5 ;;
    doctor) python3 "$PALM_DIR/../hub/doctor.py" ;;
    *) echo "用法：palm ls | palm attach <宿主号> | palm pair [--qr] [--host 地址] [--name 电脑名] [--adb 序列号] [--rotate] | palm hub | palm doctor" ;;
  esac
}
