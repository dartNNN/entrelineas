"""Segment pass for one chapter: writes build/chNN/chapter.json plus a readable preview.

Lexical comments come from the book-level pass, builder/run_lexicon.py, run after the chapters are built; a rebuilt
chapter has none until it runs again.

Usage: source ~/.config/openrouter.env && .venv/bin/python builder/run_chapter.py 1 [--sync]
"""
import concurrent.futures as cf
import json
import sys

import llm
import segment_answers
from chapter_build import SEGMENT_MODEL, log, reference_marks, prepare, render


def main():
    number = int(sys.argv[1])
    use_batch = "--sync" not in sys.argv
    c = prepare(number)
    out, book, chapter, sentences, words = c.out, c.book, c.chapter, c.sentences, c.words
    references, ref_of, footnotes, segment_system, prompts = c.references, c.ref_of, c.footnotes, c.segment_system, c.prompts

    # Segment pass (strong model).
    seg_cost = 0.0
    if use_batch:
        seg_results, seg_cost, errors = llm.batch(SEGMENT_MODEL, segment_system, prompts, log=log)
        for cid in errors:
            log(f"batch error {cid}: {errors[cid]}; retrying sync")
    else:
        seg_results, errors = {}, {c: None for c in prompts}
    with cf.ThreadPoolExecutor(6) as pool:
        futures = {pool.submit(llm.complete, SEGMENT_MODEL, segment_system, prompts[c], 32000): c
                   for c in prompts if c not in seg_results}
        for f in cf.as_completed(futures):
            res, cost, _ = f.result()
            seg_results[futures[f]] = res
            seg_cost += cost
    segments = {}
    for res in seg_results.values():
        segments.update(res)
    problems = {}
    for s in sentences:
        ann = segments.get(s.sid)
        p = ["missing"] if ann is None else segment_answers.check_segments(s, ann)
        if p:
            problems[s.sid] = p
    log(f"segment pass: ${seg_cost:.3f}, sentences with problems: {len(problems)}")
    reference_marks(references, ref_of, segments)

    result = {"chapter": number, "title": chapter.title, "references": references, "footnotes": footnotes,
              "source_footnotes": c.source_footnotes, "sentences": []}
    for s in sentences:
        result["sentences"].append({"sid": s.sid, "text": s.text, "tokens": [t.text for t in s.tokens], "ws": [t.ws for t in s.tokens],
                                    "paragraph": s.paragraph, "ref": ref_of.get(s.sid), "notes": s.notes,
                                    "annotations": segments.get(s.sid), "lexical": [],
                                    "problems": problems.get(s.sid, [])})
    (out / "chapter.json").write_text(json.dumps(result, ensure_ascii=False, indent=1))

    total = seg_cost
    chapter_words = sum(len(p.split()) for p in chapter.paragraphs)
    book_words = sum(len(p.split()) for c in book for p in c.paragraphs)
    stats = {"words": words, "sentences": len(sentences), "segment_cost": seg_cost,
             "total_cost": total, "book_estimate": total * book_words / chapter_words if chapter_words else 0,
             "segment_problems": len(problems), "lexical_comments": 0,
             "references": len(references),
             "loose_references": sum(1 for r in references if r["loose"]),
             "uncertain_references": sum(1 for r in references if r["uncertain"]),
             "references_checked_by_model": sum(1 for r in references if r["check"])}
    (out / "stats.json").write_text(json.dumps(stats, ensure_ascii=False, indent=1))
    render(result, stats, out / "preview.md")
    log(json.dumps(stats, ensure_ascii=False))


if __name__ == "__main__":
    main()
