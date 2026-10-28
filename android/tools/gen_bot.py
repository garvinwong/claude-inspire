#!/usr/bin/env python3
"""吉祥物 C 案矢量母版生成器（唯一真源）：一份参数 → ① 对比用 SVG ② App 用 Kotlin（ui/BotArt.kt）。

几何从概念图量出来（tools/trace_bot.py 分割 + 量取），坐标系 0~100（机身 + 天线 + 腿的外接方框）。
形状 = 带透视的圆角四边形 + 边缘微鼓（卡通感来自鼓起的边与大圆角，不是正矩形）。
天线、腿、屏幕里的字在 App 里按锚点参数化绘制（要能动），这里给出锚点与尺寸。
用法：python3 gen_bot.py svg <输出.svg> [状态]   /   python3 gen_bot.py kotlin <BotArt.kt>
"""
import math
import sys

# ---------------- 参数（改造型只改这里） ----------------
P = {
    # 正面上沿就是概念图里那道亮带的顶线：高光画在正面自身的上沿（斜面），不再另贴一块（“后面贴上去”的观感）
    'front':  {'q': [(10.4, 20.4), (76.4, 21.6), (75.2, 80.2), (9.6, 80.0)], 'r': 11.5, 'bulge': 0.9},
    'bevel':  {'hi': 3.8, 'mid': 2.0},                 # 斜面高光厚度（亮带）与过渡带厚度
    'screen': {'q': [(15.6, 29.2), (67.6, 30.3), (66.7, 69.8), (15.2, 68.8)], 'r': 7.2, 'bulge': 0.5},
    # 侧面：直接用概念图量到的轮廓点（measure.py side_right_profile），平滑样条闭合；隐在正面后面的那半圈只为闭合
    'side': {'pts': [(71.0, 22.6), (78.6, 23.6), (84.9, 26.8), (88.6, 31.8), (90.2, 38.6), (90.7, 44.5), (90.9, 50.9),
                     (90.9, 57.2), (90.7, 63.5), (89.6, 69.6), (86.4, 74.0), (81.6, 76.8), (76.6, 78.8), (72.0, 79.2), (68.0, 76.0),
                     (68.0, 30.0)]},
    'antenna': {'L': {'base': (41.6, 21.0), 'tip': (38.4, 6.0)}, 'R': {'base': (57.6, 21.6), 'tip': (62.6, 7.0)},
                'stalk': 3.2, 'ball': 4.5},
    'legs': {'L': (37.1, 72.0), 'R': (63.6, 72.4), 'w': 11.0, 'h': 17.2},
    'shadow': {'c': (51.0, 98.0), 'rx': 21.5, 'ry': 1.6},
    'text': {'origin': (25.2, 40.4), 'pitch': 2.9, 'cell': 2.8, 'bar': (37.6, 55.9, 10.2, 3.0)},
    'colors': {'front': '#DF7A53', 'frontHi': '#E8875F', 'frontLo': '#D9714D', 'top': '#F3A97F', 'side': '#BE5F3D',
               'sideHi': '#C8673F', 'bevelMid': '#EC9670', 'screen': '#302E37', 'ink': '#F6F2EE', 'stalk': '#C26843', 'ball': '#E17A53',
               'leg': '#BF5F3D', 'amber': '#E8A33D', 'shadow': '#E07952'},
}


def _unit(a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    n = math.hypot(dx, dy) or 1.0
    return dx / n, dy / n


def spline(pts):
    """闭合 Catmull-Rom 样条 → 三次贝塞尔路径。"""
    n = len(pts)
    d = [f'M{pts[0][0]:.2f},{pts[0][1]:.2f}']
    for i in range(n):
        p0, p1, p2, p3 = pts[i - 1], pts[i], pts[(i + 1) % n], pts[(i + 2) % n]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        d.append(f'C{c1[0]:.2f},{c1[1]:.2f} {c2[0]:.2f},{c2[1]:.2f} {p2[0]:.2f},{p2[1]:.2f}')
    return ''.join(d) + 'Z'


def shape(name):
    g = P[name]
    return spline(g['pts']) if 'pts' in g else rquad(g['q'], g['r'], g['bulge'])


def rquad(q, r, bulge):
    """圆角四边形：角用近圆三次曲线，边用二次曲线向外鼓 bulge（中点外推量）。"""
    n = len(q)
    cx = sum(p[0] for p in q) / n
    cy = sum(p[1] for p in q) / n
    a, b = [], []
    for i in range(n):
        cur, prv, nxt = q[i], q[i - 1], q[(i + 1) % n]
        up, un = _unit(cur, prv), _unit(cur, nxt)
        rr = min(r, math.dist(cur, prv) * 0.45, math.dist(cur, nxt) * 0.45)   # 圆角不超过邻边的 45%，短边不出尖
        a.append((cur[0] + up[0] * rr, cur[1] + up[1] * rr))      # 进角点
        b.append((cur[0] + un[0] * rr, cur[1] + un[1] * rr))      # 出角点
    d = [f'M{b[0][0]:.2f},{b[0][1]:.2f}']
    for i in range(n):
        j = (i + 1) % n
        s, e = b[i], a[j]
        mx, my = (s[0] + e[0]) / 2, (s[1] + e[1]) / 2
        ox, oy = mx - cx, my - cy
        on = math.hypot(ox, oy) or 1.0
        k_ = bulge * 2 * min(1.0, math.dist(s, e) / 30)                          # 外鼓随边长缩放
        c = (mx + ox / on * k_, my + oy / on * k_)
        d.append(f'Q{c[0]:.2f},{c[1]:.2f} {e[0]:.2f},{e[1]:.2f}')
        k = 0.55
        c1 = (e[0] + (q[j][0] - e[0]) * k, e[1] + (q[j][1] - e[1]) * k)
        c2 = (b[j][0] + (q[j][0] - b[j][0]) * k, b[j][1] + (q[j][1] - b[j][1]) * k)
        d.append(f'C{c1[0]:.2f},{c1[1]:.2f} {c2[0]:.2f},{c2[1]:.2f} {b[j][0]:.2f},{b[j][1]:.2f}')
    return ''.join(d) + 'Z'


GLYPH3 = {'>': ['100', '010', '001', '010', '100'], '?': ['111', '001', '011', '000', '010']}


def svg(state='run'):
    c = P['colors']
    parts = []
    s = P['shadow']
    parts.append(f'<ellipse cx="{s["c"][0]}" cy="{s["c"][1]}" rx="{s["rx"]}" ry="{s["ry"]}" fill="{c["shadow"]}" opacity="0.8"/>')
    lg = P['legs']
    for k in ('L', 'R'):
        x, y = lg[k]
        parts.append(f'<rect x="{x - lg["w"] / 2:.2f}" y="{y:.2f}" width="{lg["w"]}" height="{lg["h"]}" rx="{lg["w"] / 2:.2f}" fill="{c["leg"]}"/>')
    a = P['antenna']
    for k in ('L', 'R'):
        b_, t_ = a[k]['base'], a[k]['tip']
        parts.append(f'<line x1="{b_[0]}" y1="{b_[1]}" x2="{t_[0]}" y2="{t_[1]}" stroke="{c["stalk"]}" stroke-width="{a["stalk"]}" stroke-linecap="round"/>')
        parts.append(f'<circle cx="{t_[0]}" cy="{t_[1]}" r="{a["ball"]}" fill="{c["ball"]}"/>')
    parts.append('<defs><linearGradient id="sg" x1="0" x2="1"><stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient>'
                 '<linearGradient id="fg" x1="0" y1="0" x2="0.3" y2="1"><stop offset="0" stop-color="%s"/><stop offset="1" stop-color="%s"/></linearGradient></defs>'
                 % (c['sideHi'], c['side'], c['frontHi'], c['frontLo']))
    bv = P['bevel']
    parts.append(f'<path d="{shape("side")}" fill="url(#sg)"/>')
    parts.append(f'<clipPath id="fc"><path d="{shape("front")}"/></clipPath>')
    parts.append(f'<g clip-path="url(#fc)"><path d="{shape("front")}" fill="{c["top"]}"/>'
                 f'<path d="{shape("front")}" transform="translate(0 {bv["mid"]})" fill="{c["bevelMid"]}"/>'
                 f'<path d="{shape("front")}" transform="translate(0 {bv["hi"]})" fill="url(#fg)"/></g>')
    parts.append(f'<path d="{shape("screen")}" fill="{c["screen"]}"/>')
    t = P['text']
    ox, oy = t['origin']
    glyph = '?' if state == 'wait' else '>'
    col = c['amber'] if state == 'wait' else c['ink']
    for y, row in enumerate(GLYPH3[glyph]):
        for x, bit in enumerate(row):
            if bit == '1':
                parts.append(f'<rect x="{ox + x * t["pitch"]:.2f}" y="{oy + y * t["pitch"]:.2f}" width="{t["cell"]}" height="{t["cell"]}" fill="{col}"/>')
    if state != 'wait':
        bx, by, bw, bh = t['bar']
        parts.append(f'<rect x="{bx}" y="{by}" width="{bw}" height="{bh}" fill="{c["ink"]}"/>')
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="600" height="600">{"".join(parts)}</svg>'


def kotlin():
    q = shape
    a, lg, t, s = P['antenna'], P['legs'], P['text'], P['shadow']
    col = '\n'.join(f'    val {k} = Color(0xFF{v[1:]})' for k, v in P['colors'].items())
    pt = lambda p: f'Offset({p[0]}f, {p[1]}f)'
    return f'''package app.palminspire.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * 吉祥物 C 案矢量母版（由 tools/gen_bot.py 生成，勿手改；改造型改生成器参数后重跑）。
 * 坐标系 0~100，几何从概念图量出（tools/trace_bot.py）。机身四块是 SVG 路径，天线、腿、屏幕字是锚点参数。
 */
object BotArt {{
    const val SIDE = "{q('side')}"
    const val FRONT = "{q('front')}"
    /** 正面上沿斜面高光：在正面内裁剪，正面向下错开 BEVEL_MID / BEVEL_HI 露出的上沿分别涂过渡色与亮色。 */
    const val BEVEL_HI = {P['bevel']['hi']}f
    const val BEVEL_MID = {P['bevel']['mid']}f
    const val SCREEN = "{q('screen')}"
    val ANT_L_BASE = {pt(a['L']['base'])}
    val ANT_L_TIP = {pt(a['L']['tip'])}
    val ANT_R_BASE = {pt(a['R']['base'])}
    val ANT_R_TIP = {pt(a['R']['tip'])}
    const val STALK = {a['stalk']}f
    const val BALL = {a['ball']}f
    val LEG_L = {pt(lg['L'])}
    val LEG_R = {pt(lg['R'])}
    const val LEG_W = {lg['w']}f
    const val LEG_H = {lg['h']}f
    val SHADOW = {pt(s['c'])}
    const val SHADOW_RX = {s['rx']}f
    const val SHADOW_RY = {s['ry']}f
    /** 屏幕内框（左上、右下），屏幕字按它排版。 */
    val SCREEN_TL = {pt((min(p[0] for p in P['screen']['q']), min(p[1] for p in P['screen']['q'])))}
    val SCREEN_BR = {pt((max(p[0] for p in P['screen']['q']), max(p[1] for p in P['screen']['q'])))}
    /** 概念图悬浮态的字：3×5 粗方块的 > 起点、格距、方块边长，与实心光标条（x, y, 宽, 高）。 */
    val TEXT_ORIGIN = {pt(t['origin'])}
    const val PITCH = {t['pitch']}f
    const val CELL = {t['cell']}f
    val BAR = floatArrayOf({', '.join(f"{v}f" for v in t['bar'])})
    /** 机身锚点：挤压拉伸以底边中点为原点。 */
    val BODY_FOOT = Offset(43f, 80f)
{col}
}}
'''


if __name__ == '__main__':
    mode = sys.argv[1]
    out = sys.argv[2]
    data = svg(sys.argv[3] if len(sys.argv) > 3 else 'run') if mode == 'svg' else kotlin()
    open(out, 'w', encoding='utf-8').write(data)
