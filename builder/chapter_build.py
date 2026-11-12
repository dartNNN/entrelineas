"""The segment pass of one chapter (strong model): reading and analysing the chapter, the published translation per sentence
group, the segment-pass prompts, and the preview of a built chapter. run_chapter.py runs it; prompt_audit.py and
run_lexicon.py reuse it."""
import json
import sys
import time
import types

import spacy

import model_prompts
import published
import text
from config import REFERENCE, ROOT, SOURCE


SEGMENT_MODEL = "openai/gpt-6.1-sol"


PREVIEW_PARAGRAPHS = 30


PREVIEW_THRESHOLD = 10


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


def reference_marks(references, ref_of, segments):
    """Free and uncertain published translations.

    A group whose alignment score is usual for its length is close; a group sent for checking takes the segment
    model's per-sentence verdict ("r"); a checked group the model gave no verdict for is marked uncertain.
    """
    verdicts = {}
    for sid, k in ref_of.items():
        verdicts.setdefault(k, []).append((segments.get(sid) or {}).get("r"))
    for k, r in enumerate(references):
        found = verdicts.get(k, []) if r["check"] else []
        r["loose"] = "free" in found
        r["uncertain"] = r["check"] and ("none" in found or not any(found))


def prepare(number):
    """Everything the passes need before any model call, including the segment-pass prompts."""
    c = types.SimpleNamespace(number=number, out=ROOT / "build" / f"ch{number:02d}")
    c.out.mkdir(parents=True, exist_ok=True)
    c.book = text.read_chapters(SOURCE)
    c.chapter = c.book[number - 1]
    ref_paragraphs = text.read_chapters(REFERENCE)[number - 1].paragraphs
    c.sentences = text.analyse(spacy.load("es_core_news_md"), c.chapter)
    c.by_id = {s.sid: s for s in c.sentences}
    c.order = {s.sid: k for k, s in enumerate(c.sentences)}
    c.words = sum(1 for s in c.sentences for t in s.tokens if t.text.isalpha())
    c.source_footnotes = {f["id"]: f["text"] for f in c.chapter.footnotes}
    log(f"chapter {number}: {len(c.chapter.paragraphs)} paragraphs, {len(c.sentences)} sentences, {c.words} words")
    c.references, c.ref_of, c.footnotes = [], {}, {}
    if (c.out / "alignment.json").exists():
        alignment = json.loads((c.out / "alignment.json").read_text())
        c.references, c.ref_of = published.sentence_references(c.sentences, alignment)
        for r, z in zip(c.references, published.score_deviations(c.references)):
            r["z"] = None if z is None else round(z, 2)
            r["check"] = z is not None and z < published.CHECK_BELOW_Z
        c.footnotes = {i: alignment["footnotes"][i] for r in c.references for i in r["notes"]}
        log(f"reference: sentence alignment, {len(c.references)} groups, {len(c.footnotes)} footnotes")
    else:
        log("reference: proportional excerpt (no alignment.json; see builder/align.py)")
    c.segment_system = model_prompts.SEGMENT_SYSTEM_ALIGNED if c.references else model_prompts.SEGMENT_SYSTEM
    c.prompts = {}
    for k, chunk in enumerate(model_prompts.chunk_sentences(c.chapter)):
        prev_par = chunk[0].paragraph - 1
        previous = c.chapter.paragraphs[prev_par - 1] if prev_par >= 1 else ""
        if c.references:
            c.prompts[f"chunk{k:02d}"] = model_prompts.aligned_segment_prompt(chunk, previous, c.references, c.ref_of, c.footnotes,
                                                                       c.source_footnotes)
        else:
            c.prompts[f"chunk{k:02d}"] = model_prompts.segment_prompt(chunk, previous,
                                                               model_prompts.reference_excerpt(c.chapter, ref_paragraphs, chunk),
                                                               c.source_footnotes)
    return c


def render(result, stats, path):
    lines = [f"# Глава {result['chapter']}: {result['title']}", "",
             f"Слов: {stats['words']}, предложений: {stats['sentences']}. Разметка главы: ${stats['segment_cost']:.2f}, "
             f"оценка разметки на книгу: ${stats['book_estimate']:.1f} (словарный проход считается по книге, "
             f"build/lexicon/stats.json). Проблемных предложений: {stats['segment_problems']}. "
             f"Подсказки к словам ({stats.get('lexical_comments', 0)}) показаны до {PREVIEW_THRESHOLD}-го появления "
             f"значения.", ""]
    current_par = None
    sentences = result["sentences"]
    for k, s in enumerate(sentences):
        par = s["sid"].split("s")[0]
        if par != current_par:
            if current_par is not None:
                lines.append("")
            current_par = par
            if len({x["sid"].split("s")[0] for x in result["sentences"] if x["sid"] <= s["sid"]}) > PREVIEW_PARAGRAPHS:
                break
        ann = s["annotations"] or {}
        lex = {l["token"]: l for l in s["lexical"] if l["ordinal"] <= PREVIEW_THRESHOLD}
        parts = []
        for seg in ann.get("s", []):
            words = []
            for i in range(seg[0], seg[1] + 1):
                if i >= len(s["tokens"]):
                    break
                words.append(s["tokens"][i])
                if i in lex:
                    l = lex[i]
                    extra = "; ".join(x for x in (l.get("form"), l.get("gram")) if x)
                    words.append(f"[{l['gloss']}{'; ' + extra if extra else ''}]")
            hint = seg[2] + (f" = {seg[3]}" if len(seg) > 3 and seg[3] else "")
            parts.append(" ".join(words) + f" ({hint})")
        lines.append(" ".join(parts) if parts else s["text"])
        for g in ann.get("g", []):
            lines.append(f"- грамматика: {g[3] if len(g) > 3 else g}")
        for c in ann.get("c", []):
            lines.append(f"- контекст: {c[2] if len(c) > 2 else c}")
        for v in ann.get("v", []):
            lines.append(f"- варианты: {', '.join(v[2]) if len(v) > 2 else v} — {v[3] if len(v) > 3 else ''}")
        for e in ann.get("e", []):
            lines.append(f"- выражение: {e[2]} — {e[3]}" if len(e) > 3 else f"- выражение: {e}")
        ref = s.get("ref")
        if ref is not None and (k + 1 == len(sentences) or sentences[k + 1].get("ref") != ref):
            lines.append(f"- перевод: {result['references'][ref]['text'] or '—'}")
    path.write_text("\n".join(lines))
