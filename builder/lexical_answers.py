"""Answers of the lexical pass: dictionary sense ids, the model's lemma fixes, merging the answers to a split group."""
import re


def normalize_dict_ids(entry):
    """The model sometimes copies the line prefix into "dict" ("D2", "DF1" for "2", "F1"); strips it in place."""
    for s in entry.get("senses", []) if isinstance(entry, dict) else []:
        if isinstance(s, dict) and s.get("dict") is not None:
            s["dict"] = re.sub(r"^D(?=[\dFP])", "", str(s["dict"]).strip())
    return entry


def check_dictionary_senses(entry, senses):
    """Sense ids of the model's answer checked against the dictionary entry: (from dictionary, outside it, invalid)."""
    ids = {s["id"] for s in senses}
    used = [str(s.get("dict")) if s.get("dict") is not None else None for s in entry.get("senses", [])]
    return sum(1 for u in used if u in ids), sum(1 for u in used if u is None), sum(1 for u in used if u and u not in ids)


def apply_fix(key, fix, keys):
    """Applies the lexical model's "fix" to a "lemma|POS" group key.

    The model returns either a lemma or "lemma|POS" when the tagger got the part of speech wrong too.
    Returns (key, lemma, pos_changed, sense_prefix). When another group already owns the new key, both groups keep
    the key and the moved group's sense ids get its old lemma as prefix ("lista.1"), because the two groups number
    their senses independently.
    """
    lemma, pos = key.split("|")
    if not fix:
        return key, None, False, ""
    new_lemma, _, new_pos = fix.strip().partition("|")
    new_lemma = new_lemma.strip().lower() or lemma
    new_pos = new_pos.strip().upper() or pos
    new_key = f"{new_lemma}|{new_pos}"
    prefix = f"{lemma}." if new_key != key and new_key in keys else ""
    return new_key, new_lemma, new_pos != pos, prefix


def merge_parts(parts):
    """One entry from the answers to the parts of a split group, in part order: senses with the same dictionary id,
    or without one and with the same gloss, are one sense; ids are renumbered from 1."""
    parts = [p for p in parts if isinstance(p, dict)]
    if not parts:
        return None
    if len(parts) == 1:
        return parts[0]
    if all(p.get("skip") for p in parts):
        return {"skip": True}
    senses, by_identity, occ = [], {}, []
    for p in parts:
        local = {}
        for s in p.get("senses", []):
            if not isinstance(s, dict):
                continue
            identity = ("dict", str(s["dict"])) if s.get("dict") is not None else ("gloss", s.get("gloss"))
            if identity not in by_identity:
                by_identity[identity] = str(len(senses) + 1)
                senses.append({**s, "id": by_identity[identity]})
            local[str(s.get("id"))] = by_identity[identity]
        occ += [local.get(str(o)) for o in (p.get("occ") or [])]
    fix = next((p.get("fix") for p in parts if p.get("fix")), None)
    return {"fix": fix, "senses": senses, "occ": occ}
