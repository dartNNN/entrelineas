"""Controlled grammar topic catalog for readers, built section by section from the PCIC grammar inventory.

A topic is one construction a reader has to recognise in a sentence to understand it; its level is the earliest
PCIC level of its rules. Rules that matter only for speaking and writing are skipped with a reason. Every rule line
of the section must end up in exactly one topic or among the skipped ones; the check is done here, not trusted to
the model. Writes build/grammar/catalog/<section>.json.

Usage: source ~/.config/openrouter.env && .venv/bin/python builder/grammar_catalog.py 15.3.6 [...]
"""
import json
import sys

import llm
from config import ROOT

MODEL = "openai/gpt-6.1-sol"
LEVEL_ORDER = ["A1", "A2", "B1", "B2", "C1", "C2"]

SYSTEM = """You build a controlled catalog of Spanish grammar topics for a Russian-speaking reader of novels
(reading comprehension only), from one section of the grammar inventory of the Plan curricular del Instituto Cervantes.

Input: the section's lines in order, per level. "H [level] text" lines are headings, context only. "L<n> [level] text"
lines are rules, usually "rule / example / example".

Principles:
1. A topic is one construction a reader must recognise in a sentence to understand it. Put rules in one topic when the
   same explanation lets the reader understand sentences of both; make separate topics when the meaning differs.
2. Skip rules that matter only for speaking or writing and never show up as such in correct text: forms marked as
   wrong with *, spelling, restrictions on what may not be used, conjugation paradigms and irregular forms (the
   reader's word hints already explain every form). Give the reason.
3. A topic's level is the earliest level among its rules.
4. Every L line goes to exactly one topic or to skipped.
5. One form, one topic. Describe each topic's form: "forms", the verb forms of its key verb group, from this list
   only: Ind.Pres Ind.Imp Ind.Past Ind.Fut Cnd Sub.Pres Sub.Imp Sub.Fut Imp Inf Ger Part, with ".Perf" added for the
   compound tense with haber (Ind.Imp.Perf is the pluperfect, Sub.Imp.Perf the pluperfect subjunctive, Cnd.Perf the
   conditional perfect); empty list if no verb form defines it. "trigger": the word or words that introduce the
   construction in the text ("si", "como si", "por si", "ojalá", "aunque"), or null. "role": where the key verb stands
   relative to the trigger: "clause" (in the clause the trigger opens), "main" (in the main clause that such a clause
   depends on), "alone" (in a trigger clause with no main clause, as in ¡Si me llamara!), or null without trigger.
   "by_form": true when form, trigger and role identify the topic, false when the reader needs the meaning to tell it
   from other topics with the same form (futuro de probabilidad vs plain future). Two topics with by_form true must
   never share form, trigger and role; when they would, merge them or set by_form false on the one that needs the
   meaning.

Each topic: "key" (English snake_case, unique, with a short area prefix such as cond_ or verb_), "name_ru" (short: the Spanish
pattern and what it means, e.g. "si + imperfecto de subjuntivo: нереальное условие в настоящем"), "level",
"lines" (the L numbers), "explanation_ru" (1-2 plain sentences telling the reader how to understand it, no linguistic
jargon beyond the Spanish tense names), "example" (one Spanish example taken from its lines) and "example_ru"
(its Russian translation), "forms", "trigger", "role", "by_form".
Return ONLY JSON: {"topics": [...], "skipped": [{"lines": [n, ...], "reason": "..."}]}"""
FORMS = {f + p for f in ("Ind.Pres", "Ind.Imp", "Ind.Past", "Ind.Fut", "Cnd", "Sub.Pres", "Sub.Imp", "Sub.Fut", "Imp", "Inf",
                         "Ger", "Part") for p in ("", ".Perf")}


def section_input(section):
    """Prompt text and the rule lines by number."""
    lines, rules, n = [], {}, 0
    for level in LEVEL_ORDER:
        for text in section["levels"].get(level, []):
            if text.startswith("[v."):
                continue
            if " / " in text:
                n += 1
                rules[n] = (level, text)
                lines.append(f"L{n} [{level}] {text}")
            else:
                lines.append(f"H [{level}] {text}")
    head = f"Section {section['number']}: {section.get('parent') or ''} / {section['title']}".replace(" /  / ", " / ")
    return head + "\n" + "\n".join(lines), rules


def check(result, rules):
    """Problems with the model's assignment: missing, repeated or unknown lines, wrong levels, duplicate keys."""
    problems, seen = [], {}
    for where, groups in (("topic", result.get("topics", [])), ("skipped", result.get("skipped", []))):
        for g in groups:
            for n in g.get("lines", []):
                if n not in rules:
                    problems.append(f"unknown line L{n} in {where}")
                elif n in seen:
                    problems.append(f"L{n} in both {seen[n]} and {g.get('key', where)}")
                seen[n] = g.get("key", where)
    problems += [f"L{n} not assigned" for n in rules if n not in seen]
    keys = [t.get("key") for t in result.get("topics", [])]
    problems += [f"duplicate key {k}" for k in set(keys) if keys.count(k) > 1]
    signatures = {}
    for t in result.get("topics", []):
        bad = [f for f in t.get("forms") or [] if f not in FORMS]
        if bad:
            problems.append(f"{t.get('key')}: unknown forms {bad}")
        if t.get("by_form"):
            for f in t.get("forms") or []:
                sig = (f, (t.get("trigger") or "").lower(), t.get("role"))
                if sig in signatures:
                    problems.append(f"{t.get('key')} and {signatures[sig]} share form {f}, trigger {sig[1] or 'none'}, "
                                    f"role {sig[2]}")
                signatures[sig] = t.get("key")
    for t in result.get("topics", []):
        levels = [rules[n][0] for n in t.get("lines", []) if n in rules]
        if levels and t.get("level") != min(levels, key=LEVEL_ORDER.index):
            problems.append(f"{t.get('key')}: level {t.get('level')}, earliest rule is {min(levels, key=LEVEL_ORDER.index)}")
    return problems


def main():
    sections = {s["number"]: s for s in json.loads((ROOT / "build" / "grammar" / "pcic_grammar.json").read_text())}
    out_dir = ROOT / "build" / "grammar" / "catalog"
    out_dir.mkdir(parents=True, exist_ok=True)
    for number in sys.argv[1:]:
        section = sections[number]
        prompt, rules = section_input(section)
        result, cost, cached = llm.complete(MODEL, SYSTEM, prompt, 16000)
        problems = check(result, rules)
        result.update({"section": number, "title": section["title"], "parent": section.get("parent"),
                       "rules": {str(n): {"level": lv, "text": t} for n, (lv, t) in rules.items()}, "problems": problems})
        (out_dir / f"{number}.json").write_text(json.dumps(result, ensure_ascii=False, indent=1))
        print(f"{number} {section['title']}: {len(rules)} rules -> {len(result.get('topics', []))} topics, "
              f"{sum(len(s['lines']) for s in result.get('skipped', []))} skipped, ${cost:.3f}{' (cached)' if cached else ''}")
        for p in problems:
            print("  problem:", p)


if __name__ == "__main__":
    main()
