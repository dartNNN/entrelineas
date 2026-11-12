"""Builds reader chapters without any language model or API key.

What it needs, all local: the original and the published translation as FB2 (builder/config.py), StarDict
dictionaries in build/dict/<name> (see DICTIONARIES; Lingvo converted to StarDict works best), spaCy for
tokens and lemmas, and the sentence alignment (builder/align.py, LaBSE on the CPU) to pair every sentence with the
translator's text.

What the reader gets: the original with the published translation per sentence (in the word card and, if wanted,
after the paragraph), the translator's and the author's footnotes, and for every content word a gloss taken from
its dictionary entry with the whole entry in the word card. There are no literal translations, grammar or context
notes: those need a model.

How a sense is chosen without a model, per occurrence: the numbered sense sharing most Russian word stems with the
published translation of the sentence, otherwise the first sense. Measured on chapter 1 against the model's choice
(2026-11-07): 60 percent agreement on polysemous words against 53 percent for always the first sense; where
the translation decides, 68 percent. Matching the entry's examples and set phrases in the sentence did not help
(42 percent where it fired, no gain overall) and is left out. Glosses are marked "from": "dictionary" so readers can
say they may not fit the context.

Writes build/offline/chNN/chapter.json (the same shape as run_chapter.py plus "entries") and build/offline/stats.json.
Usage: .venv/bin/python builder/build_offline.py 1 [2 ...]   (then: export_package.py --offline 1 [2 ...])
"""
import json
import re
import subprocess
import sys
import time
from pathlib import Path

import spacy

import dictionary
import published
import text
from config import ROOT, SOURCE

MAX_OCCURRENCE = 20
DICTIONARIES = ["lingvo/UniversalEsRu", "lingvo/ModernUsageEsRu", "lingvo/LatinAmericanEsRu"]
GLOSS_WORDS = 6
OUT = ROOT / "build" / "offline"


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


def stems(t):
    """Russian word stems (first five letters of words of four letters and more)."""
    return {w[:5] for w in re.findall(r"[а-яё]{4,}", t.lower())}


def choose(senses, reference):
    """(sense, how) for one occurrence: 'translation' or 'first'; (None, None) when the entry has no senses."""
    if not senses:
        return None, None
    numbered = [s for s in senses if s["id"][0].isdigit()] or senses
    ref = stems(reference or "")
    if ref:
        best = max([(len(stems(s["text"]) & ref), -i, s) for i, s in enumerate(numbered)], key=lambda x: (x[0], x[1]))
        if best[0] > 0:
            return best[2], "translation"
    return numbered[0], "first"


def gloss(sense):
    """A short gloss from a sense: the meaning of a set phrase, or the first words of a sense up to ';'."""
    t = sense["text"]
    if sense["id"][0] in "FP":
        parts = re.split(r"\s[—–-]\s|:\s", t, maxsplit=1)
        t = parts[1] if len(parts) > 1 else t
    t = re.sub(r"\s+([,.;])", r"\1", re.sub(r"\([^)]*\)", "", t.split(";")[0])).strip(" ,")
    words = t.split() or sense["text"].split()  # a sense that is only a remark in brackets keeps its text
    return " ".join(words[:GLOSS_WORDS]) + ("…" if len(words) > GLOSS_WORDS else "")


def ensure_alignment(number):
    path = ROOT / "build" / f"ch{number:02d}" / "alignment.json"
    if not path.exists():
        log(f"chapter {number}: aligning with the published translation (LaBSE, a few minutes on the CPU)")
        subprocess.run([sys.executable, str(Path(__file__).parent / "align.py"), str(number)], check=True)
    return json.loads(path.read_text())


def main():
    numbers = [int(a) for a in sys.argv[1:] if a.isdigit()] or [1]
    nlp = spacy.load("es_core_news_md")
    books = dictionary.Dictionaries([ROOT / "build" / "dict" / name for name in DICTIONARIES])
    if not books:
        raise SystemExit("no dictionaries in build/dict (see DICTIONARIES in builder/build_offline.py)")
    book = text.read_chapters(SOURCE)
    chapters = {}
    for n in numbers:
        chapter = book[n - 1]
        sentences = text.analyse(nlp, chapter)
        alignment = ensure_alignment(n)
        references, ref_of = published.sentence_references(sentences, alignment)
        for r, z in zip(references, published.score_deviations(references)):
            r["check"] = z is not None and z < published.CHECK_BELOW_Z
        chapters[n] = (chapter, sentences, references, ref_of, alignment)
    all_sentences = [s for n in numbers for s in chapters[n][1]]
    by_id = {s.sid: s for s in all_sentences}
    groups = text.lexical_occurrences(all_sentences, 10 ** 9)

    stats = {"chapters": numbers, "occurrences": 0, "with_entry": 0, "translation": 0, "first": 0, "groups": len(groups),
             "groups_with_entry": 0}
    choice, entries_of = {}, {}
    for key, occs in groups.items():
        lemma = key.split("|")[0]
        headword, senses = books.inventory(lemma, [by_id[o["sid"]].text for o in occs])
        stats["occurrences"] += len(occs)
        if not senses:
            continue
        stats["groups_with_entry"] += 1
        entries_of[key] = {"headword": headword, "senses": [{"id": s["id"], "text": s["text"], "gram": s.get("gram")} for s in senses]}
        for o in occs:
            n = int(o["sid"][1:3])
            _, _, references, ref_of, _ = chapters[n]
            ref = ref_of.get(o["sid"])
            sense, how = choose(senses, references[ref]["text"] if ref is not None else None)
            choice[o["oid"]] = (key, sense, how)
            stats["with_entry"] += 1
            stats[how] += 1

    # Occurrences numbered per sense in book order; the first MAX_OCCURRENCE of each sense get a gloss.
    ordinals, numbered = {}, {}
    for s in all_sentences:
        for t in s.tokens:
            c = choice.get(f"{s.sid}t{t.i}")
            if c:
                item = f"{c[0]}#{c[1]['id']}"
                ordinals[item] = ordinals.get(item, 0) + 1
                numbered[f"{s.sid}t{t.i}"] = (item, ordinals[item])

    for n in numbers:
        chapter, sentences, references, ref_of, alignment = chapters[n]
        rows, used = [], set()
        for s in sentences:
            lex = []
            for t in s.tokens:
                oid = f"{s.sid}t{t.i}"
                if oid not in numbered or numbered[oid][1] > MAX_OCCURRENCE:
                    continue
                key, sense, _ = choice[oid]
                used.add(key)
                lemma = key.split("|")[0]
                lex.append({"token": t.i, "lemma": lemma, "key": numbered[oid][0], "ordinal": numbered[oid][1],
                            "gloss": gloss(sense), "gram": sense.get("gram"), "form": text.form_note(t, lemma), "from": "dictionary"})
            rows.append({"sid": s.sid, "text": s.text, "tokens": [t.text for t in s.tokens], "ws": [t.ws for t in s.tokens],
                         "paragraph": s.paragraph, "ref": ref_of.get(s.sid), "notes": s.notes,
                         "annotations": {"s": [], "g": [], "c": [], "v": [], "e": []}, "lexical": lex})
        refs = [{"text": r["text"], "loose": False, "uncertain": r["check"], "notes": r["notes"]} for r in references]
        footnotes = {i: alignment["footnotes"][i] for r in references for i in r["notes"]}
        data = {"chapter": n, "title": chapter.title, "references": refs, "footnotes": footnotes,
                "source_footnotes": {f["id"]: f["text"] for f in chapter.footnotes},
                "entries": {k: entries_of[k] for k in sorted(used)}, "sentences": rows}
        out = OUT / f"ch{n:02d}"
        out.mkdir(parents=True, exist_ok=True)
        (out / "chapter.json").write_text(json.dumps(data, ensure_ascii=False, indent=1))
        log(f"chapter {n}: {len(rows)} sentences, {sum(len(r['lexical']) for r in rows)} glosses, {len(used)} entries")
    (OUT / "stats.json").write_text(json.dumps(stats, ensure_ascii=False, indent=1))
    log(json.dumps(stats, ensure_ascii=False))


if __name__ == "__main__":
    main()
