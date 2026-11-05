"""把产物的 Markdown 正文排成 PDF。

只有视频讲义会在生成时编译 PDF，其余产物（报告、学习指南、FAQ 等）原来没有任何导出 PDF 的路径，
下载 PDF 一律返回「已发布版本缺少 PDF 文件」。这里用与讲义相同的 XeLaTeX 与中文字体，按常见
Markdown 结构（标题、列表、引用、表格、段落）排版，不执行任何 LaTeX 命令，正文全部转义。
"""

from __future__ import annotations

import re
import subprocess
import tempfile
from pathlib import Path

from pypdf import PdfReader

_SPECIALS = {"\\": r"\textbackslash{}", "{": r"\{", "}": r"\}", "$": r"\$", "&": r"\&", "#": r"\#",
             "%": r"\%", "_": r"\_", "^": r"\textasciicircum{}", "~": r"\textasciitilde{}"}
_HEADINGS = {1: "section*", 2: "subsection*", 3: "subsubsection*"}
MAX_MARKDOWN_CHARS = 400_000

_PREAMBLE = r"""\documentclass[a4paper]{article}
\usepackage[fontset=fandol]{ctex}
\usepackage[margin=2.2cm]{geometry}
\usepackage{enumitem}
\setlist{nosep}
\setlength{\parskip}{0.5em}
\setlength{\parindent}{0pt}
\begin{document}
"""


def escape(text: str) -> str:
    return "".join(_SPECIALS.get(char, char) for char in text)


def _inline(text: str) -> str:
    """行内格式：链接只保留文字，粗体与行内代码保留强调，其余原样转义。"""
    text = re.sub(r"!\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", text)
    parts = re.split(r"(\*\*[^*]+\*\*|`[^`]+`)", text)
    out = []
    for part in parts:
        if part.startswith("**") and part.endswith("**") and len(part) > 4:
            out.append(r"\textbf{" + escape(part[2:-2]) + "}")
        elif part.startswith("`") and part.endswith("`") and len(part) > 2:
            out.append(r"\texttt{" + escape(part[1:-1]) + "}")
        else:
            out.append(escape(part))
    return "".join(out)


def markdown_to_latex(title: str, markdown: str) -> str:
    lines = markdown.replace("\r\n", "\n").split("\n")
    body: list[str] = [r"{\LARGE\bfseries " + escape(title.strip() or "产物") + r"\par}\vspace{1em}"]
    list_kind: str | None = None
    in_code = False

    def close_list() -> None:
        nonlocal list_kind
        if list_kind:
            body.append(r"\end{" + list_kind + "}")
            list_kind = None

    for raw in lines:
        line = raw.rstrip()
        if line.strip().startswith("```"):
            close_list()
            in_code = not in_code
            continue
        if in_code:
            body.append(r"{\small\texttt{" + escape(line) + r"}}\\")
            continue
        stripped = line.strip()
        if not stripped:
            close_list()
            body.append("")
            continue
        heading = re.match(r"^(#{1,6})\s+(.*)$", stripped)
        if heading:
            close_list()
            level = min(len(heading.group(1)), 3)
            body.append("\\" + _HEADINGS[level] + "{" + _inline(heading.group(2)) + "}")
            continue
        if re.match(r"^\|?\s*:?-{3,}", stripped):
            continue  # 表格分隔行
        if stripped.startswith("|"):
            close_list()
            cells = [cell.strip() for cell in stripped.strip("|").split("|")]
            body.append(" \\quad ".join(_inline(cell) for cell in cells) + r"\\")
            continue
        bullet = re.match(r"^[-*+]\s+(.*)$", stripped)
        ordered = re.match(r"^\d+[.)]\s+(.*)$", stripped)
        if bullet or ordered:
            kind = "itemize" if bullet else "enumerate"
            if list_kind != kind:
                close_list()
                body.append(r"\begin{" + kind + "}")
                list_kind = kind
            body.append(r"\item " + _inline((bullet or ordered).group(1)))
            continue
        close_list()
        if stripped.startswith(">"):
            body.append(r"\begin{quote}" + _inline(stripped.lstrip("> ")) + r"\end{quote}")
            continue
        body.append(_inline(stripped) + "\n")
    close_list()
    return _PREAMBLE + "\n".join(body) + "\n\\end{document}\n"


def render_markdown_pdf(title: str, markdown: str) -> bytes:
    if not markdown or not markdown.strip():
        raise ValueError("产物正文为空，不能生成 PDF")
    if len(markdown) > MAX_MARKDOWN_CHARS:
        raise ValueError("产物正文过长，不能生成 PDF")
    with tempfile.TemporaryDirectory(prefix="nw-md-pdf-") as directory:
        workdir = Path(directory)
        (workdir / "artifact.tex").write_text(markdown_to_latex(title, markdown), encoding="utf-8")
        result = subprocess.run(
            ["xelatex", "-no-shell-escape", "-interaction=nonstopmode", "-halt-on-error", "artifact.tex"],
            cwd=workdir, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
        pdf = workdir / "artifact.pdf"
        if result.returncode or not pdf.is_file():
            raise ValueError("产物 PDF 排版失败：" + result.stdout[-1200:])
        content = pdf.read_bytes()
        if not PdfReader(pdf).pages:
            raise ValueError("产物 PDF 排版没有生成页面")
        return content
