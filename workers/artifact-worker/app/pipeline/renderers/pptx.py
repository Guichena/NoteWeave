"""渲染可编辑的 DECK，并从实际 PPTX 生成逐页预览。"""

from pathlib import Path
import os
import shutil
import subprocess
from tempfile import TemporaryDirectory

from PIL import Image
from pypdf import PdfReader

from app.pipeline.renderers.frames import time_label
from app.video_deck_render import render_original_video_deck


def render(ir, task_id, evidence, frame_reader):
    path = render_original_video_deck(task_id, ir, read_file=frame_reader, evidence=evidence)
    sections = [{"heading": slide["title"], "body": "\n".join("- " + b["text"] for b in slide["bullets"])
                 + ("\n\n" + slide["figure"]["caption"]["text"] + "\n" + time_label(slide["figure"], evidence)
                    if "figure" in slide else ""), "source_refs": []} for slide in ir["slides"]]
    with TemporaryDirectory(prefix="noteweave-deck-") as directory:
        temporary = Path(directory)
        execute([shutil.which("soffice.com") if os.name == "nt" else "soffice", f"-env:UserInstallation={(temporary / 'profile').as_uri()}", "--headless",
                 "--convert-to", "pdf", "--outdir", str(temporary), str(path)], 180)
        pdf = temporary / (path.stem + ".pdf")
        if not pdf.is_file() or not 16 <= pdf.stat().st_size <= 150_000_000:
            raise ValueError("幻灯片转换未生成有效 PDF")
        reader = PdfReader(pdf)
        if len(reader.pages) != len(ir["slides"]):
            raise ValueError("幻灯片实际渲染页数与 IR 不一致")
        previews = []
        for index, slide in enumerate(ir["slides"], 1):
            visible = "".join((reader.pages[index - 1].extract_text() or "").split())
            if any("".join(text.split()) not in visible for text in [slide["title"], *(b["text"] for b in slide["bullets"])]):
                raise ValueError(f"第 {index} 页渲染丢失标题或要点")
            prefix = path.parent / f"slide-{index:03d}"
            execute(["pdftoppm", "-f", str(index), "-l", str(index), "-singlefile", "-r", "120", "-png", str(pdf), str(prefix)], 90)
            preview = prefix.with_suffix(".png")
            with Image.open(preview) as image:
                image.verify()
            previews.append(preview)
    return sections, [("PRIMARY_PPTX", path, 0), *[("SLIDE_PREVIEW", p, i) for i, p in enumerate(previews, 1)]]


def execute(command, timeout):
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode:
        raise ValueError(f"渲染工具 {command[0]} 执行失败：{result.stderr[-800:]}")
