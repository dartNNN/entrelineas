"""Grammar inventory of the Plan curricular del Instituto Cervantes (PCIC) from its .docx edition.

The inventory is three parts (A1-A2, B1-B2, C1-C2). Top-level chapters ("9. El verbo") are plain paragraphs; every
section is a table: a title row ("9.1. Conjugación"), a row naming the levels of its columns ("A1", "A2"), then rows
whose cells hold that level's items; inside a cell, lines like "15.3.6. Condicionales" open subsections. Chapters
with a single table ("4. Los demostrativos") have an unnumbered title row and take the chapter's number.
Writes build/grammar/pcic_grammar.json in document order:
[{"number": "15.3.6", "title": "Condicionales", "parent": "Oraciones subordinadas adverbiales", "chapter": "15. ...",
  "levels": {"B1": [lines], ...}}, ...]
with one entry per deepest numbered section; "[—]" (nothing at this level) is dropped. A rule already met in another
section earlier in the document is not repeated: the .docx copy has the C2 rules of 15.2 (relative clauses) pasted
again into the C1 cell of 15.3.6 (conditionals).

The PCIC is copyrighted by the Instituto Cervantes: the file stays local like the dictionaries.
Usage: .venv/bin/python builder/pcic.py path/to/plan-curricular.docx
"""
import json
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

from config import ROOT

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"
LEVELS = {"A1", "A2", "B1", "B2", "C1", "C2"}
PART = re.compile(r"Gramática\. Inventario (A1-A2|B1-B2|C1-C2)")
SECTION = re.compile(r"^(\d+(?:\.\d+)*)\.?\s+(.*)$")
SUBSECTION = re.compile(r"^(\d+(?:\.\d+){2,})\.?\s+(.*)$")


EMPTY = "[—]"  # the PCIC's mark for "nothing at this level"


def _text(element):
    """Text of an element; a line break inside a paragraph (rule, then its example) becomes " / "."""
    parts = []
    for node in element.iter():
        if node.tag == f"{W}t":
            parts.append(node.text or "")
        elif node.tag in (f"{W}br", f"{W}cr"):
            parts.append(" / ")
    return "".join(parts).strip()


def _cell_lines(cell):
    return [line for line in (" ".join(_text(p).split()) for p in cell.iter(f"{W}p")) if line]


def parse(docx_path):
    body = ET.fromstring(zipfile.ZipFile(docx_path).read("word/document.xml")).find(f"{W}body")
    sections, part, chapter, first_section = {}, None, None, {}
    for child in body:
        if child.tag == f"{W}p":
            text = " ".join(_text(child).split())
            m = PART.match(text)
            if m:
                part = m.group(1)
            elif part and text.startswith(("Pronunciación", "Ortografía")) and "Inventario" in text:
                break
            elif part and re.match(r"^\d+\.\s+\S", text) and len(text) < 80:
                chapter = text
            elif part and text == "Oraciones compuestas por subordinación":  # the one chapter heading without number
                chapter = "15. " + text
            continue
        if child.tag != f"{W}tbl" or not part:
            continue
        rows = [[_cell_lines(tc) for tc in tr.findall(f"{W}tc")] for tr in child.findall(f"{W}tr")]
        if not rows or not rows[0] or not rows[0][0]:
            continue
        m = SECTION.match(rows[0][0][0])
        if m:
            number, title = m.group(1), m.group(2).strip()
        elif chapter and chapter.split(". ", 1)[1].strip().lower() == rows[0][0][0].strip().lower():
            number, title = chapter.split(".", 1)[0], rows[0][0][0].strip()
        else:
            continue
        columns = None
        for row in rows[1:]:
            names = [" ".join(c) for c in row]
            if names and all(n in LEVELS for n in names):
                columns = names
                continue
            if not columns or len(row) != len(columns):
                continue
            for level, lines in zip(columns, row):
                current = number
                for line in lines:
                    sub = SUBSECTION.match(line)
                    if sub and sub.group(1).startswith(number + "."):
                        current = sub.group(1)
                        entry = sections.setdefault(current, {"number": current, "title": EMPTY, "parent": title,
                                                              "chapter": chapter, "levels": {}})
                        if entry["title"] == EMPTY and sub.group(2).strip() != EMPTY:
                            entry["title"] = sub.group(2).strip()
                        continue
                    entry = sections.setdefault(current, {"number": current, "title": title, "parent": None,
                                                          "chapter": chapter, "levels": {}})
                    if line == EMPTY or (" / " in line and first_section.setdefault(line, current) != current):
                        continue
                    entry["levels"].setdefault(level, []).append(line)
    for entry in sections.values():
        entry["levels"] = {lv: entry["levels"][lv] for lv in sorted(entry["levels"])}
    return sorted(sections.values(), key=lambda e: tuple(int(x) for x in e["number"].split(".")))


def main():
    out = ROOT / "build" / "grammar" / "pcic_grammar.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    sections = parse(sys.argv[1])
    out.write_text(json.dumps(sections, ensure_ascii=False, indent=1))
    print(f"{len(sections)} sections -> {out}")


if __name__ == "__main__":
    main()
