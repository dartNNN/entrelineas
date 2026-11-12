"""Grammar topic candidates by form, without a model: the same text always gives the same result.

Every catalog topic (build/grammar/catalog/*.json) describes its form: "forms" (the verb group's forms), "trigger"
(the words that open the construction, or none), "role" (the key verb is in the trigger's clause, in the main clause
of such a clause, or in a trigger clause with no main clause) and "by_form" (whether that signature identifies it).
For each verb group of a sentence the detector finds the topics whose signature matches. A single match with by_form
is the topic; otherwise the matches are candidates for the model to choose from.

Rules learnt on chapter 1 and the PCIC examples:
- a trigger is one of the catalog's triggers found among the first words of a clause, whether the parser makes it
  a marker (si, aunque), an adverb (tal vez, a lo mejor) or an interjection (ojalá), and only there ("De haberlo
  sabido" yes, "antes de abrir" no);
- a form the analysers leave undecided (sigue: imperative or present) gives the candidates of every reading and is
  never decided by form;
- "si" heading a complement ("no sé si viene") opens an indirect question, not a condition;
- "como si" and "por si" are triggers of their own, never "como" or "si" alone;
- when the parser hangs a trigger on a non-finite verb (Si lo deseas puedo sacar…), the first finite verb after the
  trigger, before a comma, is the clause's verb.
"""
import json
import pathlib
import re

from config import ROOT

COMPLEMENT = {"ccomp", "csubj", "xcomp", "obj", "nsubj"}
FINITE = ("Ind.", "Sub.", "Cnd", "Imp")
MAX_TRIGGER_WORDS = 3


def trigger_variants(trigger):
    """Normalised alternatives of a catalog trigger: "por si (acaso)" -> ["por si"], "y / que" -> ["y", "que"],
    "(en) caso de" -> ["caso de"]; "que…, que…" and other discontinuous triggers give nothing (not detectable)."""
    if not trigger or "…" in trigger or "..." in trigger:
        return []
    out = []
    for part in re.split(r"\s*/\s*|\s*,\s*", trigger.lower()):
        part = " ".join(re.sub(r"\([^)]*\)", " ", part).split())
        if part:
            out.append(part)
    return out


def load_catalog(directory=None):
    topics = []
    for path in sorted(pathlib.Path(directory or ROOT / "build" / "grammar" / "catalog").glob("*.json")):
        data = json.loads(path.read_text())
        for t in data.get("topics", []):
            topics.append({**t, "section": data["section"], "triggers": trigger_variants(t.get("trigger"))})
    return topics


def _clause_start(head):
    """First token of a clause that is not punctuation or a coordinating conjunction."""
    return next((t for t in head.subtree if not t.is_punct and t.dep_ != "cc"), head)


def clause_triggers(a, head, known):
    """Catalog triggers among the first words of the clause headed by head: the longest one wins, so "como si"
    hides "como" and a marker inside the clause ("antes de abrir") is no trigger at all."""
    words = [x.lower_ for x in head.subtree if not x.is_punct and x.dep_ != "cc"][:MAX_TRIGGER_WORDS]
    found = set()
    for n in range(len(words), 0, -1):
        if " ".join(words[:n]) in known:
            found.add(" ".join(words[:n]))
            break
    if "si" in found and head.dep_ in COMPLEMENT:
        found.discard("si")  # indirect question
    return found


def clause_verb_form(a, head, has_trigger):
    form = a.clause_form(head)
    if not has_trigger or (form and form.startswith(FINITE)):
        return form
    for t in a.doc[_clause_start(head).i + 1:]:
        if t.text in (",", ";", ":", "—"):
            break
        if (a.forms.get(t.i) or "").startswith(FINITE):
            return a.clause_form(t)
    return form


def verb_groups(a):
    """Heads of verb groups: verbs that are not auxiliaries or copulas of another verb, and copular predicates."""
    for t in a.doc:
        if t.i in a.forms and t.dep_ not in ("aux", "aux:pass", "cop"):
            yield t
        elif t.i not in a.forms and any(c.dep_ == "cop" and c.i in a.forms for c in t.children):
            yield t


def signatures(a, head, known):
    """[(form, trigger or None, role or None)] under which a verb group can match topics."""
    own = clause_triggers(a, head, known)
    form = clause_verb_form(a, head, bool(own))
    if not form:
        return []
    out = [(form, None, None)]
    hangs_on_verb = head.dep_ == "advcl" and a.verb(head.head) is not None
    for trig in own:
        out.append((form, trig, "clause"))
        if not hangs_on_verb and head.dep_ not in COMPLEMENT:
            out.append((form, trig, "alone"))
    for child in head.children:
        if child.dep_ == "advcl":
            out += [(form, trig, "main") for trig in clause_triggers(a, child, known)]
    return out


def detect(a, topics):
    """[{"tokens": [head index], "form", "candidates": [keys], "topic": key if decided by form else None}] for each
    verb group with matching topics. A topic with a trigger is more specific than a bare tense topic of the same form,
    so when any triggered topic matches, the bare ones are dropped."""
    result = []
    known = {trig for t in topics for trig in t["triggers"]}
    for head in verb_groups(a):
        sigs = signatures(a, head, known)
        if not sigs:
            continue
        form = sigs[0][0]
        readings = set(form.split("|"))
        matches = []
        for t in topics:
            if not readings & set(t.get("forms") or []):
                continue
            if any((trig is None and not t["triggers"] and not t.get("role"))
                   or (trig is not None and trig in t["triggers"] and t.get("role") == role) for _, trig, role in sigs):
                matches.append(t)
        if not matches:
            continue
        triggered = [t for t in matches if t["triggers"]]
        chosen = triggered or matches
        candidates = sorted({t["key"] for t in chosen})
        decided = candidates[0] if len(candidates) == 1 and chosen[0].get("by_form") and len(readings) == 1 else None
        result.append({"tokens": [head.i], "form": form, "candidates": candidates, "topic": decided})
    return result
