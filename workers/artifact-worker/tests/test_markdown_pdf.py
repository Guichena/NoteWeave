"""产物 Markdown 转 PDF：正文全部转义、常见结构能排版；本机没有 xelatex 时只校验 LaTeX 源。"""

import os
import shutil

import pytest

from app.markdown_pdf import markdown_to_latex, render_markdown_pdf


def test_markdown_structures_are_converted_and_escaped():
    tex = markdown_to_latex("预算 & 试点", "# 标题\n\n- 预算 **480000** TWD\n- 占比 50%\n\n1. 第一步\n\n"
                                       "> 引用 $x$\n\n| 项目 | 金额 |\n| --- | --- |\n| 研究 | 300000 |\n\n"
                                       "[链接文字](https://example.com) 和 `code_x`\n\n\\input{/etc/passwd}")
    assert r"\section*{标题}" in tex
    assert r"\begin{itemize}" in tex and r"\textbf{480000}" in tex and r"50\%" in tex
    assert r"\begin{enumerate}" in tex
    assert r"\$x\$" in tex
    assert "研究 \\quad 300000" in tex
    assert "链接文字" in tex and "https://example.com" not in tex
    assert r"\texttt{code\_x}" in tex
    # 正文里的 LaTeX 命令被转义，不会被执行
    assert r"\input{/etc/passwd}" not in tex
    assert r"预算 \& 试点" in tex


def test_empty_markdown_is_rejected():
    with pytest.raises(ValueError):
        render_markdown_pdf("标题", "  ")


@pytest.mark.skipif(shutil.which("xelatex") is None or not os.environ.get("NOTEWEAVE_RUN_XELATEX_TESTS"),
                    reason="需要 Worker 镜像里的 xelatex 与中文字体，设置 NOTEWEAVE_RUN_XELATEX_TESTS=1 时运行")
def test_render_produces_pdf():
    content = render_markdown_pdf("测试", "# 标题\n\n中文正文。")
    assert content.startswith(b"%PDF-")
