"""使用课程讲义模板与 XeLaTeX 编译插图 PDF。"""

import hashlib
from io import BytesIO
from pathlib import Path
import subprocess

from PIL import Image
from pypdf import PdfReader

from app.pipeline.renderers.frames import read_frame, time_label


def escape(text):
    replacements = {"\\": r"\textbackslash{}", "{": r"\{", "}": r"\}", "$": r"\$", "&": r"\&",
                    "#": r"\#", "%": r"\%", "_": r"\_", "^": r"\textasciicircum{}", "~": r"\textasciitilde{}"}
    return "".join(replacements.get(c, c) for c in text)


def render(ir, output, evidence, frame_reader):
    output.mkdir(parents=True, exist_ok=True)
    template = (Path(__file__).resolve().parents[3] / "templates" / "lecture-notes.tex").read_text(encoding="utf-8")
    template = template.replace(r"\newcommand{\notetitle}{}", r"\newcommand{\notetitle}{" + escape(ir["title"]) + "}")
    body = []
    for section in ir["sections"]:
        body.append(r"\section{" + escape(section["heading"]) + "}")
        for block in section["blocks"]:
            if block["type"] == "figure":
                content = read_frame(block["frame_id"], evidence, frame_reader)
                with Image.open(BytesIO(content)) as image:
                    suffix = ".png" if image.format == "PNG" else ".jpg"
                name = "frame-" + hashlib.sha256(content).hexdigest() + suffix
                (output / name).write_bytes(content)
                body.append(r"\begin{figure}[H]\centering" + "\n" +
                            r"\includegraphics[width=0.92\linewidth,height=0.52\textheight,keepaspectratio]{" + name + "}\n" +
                            r"\caption{" + escape(block["caption"]["text"]) + "}\n" +
                            r"{\small " + escape(time_label(block, evidence)) + r"\par}\end{figure}")
            else:
                statements = [escape(s["text"]) for s in block["statements"]]
                if block["type"] == "list":
                    body.append(r"\begin{itemize}" + "\n" + "\n".join(r"\item " + s for s in statements) + "\n" + r"\end{itemize}")
                else:
                    body.extend(s + "\n\n" for s in statements)
    start, end = "%% --- 正文内容开始 --- %%", "%% --- 正文内容结束 --- %%"
    before, rest = template.split(start, 1)
    _, after = rest.split(end, 1)
    tex = output / "lecture-notes.tex"
    tex.write_text(before + start + "\n" + "\n".join(body) + "\n" + end + after, encoding="utf-8")
    for _ in range(2):
        result = subprocess.run(["xelatex", "-no-shell-escape", "-interaction=nonstopmode", "-halt-on-error", tex.name],
                                cwd=output, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
        if result.returncode:
            raise ValueError("讲义 PDF 编译失败：" + result.stdout[-1800:])
    pdf = output / "lecture-notes.pdf"
    if not pdf.is_file() or not 16 <= pdf.stat().st_size <= 150_000_000 or not PdfReader(pdf).pages:
        raise ValueError("讲义编译未生成有效 PDF")
    return pdf
