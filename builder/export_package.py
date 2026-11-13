"""Packs built chapters into a book package (ZIP: manifest.json + chapters/NNN.json).

Usage: python builder/export_package.py 1 [2 ...]
Writes build/<id>.bbook.zip (id from books/book.json, see config.py)
"""
import json
import pathlib
import sys
import zipfile

import jsonschema

from config import BOOK, BUILD

ROOT = pathlib.Path(__file__).parent.parent
SCHEMA = "1.5"
# The package format is defined once, in format/*.schema.json; the builder, the Android reader and the web reader
# all check against these files, so the three cannot drift apart.
FORMAT = pathlib.Path(__file__).parent.parent / "format"
MAX_OCCURRENCE = 20


def chapter_document(data):
    paragraphs = []
    for s in data["sentences"]:
        par_id = f"c{data['chapter']:02d}p{s['paragraph']:03d}"
        if not paragraphs or paragraphs[-1]["id"] != par_id:
            paragraphs.append({"id": par_id, "sentences": []})
            if s["paragraph"] == 0:
                paragraphs[-1]["heading"] = True
        ann = s.get("annotations") or {}
        paragraphs[-1]["sentences"].append({
            "id": s["sid"],
            "tokens": s["tokens"],
            "ws": s["ws"],
            "segments": ann.get("s", []),
            "grammar": ann.get("g", []),
            "context": ann.get("c", []),
            "variants": ann.get("v", []),
            "expressions": ann.get("e", []),
            "lexical": [{"t": l["token"], "lemma": l["lemma"], "key": l["key"], "n": l["ordinal"],
                         "gloss": l["gloss"], "gram": l.get("gram"), "form": l.get("form"),
                         **({"from": l["from"]} if l.get("from") else {}),
                         **({"sense": l["sense"]} if l.get("sense") else {})} for l in s["lexical"]],
            "ref": s.get("ref"),
            "notes": s.get("notes", []),
        })
    # Schema 1.1: the published translation, one entry per aligned sentence group; a sentence points to it by "ref".
    # text is null where the translator left the sentences out; loose marks a free rendering and uncertain a group
    # the segment model found not to render the sentences (both judged by the model, not by alignment scores).
    # Schema 1.3: the chapter title is paragraph 0 with "heading": true, annotated like any paragraph.
    # Schema 1.2: footnotes as their own list with their side ("reference": the translator's, a reference lists their
    # ids; "source": the author's, a sentence lists their ids).
    references = [{"text": r["text"], "loose": r["loose"], "uncertain": r["uncertain"], "notes": r["notes"]}
                  for r in data.get("references", [])]
    footnotes = [{"id": i, "text": t, "side": "reference"} for i, t in data.get("footnotes", {}).items()]
    footnotes += [{"id": i, "text": t, "side": "source"} for i, t in data.get("source_footnotes", {}).items()]
    doc = {"index": data["chapter"], "title": data["title"], "references": references, "footnotes": footnotes,
           "paragraphs": paragraphs}
    # Dictionary entries of the chapter's words for the word card: books built without a model (1.4) and with one (1.5).
    if data.get("entries"):
        doc["entries"] = data["entries"]
    return doc


def validator(name):
    return jsonschema.Draft202012Validator(json.loads((FORMAT / f"{name}.schema.json").read_text()))


def check(doc, name):
    """Raises with every place where doc breaks format/<name>.schema.json."""
    errors = sorted(validator(name).iter_errors(doc), key=lambda e: list(e.path))
    if errors:
        raise ValueError(f"{name} does not match format/{name}.schema.json:\n" +
                         "\n".join(f"  {e.json_path}: {e.message[:200]}" for e in errors[:20]))


def main():
    """export_package.py [--offline] N [N ...]: chapters from build/chNN (model build) or build/offline/chNN."""
    offline = "--offline" in sys.argv
    numbers = [int(a) for a in sys.argv[1:] if a.isdigit()] or [1]
    source = ROOT / BUILD / ("offline" if offline else "")
    book_id = BOOK["id"] + ("-offline" if offline else "")
    out = ROOT / BUILD / f"{book_id}.bbook.zip"
    chapters = []
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        for n in numbers:
            data = json.loads((source / f"ch{n:02d}" / "chapter.json").read_text())
            name = f"chapters/{n:03d}.json"
            doc = chapter_document(data)
            check(doc, "chapter")
            z.writestr(name, json.dumps(doc, ensure_ascii=False, separators=(",", ":")))
            chapters.append({"index": n, "title": data["title"], "file": name})
        manifest = {"schema": SCHEMA, "id": book_id,
                    "title": BOOK["title"] + (" (словарь, без модели)" if offline else ""),
                    "author": BOOK["author"], "studied": BOOK["studied"], "support": BOOK["support"],
                    "maxOccurrence": MAX_OCCURRENCE, "chapters": chapters}
        check(manifest, "manifest")
        z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=1))
    print(out, out.stat().st_size, "bytes")


if __name__ == "__main__":
    main()
