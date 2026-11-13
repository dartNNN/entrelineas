"""Which chapters of the translation go with each chapter of the original, and a check of both editions.

Editions differ: a translation may merge or split chapters, drop a prologue or add a preface, so chapter n of one
is not always chapter n of the other. By default chapter n goes with chapter n. Running this script matches them by
content and length, prints both editions side by side with the doubtful pairs marked, and writes the result to
<build>/chapters.json, which the alignment and the chapter build then use:

  {"pairs": [{"source": 5, "reference": [5, 6], "length": 0.04, "similarity": 0.81, "doubt": ""}, ...]}

A pair can be fixed by hand in the book's settings, which win over the file: "reference_chapters": {"5": [5, 6], "7": []}
(an empty list: the translation has no such chapter).

Matching: a monotonic dynamic programme over both chapter lists, like the sentence alignment one level down. A group
of 1-3 chapters on one side with 1-3 on the other costs its length mismatch (|log| of the word ratio, corrected for
the ratio of the two editions) plus twice its dissimilarity (1 - cos of LaBSE embeddings of the opening and the
closing words); merges cost a little extra, so that 1:1 wins a tie; leaving a chapter out costs more the longer it is.

Usage: .venv/bin/python builder/chapters.py            (needs the aligner's packages, like builder/align.py)
"""
import json
import math
import sys

import text
from config import BOOK, BUILD, REFERENCE, ROOT, SOURCE

SAMPLE_WORDS = 80
MOVES = [(1, 1), (1, 2), (2, 1), (1, 3), (3, 1), (1, 0), (0, 1)]
MERGE_COST = 0.15        # per chapter beyond one on either side
SKIP_COST = (0.2, 1.5)   # leaving out a chapter: from a short one (a preface) to a typical one
DOUBT_LENGTH = 0.35      # |log| of the corrected word ratio above which a pair is doubtful (~ 30 % off)
DOUBT_SIMILARITY = 0.5


def words(chapter):
    return sum(len(p.split()) for p in chapter.paragraphs)


def opening(chapter):
    return " ".join(" ".join(chapter.paragraphs).split()[:SAMPLE_WORDS])


def closing(chapter):
    return " ".join(" ".join(chapter.paragraphs).split()[-SAMPLE_WORDS:])


def match(source, reference, embed):
    """[(source indices, reference indices, {"length", "similarity"})] covering both lists in order (0-based)."""
    n, m = len(source), len(reference)
    sw, rw = [max(words(c), 1) for c in source], [max(words(c), 1) for c in reference]
    ratio = sum(rw) / sum(sw)
    vectors = embed([opening(c) for c in source] + [closing(c) for c in source]
                    + [opening(c) for c in reference] + [closing(c) for c in reference])
    s_open, s_close, r_open, r_close = vectors[:n], vectors[n:2 * n], vectors[2 * n:2 * n + m], vectors[2 * n + m:]
    typical = sorted(sw)[n // 2]

    def skip(w, scale):
        return SKIP_COST[0] + (SKIP_COST[1] - SKIP_COST[0]) * min(1.0, w / (typical * scale))

    def group(i, a, j, b):
        length = abs(math.log((sum(rw[j:j + b]) / ratio) / sum(sw[i:i + a])))
        similarity = (float(s_open[i] @ r_open[j]) + float(s_close[i + a - 1] @ r_close[j + b - 1])) / 2
        cost = min(length, 2.0) + 2 * (1 - similarity) + MERGE_COST * (a + b - 2)
        return cost, {"length": round(length, 3), "similarity": round(similarity, 3)}

    cost = {(0, 0): 0.0}
    back = {}
    for i in range(n + 1):
        for j in range(m + 1):
            if (i, j) not in cost:
                continue
            for a, b in MOVES:
                if i + a > n or j + b > m:
                    continue
                if a and b:
                    c, info = group(i, a, j, b)
                else:
                    c, info = (skip(sw[i], 1) if a else skip(rw[j], ratio)), None
                if cost[i, j] + c < cost.get((i + a, j + b), math.inf):
                    cost[i + a, j + b] = cost[i, j] + c
                    back[i + a, j + b] = (i, j, a, b, info)
    out, i, j = [], n, m
    while (i, j) != (0, 0):
        pi, pj, a, b, info = back[i, j]
        out.append((list(range(pi, i)), list(range(pj, j)), info))
        i, j = pi, pj
    return out[::-1]


def doubt(source_ids, reference_ids, info):
    """Why a pair needs a look, or ""."""
    if not reference_ids:
        return "нет в переводе"
    if not source_ids:
        return "нет в оригинале"
    reasons = []
    if len(source_ids) > 1 or len(reference_ids) > 1:
        reasons.append(f"{len(source_ids)}:{len(reference_ids)}")
    if info["length"] > DOUBT_LENGTH:
        reasons.append(f"длина {math.exp(info['length']):.2f}×")
    if info["similarity"] < DOUBT_SIMILARITY:
        reasons.append(f"сходство {info['similarity']:.2f}")
    return ", ".join(reasons)


def pairs(groups):
    """The file's pairs: one per source chapter (1-based); a group of several source chapters gives each of them
    the whole group of reference chapters, and the sentence alignment finds each one's part."""
    out = []
    for s, r, info in groups:
        for k in s:
            out.append({"source": k + 1, "reference": [x + 1 for x in r], **(info or {}), "doubt": doubt(s, r, info)})
    return out


def reference_numbers(number, reference_count):
    """The reference chapters (1-based) that go with source chapter `number`."""
    given = (BOOK.get("reference_chapters") or {}).get(str(number))
    if given is not None:
        return list(given)
    path = ROOT / BUILD / "chapters.json"
    if path.exists():
        for p in json.loads(path.read_text())["pairs"]:
            if p["source"] == number:
                return p["reference"]
        return []
    if number > reference_count:
        raise SystemExit(f"the translation has {reference_count} chapters, there is no chapter {number}: "
                         "run builder/chapters.py to match the chapters of the two editions")
    return [number]


def reference_chapter(number):
    """The translation of source chapter `number`: its reference chapters joined, footnote positions kept."""
    book = text.read_chapters(REFERENCE)
    chapter = text.Chapter(number, "", [])
    for k in reference_numbers(number, len(book)):
        part = book[k - 1]
        chapter.title = chapter.title or part.title
        chapter.footnotes += [{**f, "paragraph": f["paragraph"] + len(chapter.paragraphs)} for f in part.footnotes]
        chapter.paragraphs += part.paragraphs
    return chapter


def report(source, reference, rows):
    """Both editions side by side, one line per source chapter."""
    def short(t, k=38):
        return t if len(t) <= k else t[:k - 1] + "…"
    lines = []
    for p in rows:
        s = source[p["source"] - 1]
        r = [reference[k - 1] for k in p["reference"]]
        ref = " + ".join(c.title for c in r) or "—"
        sim = f"{p['similarity']:.2f}" if "similarity" in p else "    "
        mark = "!" if p["doubt"] else " "
        lines.append(f"{mark} {p['source']:3} {short(s.title, 24):24} {words(s):6} | {short(ref, 20):20} "
                     f"{sum(words(c) for c in r):6} | {sim} | {short(opening(s), 30):30} | {short(opening(r[0]) if r else '', 30):30}"
                     + (f"  ← {p['doubt']}" if p["doubt"] else ""))
    return "\n".join(lines)


def main():
    from sentence_transformers import SentenceTransformer
    import align
    source, reference = text.read_chapters(SOURCE), text.read_chapters(REFERENCE)
    model = SentenceTransformer(align.MODEL, device="cpu")
    groups = match(source, reference, lambda texts: model.encode(texts, batch_size=32, normalize_embeddings=True,
                                                                 convert_to_numpy=True))
    rows = pairs(groups)
    unmatched = [k + 1 for s, r, _ in groups if not s for k in r]
    print(f"original: {len(source)} chapters, {sum(map(words, source))} words; "
          f"translation: {len(reference)} chapters, {sum(map(words, reference))} words")
    print(report(source, reference, rows))
    if unmatched:
        print("translation chapters with no original:", ", ".join(f"{k} {reference[k - 1].title}" for k in unmatched))
    identity = all(p["reference"] == [p["source"]] for p in rows) and len(source) == len(reference)
    doubtful = sum(1 for p in rows if p["doubt"])
    print("chapter n goes with chapter n throughout" if identity else "the editions differ: see the pairs above",
          f"; {doubtful} doubtful" if doubtful else "; no doubtful pairs")
    path = ROOT / BUILD / "chapters.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"pairs": rows}, ensure_ascii=False, indent=1))
    print("->", path, "(fix a pair by hand with \"reference_chapters\" in the book's settings)", file=sys.stderr)


if __name__ == "__main__":
    main()
