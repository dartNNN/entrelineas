"""Book-level lexical pass: one dictionary entry per word over every built chapter.

Runs after run_chapter.py: reads the segment pass of every built chapter (build/chNN/chapter.json), sends each word
once with its Lingvo entry and its occurrences, each in its semantic unit with the segment literal and the published
translation, and writes the lexical comments back into each chapter.json. A word with two or more dictionary senses
gets all its occurrences, so each one knows its sense; any other word gets its first MAX_OCCURRENCE. A very frequent
word is split into parts of PART_OCCURRENCES. Occurrences are numbered per sense in book order, and only the first
MAX_OCCURRENCE of each sense get a comment. Writes build/lexicon/{entries,stats,mismatches,dictionary_misses}.json.
Each chapter also gets the dictionary entries of its words (for the word card) and, per comment, the id of the
chosen sense in that entry.

Usage: source ~/.config/openrouter.env && .venv/bin/python builder/run_lexicon.py
       .venv/bin/python builder/run_lexicon.py --entries   (only attach the entries to built chapters; no model calls)
"""
import json
import re
import sys

import chapter_build
import lexical_answers
import segment_answers
import text
from lexicon_build import MAX_OCCURRENCE, log, prepare, lexical_pass, attach_entries


def main():
    L = prepare()
    if "--entries" in sys.argv:
        attach_entries(L, json.loads((L.out / "entries.json").read_text()))
        return
    keys = [key for key, _ in L.groups]
    log(f"dictionary: {sum(1 for h, _ in L.dict_entries.values() if h)}/{len(keys)} groups found" if L.books else "dictionary: none")
    entries, cost = lexical_pass(L, keys)
    # A lemma the model corrected may have its own dictionary entry: ask again with that entry.
    again = []
    for key in keys:
        fix = (entries.get(key) or {}).get("fix")
        if L.books and fix:
            headword, senses = L.books.inventory(fix.split("|")[0], L.texts[key])
            if headword and headword != L.dict_entries.get(key, (None, []))[0]:
                L.dict_entries[key] = (headword, senses)
                occs = dict(L.groups)[key]
                L.chosen[key] = occs if len(senses) >= 2 else occs[:MAX_OCCURRENCE]
                again.append(key)
    if again:
        L.missing -= {h for h in L.missing if h.split(" @")[0] in again}
        redo, extra = lexical_pass(L, again)
        entries.update(redo)
        cost += extra
        log(f"lexical pass again for {len(again)} corrected lemmas with their own dictionary entry")

    # Comments: occurrences numbered per sense in book order, the first MAX_OCCURRENCE of each sense kept.
    key_set = set(keys)
    comments, ordinals, unassigned = {}, {}, 0
    for key, occs in sorted(L.chosen.items(), key=lambda kv: kv[0]):
        e = entries.get(key) or {}
        if e.get("skip"):
            continue
        fixed_key, lemma, pos_changed, prefix = lexical_answers.apply_fix(key, e.get("fix"), key_set)
        senses = {str(s["id"]): s for s in e.get("senses", []) if isinstance(s, dict)}
        for o, sid in zip(occs, e.get("occ") or []):
            sense = senses.get(str(sid)) if sid is not None else None
            if sense is None:
                unassigned += 1
                continue
            comments[o["oid"]] = (fixed_key, lemma, pos_changed, prefix, sense)
    order = {s.sid: k for k, s in enumerate(L.sentences)}
    for oid in sorted(comments, key=lambda x: (order[x.rsplit("t", 1)[0]], int(x.rsplit("t", 1)[1]))):
        fixed_key, lemma, pos_changed, prefix, sense = comments[oid]
        item = f"{fixed_key}#{prefix}{sense['id']}"
        ordinals[item] = ordinals.get(item, 0) + 1
        comments[oid] = (item, lemma, pos_changed, sense, ordinals[item])

    mismatches, total_comments = [], 0
    for number, ch in L.chapters.items():
        for s, row in zip(ch.sentences, ch.data["sentences"], strict=True):
            assert s.sid == row["sid"], f"{ch.path} was built from another text: rebuild the chapter"
            lex = []
            for t in s.tokens:
                c = comments.get(f"{s.sid}t{t.i}")
                if not c or c[4] > MAX_OCCURRENCE:
                    continue
                item, lemma, pos_changed, sense, ordinal = c
                # The tagger's morphology is wrong for a word whose part of speech the model corrected: no form note.
                form = "" if pos_changed else text.form_note(t, lemma)
                lex.append({"token": t.i, "lemma": lemma or t.lemma, "key": item, "ordinal": ordinal,
                            "gloss": sense["gloss"], "gram": sense.get("gram"), "form": form})
                lit = segment_answers.segment_literal(row.get("annotations"), t.i).lower()
                stems = [w[:4] for w in re.findall(r"[а-яё]{4,}", sense["gloss"].lower())]
                if lit and stems and not any(st in lit for st in stems):
                    mismatches.append((t.text, sense["gloss"], lit))
            row["lexical"] = lex
            total_comments += len(lex)
        ch.path.write_text(json.dumps(ch.data, ensure_ascii=False, indent=1))
        stats_path = ch.path.parent / "stats.json"
        stats = json.loads(stats_path.read_text()) if stats_path.exists() else {}
        stats["lexical_comments"] = sum(len(r["lexical"]) for r in ch.data["sentences"])
        stats_path.write_text(json.dumps(stats, ensure_ascii=False, indent=1))
        chapter_build.render(ch.data, stats, ch.path.parent / "preview.md")

    # Senses the model did not tie to the dictionary entry it was shown: candidates for a manual look.
    dict_checks, outside = [0, 0, 0], []
    for key, (headword, senses) in L.dict_entries.items():
        e = entries.get(key) or {}
        if not senses or e.get("skip"):
            continue
        for k, n in enumerate(lexical_answers.check_dictionary_senses(e, senses)):
            dict_checks[k] += n
        ids = {x["id"] for x in senses}
        outside += [{"key": key, "headword": headword, "gloss": s["gloss"], "dict": s.get("dict")}
                    for s in e.get("senses", []) if s.get("dict") is None or str(s["dict"]) not in ids]
    (L.out / "entries.json").write_text(json.dumps(entries, ensure_ascii=False, indent=1))
    attach_entries(L, entries)
    (L.out / "mismatches.json").write_text(json.dumps(mismatches, ensure_ascii=False, indent=1))
    (L.out / "dictionary_misses.json").write_text(json.dumps(outside, ensure_ascii=False, indent=1))
    stats = {"chapters": sorted(L.chapters), "units": len(L.units), "lexical_cost": cost, "groups": len(keys),
             "occurrences_sent": sum(map(len, L.chosen.values())), "unassigned_occurrences": unassigned,
             "comments": total_comments, "unanswered_groups": len(L.missing),
             "senses": sum(len(e.get("senses", [])) for e in entries.values() if isinstance(e, dict)),
             "lemma_fixes": sum(1 for e in entries.values() if isinstance(e, dict) and e.get("fix")),
             "dictionary_groups": f"{sum(1 for h, _ in L.dict_entries.values() if h)}/{len(keys)}",
             "senses_from_dictionary": dict_checks[0], "senses_outside_dictionary": dict_checks[1],
             "invalid_dictionary_ids": dict_checks[2], "lexical_retried_with_fixed_lemma": len(again),
             "gloss_literal_mismatch": f"{len(mismatches)}/{total_comments}"}
    (L.out / "stats.json").write_text(json.dumps(stats, ensure_ascii=False, indent=1))
    log(json.dumps(stats, ensure_ascii=False))


if __name__ == "__main__":
    main()
