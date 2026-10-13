"""仅规范化可确认的简单表格；合并单元格不猜测网格。"""
from html import unescape
from html.parser import HTMLParser
import re


class _TableParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.rows = []
        self.row = None
        self.cell = None
        self.depth = 0
        self.tables_seen = 0
        self.unknown = False
        self.text = []

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "table":
            self.depth += 1
            self.tables_seen += 1
            if self.depth > 1 or self.tables_seen > 1:
                self.unknown = True
        if self.depth and tag == "tr":
            if self.row is not None:
                self.unknown = True
            self.row = []
        if self.depth and tag in {"td", "th"}:
            if self.cell is not None or self.row is None:
                self.unknown = True
            self.cell = []
            if attributes.get("rowspan", "1") != "1" or attributes.get("colspan", "1") != "1":
                self.unknown = True
        if tag == "br" and self.cell is not None:
            self.cell.append(" ")

    def handle_data(self, value):
        if self.depth:
            self.text.append(value)
        if self.cell is not None:
            self.cell.append(value)

    def handle_endtag(self, tag):
        if tag in {"td", "th"} and self.cell is not None:
            if self.row is not None:
                self.row.append("".join(self.cell).strip())
            self.cell = None
        if tag == "tr" and self.row is not None:
            self.rows.append(self.row)
            self.row = None
        if tag == "table":
            self.depth -= 1


def _split_row(line):
    value = line.strip()
    if value.startswith("|"):
        value = value[1:]
    if value.endswith("|") and not value.endswith("\\|"):
        value = value[:-1]
    cells, cell, escaped = [], [], False
    for char in value:
        if escaped:
            cell.append(char)
            escaped = False
        elif char == "\\":
            escaped = True
        elif char == "|":
            cells.append("".join(cell).strip())
            cell = []
        else:
            cell.append(char)
    if escaped:
        cell.append("\\")
    cells.append("".join(cell).strip())
    return cells


def table_cells(text):
    if "<table" in text.lower():
        parser = _TableParser()
        parser.feed(text)
        parser.close()
        rows = parser.rows
        if parser.unknown or parser.depth or parser.cell is not None or not rows or not rows[0] or any(len(row) != len(rows[0]) for row in rows):
            return None
        return rows
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if len(lines) < 2:
        return None
    separator = _split_row(lines[1])
    if not separator or any(not re.fullmatch(r":?-{3,}:?", cell) for cell in separator):
        return None
    rows = [_split_row(lines[0])] + [_split_row(line) for line in lines[2:]]
    if len(rows[0]) != len(separator) or any(len(row) != len(rows[0]) for row in rows):
        return None
    return rows


def visible_table_text(text, cells):
    if cells is not None:
        return "\n".join(" ".join(row) for row in cells)
    if "<table" in text.lower():
        parser = _TableParser()
        parser.feed(text)
        return " ".join(parser.text).strip()
    # 无法确认表格结构时保留真实文本，避免凭语法推断列数。
    return unescape(text)
