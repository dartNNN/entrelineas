"""Prompts of the two model passes: the segment pass (strong model, chunks of a chapter with the published translation)
and the lexical pass (cheap model, a word with its dictionary entry and occurrences), and packing them into calls."""
import hashlib


SEGMENT_SYSTEM = """You prepare a Spanish novel for a Russian-speaking learner using Ilya Frank's reading method.
The original text is never changed; you return annotations that refer to words by index.

Input: numbered sentences, each line "[sid] 0 word|1 word|...". Context lines (previous paragraph) are not annotated.
"AUTHOR NOTE:" lines after a sentence are the author's footnotes to it: use them for context notes, never annotate them.
"REFERENCE" is the published Russian translation of roughly this passage (it may start earlier or end later).
Treat it as the trusted statement of meaning: never contradict it, reuse its names and terms, but keep literal
translations close to the Spanish wording.

For every sentence return:
- "s": sense-group segments covering ALL word indices in order, no gaps, no overlaps, punctuation included.
  Segments are 2-8 words and never split a verbal periphrasis (iba a cambiar), a preposition from its noun,
  or a fixed expression. Each segment: [first, last, literal, literary]. literal = word-close Russian that keeps
  the Spanish structure visible; literary = idiomatic Russian ONLY where the literal one is clumsy, else null.
- "g": grammar notes [first, last, topic_key, note] for constructions an intermediate learner may not see through:
  tenses and moods, subjunctive, clitics, se, periphrasis, agreement, word order, indirect questions, prepositional
  government. topic_key is short English snake_case. note in Russian, max 25 words. Skip the obvious.
- "c": context notes [first, last, note] in Russian for realia, art and chess terms, allusions, archaic or regional use.
- "v": variants [first, last, [options], note] only where Russian renderings differ in nuance.
- "e": fixed multiword expressions [first, last, dictionary_form, russian_gloss], e.g. al mismo tiempo.
Never mention tagging, indices or digitisation errors in notes. Ranges are inclusive.
Return ONLY JSON: {"<sid>": {"s": [...], "g": [...], "c": [...], "v": [...], "e": [...]}, ...}"""


# Same task when the translation is aligned sentence by sentence (build/chNN/alignment.json).
_REFERENCE_EXCERPT = """"REFERENCE" is the published Russian translation of roughly this passage (it may start earlier or end later).
Treat it as"""


SEGMENT_SYSTEM_ALIGNED = SEGMENT_SYSTEM.replace(_REFERENCE_EXCERPT, """"RU:" lines give the published Russian translation of the sentences above them, back to the previous RU line
("RU: —": the translator left them out); "NOTE:" lines after it are the translator's footnotes to it. Treat it as""").replace(
    """- "e": fixed multiword expressions""", """- "r", only for sentences covered by an "RU?:" line (a translation the alignment is unsure of; otherwise the
  same as "RU:"): how it renders this sentence: "close", "free" (the meaning is kept but the wording departs freely,
  or the translator added or dropped details), or "none" (it does not render this sentence at all).
- "e": fixed multiword expressions""").replace('"v": [...], "e": [...]}', '"v": [...], "e": [...], "r": "close"}')


assert SEGMENT_SYSTEM_ALIGNED.count('"r"') == 2 and "NOTE:" in SEGMENT_SYSTEM_ALIGNED


LEXICAL_SYSTEM = """You write a learner's dictionary for one Spanish novel, for Russian speakers.
Input: lemma groups "## lemma|POS" with numbered occurrences from the whole book; a group of a very frequent word may
come in parts, "## lemma|POS @2". An occurrence is a short passage with the target word in [brackets]; after "=>"
comes the Russian translation of the phrase containing the word in this place, after "| RU:" the published Russian
translation of its sentence ("RU: —": the translator left it out), and "(в сцене: …)" names people mentioned just
before the passage, a hint that may be wrong. Use them to pick the sense: the gloss of the chosen sense must match how
the word is translated there. Lines "D<id>: ..." under a group list the senses of the word in Lingvo's Large
Spanish-Russian dictionary (the headword in "DICT <headword>" may be the base form of the given lemma), with "e.g."
examples that occur in these passages; "F<n>" lines are the entry's idioms and "P<n>" lines phrase entries found in
the passages.
For each group return the senses ACTUALLY used in these occurrences and which sense each occurrence uses.
Rules:
- "fix": the correct dictionary lemma if the given one is wrong (e.g. a clitic glued to it, or a non-Spanish word), else null.
- each sense: {"id": "1", "gloss": Russian gloss in Frank's style: basic meaning first if it differs, then the meaning
  used here; 2-8 words; dictionary form (nominative, infinitive), no trailing period, lowercase}, "gram": short grammar label or null (noun gender "м."/"ж.",
  verb government like "acordarse de", "прил.", etc.)
- each sense also gets "dict": the id after D (e.g. "2", "1.3", "F1", "P1") of the line it corresponds to, or null when
  none fits or there are none.
  Prefer the dictionary's meaning and wording when one fits; still keep the gloss short.
- "occ": list of sense ids, one per numbered occurrence, in the given order.
- Latin or other foreign words: one sense with the Russian meaning and gram "лат." etc.
- Single letters, abbreviations and fragments of names (e.g. "X" in "rayos X"): return {"skip": true}.
Return ONLY JSON keyed by the group header as given (with " @2" for a part):
{"lemma|POS": {"fix": null, "senses": [{"id": "1", "gloss": "...", "gram": null, "dict": "2"}], "occ": ["1", "1"]}, ...}"""


def chunk_sentences(chapter, words_per_chunk=1000):
    """Groups whole paragraphs into chunks of about N words."""
    chunks, current, count = [], [], 0
    by_par = {}
    for s in chapter.sentences:
        by_par.setdefault(s.paragraph, []).append(s)
    for p in sorted(by_par):
        sents = by_par[p]
        n = sum(len(s.tokens) for s in sents)
        if current and count + n > words_per_chunk:
            chunks.append(current)
            current, count = [], 0
        current.extend(sents)
        count += n
    if current:
        chunks.append(current)
    return chunks


def reference_excerpt(chapter, ref_paragraphs, chunk, margin=0.06):
    """Interim alignment: the proportional slice of the reference chapter, with a margin."""
    total = sum(len(s.tokens) for s in chapter.sentences)
    before = 0
    for s in chapter.sentences:
        if s.sid == chunk[0].sid:
            break
        before += len(s.tokens)
    size = sum(len(s.tokens) for s in chunk)
    start, end = max(0.0, before / total - margin), min(1.0, (before + size) / total + margin)
    words = [len(p.split()) for p in ref_paragraphs]
    ref_total, acc, picked = sum(words), 0, []
    for p, w in zip(ref_paragraphs, words):
        mid = (acc + w / 2) / ref_total
        if start <= mid <= end:
            picked.append(p)
        acc += w
    return "\n".join(picked)


def _source_line(s, source_footnotes):
    lines = [f"[{s.sid}] " + "|".join(f"{t.i} {t.text}" for t in s.tokens)]
    return lines + [f"AUTHOR NOTE: {source_footnotes[i]}" for i in s.notes if (source_footnotes or {}).get(i)]


def segment_prompt(chunk, previous_paragraph, reference, source_footnotes=None):
    lines = []
    if previous_paragraph:
        lines.append("CONTEXT: " + previous_paragraph)
    for s in chunk:
        lines += _source_line(s, source_footnotes)
    lines.append("REFERENCE:\n" + reference)
    return "\n".join(lines)


def aligned_segment_prompt(chunk, previous_paragraph, references, ref_of, footnotes=None, source_footnotes=None):
    """Like segment_prompt, with the published translation (and its footnotes) after the last sentence of each group."""
    lines = []
    if previous_paragraph:
        lines.append("CONTEXT: " + previous_paragraph)
    for k, s in enumerate(chunk):
        lines += _source_line(s, source_footnotes)
        if s.sid not in ref_of:  # the title: no published translation of its own
            continue
        if k + 1 == len(chunk) or ref_of.get(chunk[k + 1].sid) != ref_of[s.sid]:
            ref = references[ref_of[s.sid]]
            lines.append(("RU?: " if ref.get("check") else "RU: ") + (ref["text"] or "—"))
            lines += [f"NOTE: {(footnotes or {})[i]}" for i in ref.get("notes", []) if (footnotes or {}).get(i)]
    return "\n".join(lines)


def passage(unit, sid, token, literal="", reference=None, hint=()):
    """One occurrence for the lexical pass: its semantic unit with the word in brackets, then the segment literal,
    the published translation of its sentence (None: no alignment; "": the translator left it out) and names hint."""
    parts = []
    for s in unit:
        if s.sid == sid:
            parts.append("".join((f"[{t.text}]" if t.i == token else t.text) + t.ws for t in s.tokens).strip())
        else:
            parts.append("".join(t.text + t.ws for t in s.tokens).strip())
    line = " ".join(parts)
    if literal:
        line += f" => {literal}"
    if reference is not None:
        line += f" | RU: {reference or '—'}"
    if hint:
        line += f" (в сцене: {', '.join(hint)})"
    return line


def lexical_block(header, passages, dictionary_entry=None):
    """A lemma group: header, the dictionary inventory (headword, senses) when there is one, numbered occurrences."""
    lines = [f"## {header}"]
    headword, senses = dictionary_entry or (None, [])
    if senses:
        lines.append(f"DICT {headword}")
        for s in senses:
            lines.append(f"D{s['id']}: {s['text']}" + (f" ({s['gram']})" if s.get("gram") else ""))
            lines += [f"  e.g. {e}" for e in s.get("examples", [])]
    lines += [f"{n}. {p}" for n, p in enumerate(passages, start=1)]
    return "\n".join(lines)


def pack_calls(blocks, max_chars, anchor_every=16):
    """Groups blocks into calls of at most max_chars (a bigger block is a call of its own).

    Blocks go in header order, and a call also ends before an anchor block, one whose header hashes to 0 modulo
    anchor_every. A changed block then moves call boundaries only up to the next anchor, so the other calls keep
    their cache entries; without anchors one changed block would shift every later call."""
    def anchor(block):
        return int(hashlib.md5(block.split("\n", 1)[0].encode()).hexdigest(), 16) % anchor_every == 0
    calls, current, size = [], [], 0
    for b in sorted(blocks):
        if current and (size + len(b) > max_chars or anchor(b)):
            calls.append(current)
            current, size = [], 0
        current.append(b)
        size += len(b)
    if current:
        calls.append(current)
    return calls
