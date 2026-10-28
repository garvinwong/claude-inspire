#!/usr/bin/env python3
"""把 tools/icons/ 里的 SVG（原创描边图标）转成 Compose ImageVector（ui/AppIcons.kt）。改图标清单后重跑本脚本。
支持 path / line / polyline / circle / ellipse / rect；元素自带 fill="currentColor" 的按填充画。"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ICONS = """send paperclip image camera file file-text terminal message-square plus history settings stop check close
x-circle check-circle shield-check shield-alert lock unlock chevron-left chevron-right chevron-down chevron-up refresh
search folder folder-open user users wifi alert-triangle alert-circle play pause keyboard more-vertical bot code diff
edit list-check sparkles zap clock hourglass loader eye globe enter arrow-up arrow-down arrow-left arrow-right link
copy trash mic bell bell-off layers git-branch wrench monitor smartphone power info help-circle rotate-ccw key
upload download cpu archive sun moon hand chart-bar minus logout""".split()

SRC = Path(__file__).resolve().parent / 'icons'
OUT = Path(__file__).resolve().parents[1] / 'app/src/main/java/app/palminspire/ui/AppIcons.kt'


def f(v):
    return f'{float(v):g}'


def to_path(el):
    tag, a = el.tag.split('}')[-1], el.attrib
    if tag == 'path':
        return a['d']
    if tag == 'line':
        return f"M{f(a['x1'])} {f(a['y1'])}L{f(a['x2'])} {f(a['y2'])}"
    if tag in ('polyline', 'polygon'):
        pts = re.findall(r'-?[\d.]+', a['points'])
        pairs = [f'{f(pts[i])} {f(pts[i + 1])}' for i in range(0, len(pts) - 1, 2)]
        return 'M' + 'L'.join(pairs) + ('Z' if tag == 'polygon' else '')
    if tag in ('circle', 'ellipse'):
        cx, cy = float(a['cx']), float(a['cy'])
        rx = float(a.get('r') or a.get('rx'))
        ry = float(a.get('r') or a.get('ry'))
        return (f'M{f(cx - rx)} {f(cy)}a{f(rx)} {f(ry)} 0 1 0 {f(2 * rx)} 0'
                f'a{f(rx)} {f(ry)} 0 1 0 {f(-2 * rx)} 0Z')
    if tag == 'rect':
        x, y, w, h = (float(a.get(k, 0)) for k in ('x', 'y', 'width', 'height'))
        rx = float(a.get('rx') or a.get('ry') or 0)
        ry = float(a.get('ry') or rx)
        rx, ry = min(rx, w / 2), min(ry, h / 2)
        if not rx:
            return f'M{f(x)} {f(y)}h{f(w)}v{f(h)}h{f(-w)}Z'
        return (f'M{f(x + rx)} {f(y)}h{f(w - 2 * rx)}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(ry)}v{f(h - 2 * ry)}'
                f'a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(ry)}h{f(-(w - 2 * rx))}a{f(rx)} {f(ry)} 0 0 1 {f(-rx)} {f(-ry)}'
                f'v{f(-(h - 2 * ry))}a{f(rx)} {f(ry)} 0 0 1 {f(rx)} {f(-ry)}Z')
    raise ValueError(f'不支持的元素 {tag}')


def camel(name):
    head, *rest = name.split('-')
    return head + ''.join(x.capitalize() for x in rest)


def main():
    lines = ['// 由 tools/gen_icons.py 从 tools/icons 生成，勿手改',
             'package app.palminspire.ui', '',
             'import androidx.compose.ui.graphics.Color',
             'import androidx.compose.ui.graphics.SolidColor',
             'import androidx.compose.ui.graphics.StrokeCap',
             'import androidx.compose.ui.graphics.StrokeJoin',
             'import androidx.compose.ui.graphics.vector.ImageVector',
             'import androidx.compose.ui.graphics.vector.PathParser',
             'import androidx.compose.ui.unit.dp', '',
             'private fun icon(name: String, vararg parts: Pair<String, Boolean>): ImageVector =',
             '    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {',
             '        for ((d, filled) in parts) {',
             '            val nodes = PathParser().parsePathString(d).toNodes()',
             '            if (filled) addPath(nodes, fill = SolidColor(Color.Black))',
             '            else addPath(nodes, stroke = SolidColor(Color.Black), strokeLineWidth = 1.75f,',
             '                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)',
             '        }', '    }.build()', '', 'object AppIcons {']
    for name in ICONS:
        root = ET.parse(SRC / f'{name}.svg').getroot()
        parts = []
        for el in root.iter():
            if el is root:
                continue
            filled = el.attrib.get('fill') == 'currentColor'
            parts.append(f'"{to_path(el)}" to {"true" if filled else "false"}')
        lines.append(f'    val {camel(name)}: ImageVector by lazy {{ icon("{name}", {", ".join(parts)}) }}')
    lines.append('}')
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(f'已生成 {OUT.name}：{len(ICONS)} 个图标')


if __name__ == '__main__':
    sys.exit(main())
