"""审批的人话摘要与风险分级：危险操作在手机上标红，批准须长按。规则只在电脑侧。"""
import os
import re

HUMAN_MAX = 40

# 危险命令：命中任一即危险
DANGER_RULES = [
    (r'\bgit\s+push\b.*(--force\b|--force-with-lease\b|\s-f\b)', 'git 强制推送'),
    (r'\bgit\s+reset\s+--hard\b', 'git 硬重置'),
    (r'\bgit\s+clean\s+-\w*f', 'git 清理未跟踪文件'),
    (r'\bgit\s+branch\s+-D\b', '删除分支'),
    (r'\brm\s+-\w*[rRf]', '删除'),
    (r'\bsudo\b', '管理员执行'),
    (r'\b(mkfs\S*|dd)\s', '磁盘写入'),
    (r'\b(chmod|chown)\s+-R\b', '批量改权限'),
    (r'\b(curl|wget)\b[^|]*\|\s*(ba|z)?sh\b', '下载并执行脚本'),
    (r'\bkill\s+-9\b|\bpkill\b|\bkillall\b', '结束进程'),
    (r'\bsystemctl\s+(stop|restart|disable)\b', '停止或重启服务'),
    (r'\bdrop\s+(table|database)\b', '删除数据表'),
    (r'\brsync\b.*--delete\b', '同步并删除'),
    (r'\bdeploy(\.sh)?\b', '部署'),
]
FILE_TOOLS = {'Edit': '改文件', 'MultiEdit': '改文件', 'Write': '写文件', 'NotebookEdit': '改笔记本'}

# 桥的审批存活秒数（与 Agents Island 桥的 PENDING_TTL / PERM_TTL / ASK_TTL 一致）
BRIDGE_TTL = {'perm': 115, 'ask': 125, 'plan': 125}
BRIDGE_TTL_DEFAULT = 40


def _cut(s, n):
    s = ' '.join(str(s or '').split())
    return s if len(s) <= n else s[:n - 1] + '…'


def _target(cmd):
    """取命令最后一个非选项参数的末段作为“对象”，如分支名、目录名。"""
    args = [a for a in cmd.split() if not a.startswith('-')]
    return os.path.basename(args[-1].rstrip('/\\')) if len(args) > 1 else ''


def classify(entry):
    """返回 (人话摘要, 风险 'danger'|'normal')。"""
    tool = str(entry.get('tool_name') or '')
    ti = entry.get('tool_input') if isinstance(entry.get('tool_input'), dict) else {}
    if tool in FILE_TOOLS:
        fp = ti.get('file_path') or ti.get('notebook_path') or ''
        return _cut(f"{FILE_TOOLS[tool]}：{os.path.basename(str(fp))}" if fp else FILE_TOOLS[tool], HUMAN_MAX), 'normal'
    if tool == 'WebFetch' and ti.get('url'):
        host = re.sub(r'^\w+://', '', str(ti['url'])).split('/')[0]
        return _cut(f'抓网页：{host}', HUMAN_MAX), 'normal'
    cmd = ' '.join(str(ti.get('command') or '').split())
    if not cmd:
        return _cut(tool, HUMAN_MAX), 'normal'
    for pattern, label in DANGER_RULES:
        if re.search(pattern, cmd, re.IGNORECASE):
            t = _target(cmd)
            return _cut(f'{label}：{t}' if t else label, HUMAN_MAX), 'danger'
    if re.match(r'git\s+push\b', cmd):
        t = _target(cmd)
        return _cut(f'git 推送：{t}' if t else 'git 推送', HUMAN_MAX), 'normal'
    if re.match(r'git\s+commit\b', cmd):
        return 'git 提交', 'normal'
    return _cut(cmd, HUMAN_MAX), 'normal'


def ttl(entry):
    """审批还能等多少秒：条目自带 ttl 优先，否则按种类取桥的默认。"""
    try:
        own = int(entry.get('ttl') or 0)
    except (TypeError, ValueError):
        own = 0
    if own > 0:
        return min(own, 86400)
    return BRIDGE_TTL.get(entry.get('kind') or '', BRIDGE_TTL_DEFAULT)
