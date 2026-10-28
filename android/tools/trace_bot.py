#!/usr/bin/env python3
"""吉祥物 C 案：从概念图描摹分层矢量母版（一次性工具，可重跑）。

做法：参照图放大 4 倍 → 按调色板最近色分类（正面 / 侧面暗橙 / 顶面亮橙 / 屏幕 / 地影）→ 每类取连通块轮廓 →
轮廓按 Catmull-Rom 转三次贝塞尔得到平滑曲线 → 输出 SVG（坐标归一到 0~100）与 JSON（供生成 Kotlin 用）。
天线、腿、屏幕里的字在 App 里参数化绘制（要能动），这里只量出它们的位置与尺寸。
用法：python3 trace_bot.py <参照图> <输出目录>
"""
import json
import sys
from pathlib import Path

import cv2
import numpy as np

PALETTE = {                                   # 参照图实测色（概念_C 悬浮态）
    'bg': (0xFB, 0xF9, 0xF5), 'front': (0xDF, 0x7A, 0x53), 'dark': (0xBE, 0x5F, 0x3D),
    'top': (0xF3, 0xA9, 0x7F), 'screen': (0x30, 0x2E, 0x37), 'cream': (0xF6, 0xF2, 0xEE), 'shadow': (0xF3, 0xDD, 0xCE),
}
UP = 4


def classify(img):
    names = list(PALETTE)
    pal = np.array([PALETTE[n] for n in names], np.float32)
    d = ((img[:, :, None, :].astype(np.float32) - pal[None, None]) ** 2).sum(-1)
    return names, d.argmin(-1)


def smooth_path(pts, scale, ox, oy):
    """闭合折线 → Catmull-Rom 三次贝塞尔 SVG 路径（坐标换算到 0~100）。"""
    p = [((x - ox) * scale, (y - oy) * scale) for x, y in pts]
    n = len(p)
    out = [f'M{p[0][0]:.2f},{p[0][1]:.2f}']
    for i in range(n):
        p0, p1, p2, p3 = p[i - 1], p[i], p[(i + 1) % n], p[(i + 2) % n]
        c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
        c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
        out.append(f'C{c1[0]:.2f},{c1[1]:.2f} {c2[0]:.2f},{c2[1]:.2f} {p2[0]:.2f},{p2[1]:.2f}')
    return ''.join(out) + 'Z'


def main(ref, outdir):
    img = cv2.cvtColor(cv2.imread(ref), cv2.COLOR_BGR2RGB)
    img = cv2.resize(img, None, fx=UP, fy=UP, interpolation=cv2.INTER_CUBIC)
    names, lab = classify(cv2.GaussianBlur(img, (5, 5), 0))
    H, W = lab.shape
    fg = (lab != names.index('bg')) & (lab != names.index('shadow'))
    ys, xs = np.nonzero(fg)
    # 以“机身 + 天线 + 腿”的外接框为 0~100 坐标系（宽高取大者）
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    side = max(x1 - x0, y1 - y0)
    ox, oy, sc = x0 - (side - (x1 - x0)) / 2, y0, 100.0 / side
    parts, info = {}, {}
    k = np.ones((5, 5), np.uint8)
    for cls in ('front', 'dark', 'top', 'screen'):
        m = (lab == names.index(cls)).astype(np.uint8) * 255
        m = cv2.morphologyEx(cv2.morphologyEx(m, cv2.MORPH_OPEN, k), cv2.MORPH_CLOSE, k)
        cnts, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
        cnts = sorted(cnts, key=cv2.contourArea, reverse=True)
        for i, c in enumerate(cnts[:6]):
            area = cv2.contourArea(c)
            if area < 0.002 * W * H:
                continue
            bx, by, bw, bh = cv2.boundingRect(c)
            approx = cv2.approxPolyDP(c, 0.006 * cv2.arcLength(c, True), True)[:, 0, :]
            key = f'{cls}{i}'
            parts[key] = smooth_path(approx.tolist(), sc, ox, oy)
            info[key] = {'box': [round((bx - ox) * sc, 2), round((by - oy) * sc, 2), round(bw * sc, 2), round(bh * sc, 2)],
                         'area': round(area * sc * sc, 1), 'points': len(approx)}
    colors = {'front': '#DF7A53', 'dark': '#BE5F3D', 'top': '#F3A97F', 'screen': '#302E37'}
    order = ['dark', 'top', 'front', 'screen']
    body = ''.join(f'<path d="{d}" fill="{colors[k.rstrip("0123456789")]}"/>'
                   for o in order for k, d in parts.items() if k.rstrip('0123456789') == o)
    svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="600" height="600">{body}</svg>'
    out = Path(outdir)
    out.mkdir(parents=True, exist_ok=True)
    (out / 'bot_traced.svg').write_text(svg)
    (out / 'bot_traced.json').write_text(json.dumps({'parts': parts, 'info': info}, ensure_ascii=False, indent=1))
    for k_, v in info.items():
        print(k_, v)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
