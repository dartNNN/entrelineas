"""The book-level lexical pass (cheap model): every word of the built chapters once, with its dictionary entry and its
occurrences; packing the calls, reading the answers, numbering occurrences per sense, and attaching the dictionary
entries to the chapters. run_lexicon.py runs it; prompt_audit.py reuses it."""
import concurrent.futures as cf
import json
import sys
import time
import types

import spacy

import dictionary
import lexical_answers
import llm
import model_prompts
import segment_answers
import text
import units as semantic_units
from config import ROOT, SOURCE


LEXICAL_MODEL = "deepseek/deepseek-v4.1-flash"


MAX_OCCURRENCE = 20


PART_OCCURRENCES = 60


CALL_CHARS = 24000


# Sense inventories, in priority order (build/dict is local: the dictionaries are copyrighted).
DICTIONARIES = ["UniversalEsRu", "ModernUsageEsRu", "LatinAmericanEsRu"]


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, file=sys.stderr, flush=True)


def prepare():
    """Built chapters, their sentences and semantic units, and the lemma groups with the occurrences to send."""
    L = types.SimpleNamespace(out=ROOT / "build" / "lexicon")
    L.out.mkdir(parents=True, exist_ok=True)
    nlp = spacy.load("es_core_news_md")
    L.chapters, L.sentences = {}, []
    for chapter in text.read_chapters(SOURCE):
        path = ROOT / "build" / f"ch{chapter.index:02d}" / "chapter.json"
        if not path.exists():
            continue
        data = json.loads(path.read_text())
        sentences = text.analyse(nlp, chapter)
        L.chapters[chapter.index] = types.SimpleNamespace(path=path, data=data, sentences=sentences)
        L.sentences += sentences
    if not L.chapters:
        raise SystemExit("no built chapters: run builder/run_chapter.py first")
    L.by_id = {s.sid: s for s in L.sentences}
    L.segments, L.reference = {}, {}
    for ch in L.chapters.values():
        refs = ch.data.get("references") or []
        for s in ch.data["sentences"]:
            L.segments[s["sid"]] = s.get("annotations")
            if refs and s.get("ref") is not None:
                L.reference[s["sid"]] = refs[s["ref"]]["text"] or ""
    L.units = semantic_units.build(L.sentences)
    L.unit_of = {s.sid: k for k, u in enumerate(L.units) for s in u}
    L.hints, L.missing = {}, set()
    L.groups = sorted(text.lexical_occurrences(L.sentences, 10 ** 9).items())
    L.books = dictionary.Dictionaries([ROOT / "build" / "dict" / "lingvo" / name for name in DICTIONARIES])
    L.texts = {key: [L.by_id[o["sid"]].text for o in occs] for key, occs in L.groups}
    L.dict_entries = {key: L.books.inventory(key.split("|")[0], L.texts[key]) for key, _ in L.groups} if L.books else {}
    L.chosen = {key: occs if len(L.dict_entries.get(key, (None, []))[1]) >= 2 else occs[:MAX_OCCURRENCE]
                for key, occs in L.groups}
    log(f"lexicon: chapters {sorted(L.chapters)}, {len(L.sentences)} sentences, {len(L.units)} units, "
        f"{len(L.groups)} groups, {sum(map(len, L.chosen.values()))} occurrences to send")
    return L


def occurrence_line(L, o):
    k = L.unit_of[o["sid"]]
    if k not in L.hints:
        L.hints[k] = semantic_units.names_hint(L.units, k)
    return model_prompts.passage(L.units[k], o["sid"], o["token"], segment_answers.segment_literal(L.segments.get(o["sid"]), o["token"]),
                          L.reference.get(o["sid"]), L.hints[k])


def blocks(L, keys):
    """{header: (key, part, block text, occurrence count)} for the given groups, split into parts."""
    out = {}
    for key in keys:
        occs = L.chosen[key]
        parts = [occs[i:i + PART_OCCURRENCES] for i in range(0, len(occs), PART_OCCURRENCES)]
        for n, part in enumerate(parts, start=1):
            header = key if len(parts) == 1 else f"{key} @{n}"
            out[header] = (key, n, model_prompts.lexical_block(header, [occurrence_line(L, o) for o in part], L.dict_entries.get(key)),
                           len(part))
    return out


def call_answers(call, res):
    """{header: answer} for one call. The model sometimes drops the " @n" of a part: such an answer is taken for the
    part when that part is the only one of its word in the call."""
    headers = [b.split("\n", 1)[0][3:] for b in call]
    res = {k.strip(): v for k, v in res.items()}
    out = {h: res[h] for h in headers if h in res}
    for h in headers:
        key = h.split(" @")[0]
        if h not in out and key in res and sum(1 for x in headers if x.split(" @")[0] == key) == 1:
            out[h] = res[key]
    return {h: lexical_answers.normalize_dict_ids(v) for h, v in out.items()}


def run_calls(calls):
    """Runs lexical calls; a failed call is split in half and retried. Returns ({header: answer}, cost)."""
    answers, total = {}, 0.0
    while calls:
        failed = []
        with cf.ThreadPoolExecutor(8) as pool:
            futures = {pool.submit(llm.complete, LEXICAL_MODEL, model_prompts.LEXICAL_SYSTEM, "\n\n".join(c), 16000, 2,
                                   {"reasoning": {"enabled": False}}): c for c in calls}
            for f in cf.as_completed(futures):
                c = futures[f]
                try:
                    res, cost, _ = f.result()
                    answers.update(call_answers(c, res))
                    total += cost
                except RuntimeError as exc:
                    log(f"lexical call with {len(c)} groups failed: {exc}")
                    if len(c) > 1:
                        failed += [c[:len(c) // 2], c[len(c) // 2:]]
        calls = failed
    return answers, total


def per_occurrence(answer, count):
    """The model sometimes answers a long group with one sense per occurrence and no "occ": read that as the sense of
    each occurrence in order, senses joined as in merging parts: by dictionary id, or by gloss when there is none."""
    if not isinstance(answer, dict) or answer.get("skip") or any(o is not None for o in answer.get("occ") or []):
        return answer
    listed = [s for s in answer.get("senses", []) if isinstance(s, dict)]
    if len(listed) != count:
        return answer
    senses, ids, occ = [], {}, []
    for s in listed:
        identity = ("dict", str(s["dict"])) if s.get("dict") is not None else ("gloss", s.get("gloss"))
        if identity not in ids:
            ids[identity] = str(len(senses) + 1)
            senses.append({**s, "id": ids[identity]})
        occ.append(ids[identity])
    log(f"read {count} senses of a group as one per occurrence: {len(senses)} distinct")
    return {**answer, "senses": senses, "occ": occ}


def complete(answer, count):
    """An answer gives a sense for every occurrence, and every sense id it uses exists."""
    if not isinstance(answer, dict):
        return False
    if answer.get("skip"):
        return True
    ids = {str(s.get("id")) for s in answer.get("senses", []) if isinstance(s, dict)}
    occ = answer.get("occ") or []
    return len(occ) >= count and all(o is not None and str(o) in ids for o in occ[:count])


def lexical_pass(L, keys):
    """Entries for the given groups, parts merged. Returns ({key: entry}, cost)."""
    bl = blocks(L, keys)
    answers, cost = run_calls(model_prompts.pack_calls([b[2] for b in bl.values()], CALL_CHARS))
    answers = {h: per_occurrence(a, bl[h][3]) for h, a in answers.items() if h in bl}
    # Groups the model left out or answered incompletely are asked once more, together, in calls of their own;
    # the second answer replaces the first only when it is complete.
    redo = [h for h, b in bl.items() if not complete(answers.get(h), b[3])]
    if redo:
        more, extra = run_calls(model_prompts.pack_calls([bl[h][2] for h in redo], CALL_CHARS))
        more = {h: per_occurrence(a, bl[h][3]) for h, a in more.items() if h in bl}
        fixed = {h: a for h, a in more.items() if complete(a, bl[h][3]) or not isinstance(answers.get(h), dict)}
        answers.update(fixed)
        cost += extra
        log(f"asked again for {len(redo)} groups left out or incomplete, {sum(complete(fixed.get(h), bl[h][3]) for h in redo)} now complete")
    parts = {}
    for header, (key, n, _, count) in sorted(bl.items(), key=lambda kv: (kv[1][0], kv[1][1])):
        if not isinstance(answers.get(header), dict):
            L.missing.add(header)
        a = answers.get(header) if isinstance(answers.get(header), dict) else {"senses": [], "occ": []}
        if not a.get("skip"):
            occ = list(a.get("occ") or [])[:count]
            a = {**a, "occ": occ + [None] * (count - len(occ))}
        parts.setdefault(key, []).append(a)
    return {key: lexical_answers.merge_parts(p) for key, p in parts.items()}, cost


def attach_entries(L, entries):
    """Puts into every built chapter the dictionary entries of its commented words, keyed by the comment's key
    without the sense, and into each comment the id of its sense in that entry ("sense"). A corrected lemma keeps the
    entry it was answered with; where two groups share a corrected key, the entry of the group that owns the key is
    kept and the moved group's comments get no sense id."""
    key_set = {key for key, _ in L.groups}
    chosen, inventory = {}, {}
    for key, e in entries.items():
        if not isinstance(e, dict) or e.get("skip"):
            continue
        headword, senses = L.dict_entries.get(key, (None, []))
        if not headword:
            continue
        fixed_key, _, _, prefix = lexical_answers.apply_fix(key, e.get("fix"), key_set)
        if not prefix or fixed_key not in inventory:
            inventory[fixed_key] = {"headword": headword, "senses": [{"id": x["id"], "text": x["text"], "gram": x.get("gram")} for x in senses]}
        if prefix:
            continue
        ids = {x["id"] for x in senses}
        for sense in e.get("senses", []):
            if isinstance(sense, dict) and sense.get("dict") is not None and str(sense["dict"]) in ids:
                chosen[f"{fixed_key}#{sense['id']}"] = str(sense["dict"])
    for ch in L.chapters.values():
        used = set()
        for row in ch.data["sentences"]:
            for lex in row.get("lexical", []):
                entry_key = lex["key"].split("#")[0]
                lex.pop("sense", None)
                if entry_key in inventory:
                    used.add(entry_key)
                    if lex["key"] in chosen:
                        lex["sense"] = chosen[lex["key"]]
        ch.data["entries"] = {k: inventory[k] for k in sorted(used)}
        ch.path.write_text(json.dumps(ch.data, ensure_ascii=False, indent=1))
        log(f"chapter {ch.path.parent.name}: {len(used)} dictionary entries attached")
