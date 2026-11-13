"""Sentence alignment of a chapter with its published Russian translation (step S1.4a).

Needs the extra packages of builder/requirements-align.txt (LaBSE through sentence-transformers, razdel), which
./setup-env.sh installs into .venv; the chapter build only reads the result, build/chNN/alignment.json.

Method: every run of 1..MAX_SPAN consecutive sentences on each side is embedded; a monotonic dynamic programme
picks groups 1:1, 1:2, 2:1, 1:3, 3:1, 2:2 or skips (1:0, 0:1). Group cost is (1 - cos) * a * b, so merges win only
when the joined text matches clearly better than single sentences; a skip costs SKIP_COST.

Usage: .venv/bin/python builder/align.py N [--force]
"""
import json
import sys
import time

import numpy as np

import chapters
import text
from config import BUILD, ROOT, SOURCE, SPACY_MODEL

MODEL = "sentence-transformers/LaBSE"
MAX_SPAN = 3
MOVES = [(1, 1), (1, 2), (2, 1), (1, 3), (3, 1), (2, 2), (1, 0), (0, 1)]
SKIP_COST = 0.6
TIE_BREAK = 1e-3  # per extra sentence in a group: on equal cost, finer groups win


def _spans(sentences):
    keys, texts = [], []
    for i in range(len(sentences)):
        for k in range(1, MAX_SPAN + 1):
            if i + k <= len(sentences):
                keys.append((i, k))
                texts.append(" ".join(sentences[i:i + k]))
    return keys, texts


def align(es, ru, embed):
    """Monotonic alignment of two sentence lists.

    embed: list of texts -> L2-normalised vectors (rows). Returns [(es indices, ru indices, cosine or None for a skip)].
    """
    ek, et = _spans(es)
    rk, rt = _spans(ru)
    sim = np.asarray(embed(et)) @ np.asarray(embed(rt)).T
    ei = {k: n for n, k in enumerate(ek)}
    ri = {k: n for n, k in enumerate(rk)}
    n, m = len(es), len(ru)
    cost = np.full((n + 1, m + 1), np.inf)
    cost[0, 0] = 0.0
    back = {}
    for i in range(n + 1):
        for j in range(m + 1):
            if cost[i, j] == np.inf:
                continue
            for a, b in MOVES:
                if i + a > n or j + b > m:
                    continue
                c = (1 - sim[ei[(i, a)], ri[(j, b)]]) * a * b + TIE_BREAK * (a + b - 2) if a and b else SKIP_COST
                if cost[i, j] + c < cost[i + a, j + b]:
                    cost[i + a, j + b] = cost[i, j] + c
                    back[(i + a, j + b)] = (i, j, a, b)
    groups, i, j = [], n, m
    while (i, j) != (0, 0):
        pi, pj, a, b = back[(i, j)]
        score = float(sim[ei[(pi, a)], ri[(pj, b)]]) if a and b else None
        groups.append((list(range(pi, i)), list(range(pj, j)), score))
        i, j = pi, pj
    return groups[::-1]


def russian_sentences(chapter):
    """Sentences of the translation; each carries the ids of the footnotes referenced inside it."""
    import razdel
    out = []
    for p, par in enumerate(chapter.paragraphs, start=1):
        marks = [f for f in chapter.footnotes if f["paragraph"] == p]
        for s in razdel.sentenize(par):
            out.append({"paragraph": p, "text": s.text,
                        "notes": [f["id"] for f in marks if s.start <= f["offset"] <= s.stop]})
    return out


def main():
    number = int(sys.argv[1])
    out = ROOT / BUILD / f"ch{number:02d}" / "alignment.json"
    if out.exists() and "--force" not in sys.argv:
        print(f"{out} exists; --force to rebuild", file=sys.stderr)
        return
    import spacy
    from sentence_transformers import SentenceTransformer

    chapter = text.read_chapters(SOURCE)[number - 1]
    # The title (paragraph 0) is aligned with nothing: the reference's titles are not part of its sentences.
    es = [s for s in text.analyse(spacy.load(SPACY_MODEL), chapter) if s.paragraph > 0]
    reference = chapters.reference_chapter(number)
    ru = russian_sentences(reference)
    model = SentenceTransformer(MODEL, device="cpu")
    started = time.time()
    if ru:
        groups = align([s.text for s in es], [s["text"] for s in ru],
                       lambda texts: model.encode(texts, batch_size=64, normalize_embeddings=True, convert_to_numpy=True))
    else:  # the translation has no such chapter (builder/chapters.py): every sentence stands alone
        groups = [([i], [], None) for i in range(len(es))]
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({
        "model": MODEL,
        "ru": ru,
        "footnotes": {f["id"]: f["text"] for f in reference.footnotes},
        "groups": [{"es": [es[i].sid for i in e], "ru": r, "score": None if s is None else round(s, 3)} for e, r, s in groups],
    }, ensure_ascii=False, indent=1))
    kinds = {}
    for e, r, _ in groups:
        kinds[f"{len(e)}:{len(r)}"] = kinds.get(f"{len(e)}:{len(r)}", 0) + 1
    print(f"chapter {number}: es {len(es)}, ru {len(ru)} sentences, {len(groups)} groups {kinds}, "
          f"{time.time() - started:.0f}s -> {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
