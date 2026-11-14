"""README 架构图的绘图小工具：方框、泳道、箭头和文字，统一配色与字号。

只依赖标准库。每张图由 build_readme_diagrams.py 里的一个函数描述，运行后写到 docs/images。
文字宽度按中文一个字等于字号、其他字符约 0.58 个字号估算，超出方框时打印警告，便于调整布局。
"""
from __future__ import annotations

import html
from dataclasses import dataclass

FONT = ("PingFang SC, Microsoft YaHei, Noto Sans CJK SC, Source Han Sans SC, "
        "Helvetica, Arial, sans-serif")
MONO = "SFMono-Regular, Consolas, Menlo, monospace"

THEMES = {
    "indigo": ("#eef2ff", "#4f46e5", "#312e81", "#3730a3"),
    "emerald": ("#ecfdf5", "#059669", "#065f46", "#047857"),
    "amber": ("#fffbeb", "#d97706", "#78350f", "#92400e"),
    "sky": ("#f0f9ff", "#0284c7", "#0c4a6e", "#075985"),
    "rose": ("#fff1f2", "#e11d48", "#881337", "#9f1239"),
    "slate": ("#f8fafc", "#64748b", "#0f172a", "#334155"),
    "violet": ("#f5f3ff", "#7c3aed", "#4c1d95", "#5b21b6"),
    "teal": ("#f0fdfa", "#0d9488", "#134e4a", "#115e59"),
}

ARROW_COLORS = {
    "slate": "#475569",
    "rose": "#e11d48",
    "amber": "#d97706",
    "emerald": "#059669",
    "indigo": "#4f46e5",
    "sky": "#0284c7",
    "violet": "#7c3aed",
}


def text_width(value: str, size: float) -> float:
    width = 0.0
    for char in value:
        width += size if ord(char) >= 0x2E80 else size * 0.58
    return width


@dataclass
class Box:
    x: float
    y: float
    w: float
    h: float

    def right(self, dy: float = 0) -> tuple[float, float]:
        return self.x + self.w, self.y + self.h / 2 + dy

    def left(self, dy: float = 0) -> tuple[float, float]:
        return self.x, self.y + self.h / 2 + dy

    def top(self, dx: float = 0) -> tuple[float, float]:
        return self.x + self.w / 2 + dx, self.y

    def bottom(self, dx: float = 0) -> tuple[float, float]:
        return self.x + self.w / 2 + dx, self.y + self.h

    @property
    def cx(self) -> float:
        return self.x + self.w / 2

    @property
    def cy(self) -> float:
        return self.y + self.h / 2


class Diagram:
    def __init__(self, name: str, width: int, height: int, title: str, subtitle: str = ""):
        self.name = name
        self.width = width
        self.height = height
        self.parts: list[str] = []
        self.warnings: list[str] = []
        self.parts.append(f'<rect width="{width}" height="{height}" fill="#ffffff"/>')
        self.text(width / 2, 44, title, size=25, weight=700, fill="#0f172a", anchor="middle")
        if subtitle:
            self.text(width / 2, 72, subtitle, size=14, fill="#475569", anchor="middle")

    # ---- 基本元素 ----
    def text(self, x, y, value, size=13.0, fill="#334155", weight=400, anchor="start",
             family=None, italic=False):
        style = ' font-style="italic"' if italic else ""
        fam = f' font-family="{family}"' if family else ""
        self.parts.append(
            f'<text x="{x:.1f}" y="{y:.1f}" font-size="{size}" font-weight="{weight}" '
            f'fill="{fill}" text-anchor="{anchor}"{fam}{style}>{html.escape(value)}</text>')

    def rect(self, x, y, w, h, fill, stroke, radius=10, dashed=False, width=1.4, opacity=1.0):
        dash = ' stroke-dasharray="6 5"' if dashed else ""
        op = f' fill-opacity="{opacity}"' if opacity < 1 else ""
        self.parts.append(
            f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" rx="{radius}" '
            f'fill="{fill}"{op} stroke="{stroke}" stroke-width="{width}"{dash}/>')

    def lane(self, x, y, w, h, label, theme="slate", note=""):
        fill, stroke, title, _ = THEMES[theme]
        self.rect(x, y, w, h, fill, stroke, radius=14, dashed=True, width=1.1, opacity=0.55)
        self.text(x + 16, y + 24, label, size=14.5, weight=700, fill=title)
        if note:
            self.text(x + 22 + text_width(label, 14.5), y + 24, note, size=12, fill="#64748b")
        return Box(x, y, w, h)

    def box(self, x, y, w, title, lines=(), theme="slate", code=None, h=None,
            size=12.5, title_size=14.5, center=False, tag=None):
        fill, stroke, title_color, text_color = THEMES[theme]
        lines = list(lines)
        needed = 24 + (24 if lines else 4) + 19 * max(len(lines) - 1, 0) + (21 if code else 0) + 14
        if not lines and not code:
            needed = 42
        height = h if h is not None else needed
        if height + 2 < needed:
            self.warnings.append(f"[{self.name}] 方框 {title} 高度 {height} 小于需要的 {needed}")
        self.rect(x, y, w, height, fill, stroke)
        tx = x + w / 2 if center else x + 14
        anchor = "middle" if center else "start"
        self.text(tx, y + 24, title, size=title_size, weight=700, fill=title_color, anchor=anchor)
        self._check(title, title_size, w - 28, title)
        if tag:
            tw = text_width(tag, 11) + 14
            self.rect(x + w - tw - 8, y + 9, tw, 20, "#ffffff", stroke, radius=10, width=1)
            self.text(x + w - tw / 2 - 8, y + 23, tag, size=11, fill=title_color, anchor="middle")
            self._check(title, title_size, w - 28 - tw - 8, title + "（含标签）")
        baseline = y + 48
        for line in lines:
            self.text(tx, baseline, line, size=size, fill=text_color, anchor=anchor)
            self._check(line, size, w - 28, title)
            baseline += 19
        if code:
            code_y = baseline + 2 if lines else y + 46
            self.text(tx, code_y, code, size=11, fill=stroke, anchor=anchor, family=MONO)
            self._check(code, 11 * 1.02, w - 28, title)
        return Box(x, y, w, height)

    def pill(self, x, y, value, theme="slate", size=12, w=None):
        fill, stroke, title_color, _ = THEMES[theme]
        width = w if w is not None else text_width(value, size) + 20
        self.rect(x, y, width, 24, fill, stroke, radius=12, width=1.1)
        self.text(x + width / 2, y + 16.5, value, size=size, fill=title_color, anchor="middle")
        return Box(x, y, width, 24)

    def arrow(self, points, label=None, color="slate", dashed=False, label_at=None,
              label_dx=0, label_dy=-6, label_anchor="middle", width=1.5):
        stroke = ARROW_COLORS[color]
        path = " ".join(("M" if i == 0 else "L") + f"{px:.1f},{py:.1f}" for i, (px, py) in enumerate(points))
        dash = ' stroke-dasharray="6 5"' if dashed else ""
        self.parts.append(
            f'<path d="{path}" fill="none" stroke="{stroke}" stroke-width="{width}"{dash} '
            f'marker-end="url(#arrow-{color})"/>')
        if label:
            if label_at is None:
                # 默认放在最长一段的中点
                best = max(range(len(points) - 1), key=lambda i: abs(points[i + 1][0] - points[i][0])
                           + abs(points[i + 1][1] - points[i][1]))
                (ax, ay), (bx, by) = points[best], points[best + 1]
                label_at = ((ax + bx) / 2, (ay + by) / 2)
            lx, ly = label_at[0] + label_dx, label_at[1] + label_dy
            tw = text_width(label, 11.5) + 10
            if label_anchor == "middle":
                rx = lx - tw / 2
            elif label_anchor == "start":
                rx = lx - 5
            else:
                rx = lx - tw + 5
            self.parts.append(f'<rect x="{rx:.1f}" y="{ly - 13:.1f}" width="{tw:.1f}" height="17" '
                              f'rx="4" fill="#ffffff" fill-opacity="0.92"/>')
            self.text(lx, ly, label, size=11.5, fill=stroke, anchor=label_anchor, weight=600)

    def legend(self, x, y, items):
        cx = x
        for theme, label in items:
            fill, stroke, _, text_color = THEMES[theme]
            self.rect(cx, y - 11, 16, 14, fill, stroke, radius=3, width=1.1)
            self.text(cx + 22, y, label, size=12, fill="#475569")
            cx += 22 + text_width(label, 12) + 22

    def _check(self, value, size, limit, owner):
        width = text_width(value, size)
        if width > limit + 0.5:
            self.warnings.append(f"[{self.name}] {owner}: {value!r} 估算宽度 {width:.0f} 超出 {limit:.0f}")

    def render(self) -> str:
        markers = "".join(
            f'<marker id="arrow-{name}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" '
            f'markerHeight="8" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{color}"/></marker>'
            for name, color in ARROW_COLORS.items())
        body = "\n  ".join(self.parts)
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.width} {self.height}" '
                f'width="{self.width}" height="{self.height}" font-family="{FONT}">\n'
                f'  <defs>{markers}</defs>\n  {body}\n</svg>\n')
