"""Bilingual StarDict dictionaries (Lingvo XDXF markup) as sense inventories for the lexical pass.

An entry is parsed into numbered senses ({"id": "2" or "1.3" (part.sense), "gram": "vt", "text": "..."}), the usage
examples of each sense, the idioms after the "••" mark, and cross-references to phrase entries. Every sense goes to
the model; examples, idioms and phrases only when their words occur in the sentences being annotated, so idioms like
"echar de menos" are shown where the text has them and the rest of a 50-sense entry's examples are not.
"""
import gzip
import html
import pathlib
import re
import struct


def load_stardict(base):
    """{lowercased headword: [raw entry, ...]} from base.ifo/.idx(.gz)/.dict(.dz)."""
    base = pathlib.Path(base)
    idx_path = base.with_suffix(".idx.gz") if base.with_suffix(".idx.gz").exists() else base.with_suffix(".idx")
    dict_path = base.with_suffix(".dict.dz") if base.with_suffix(".dict.dz").exists() else base.with_suffix(".dict")
    idx = gzip.open(idx_path).read() if idx_path.suffix == ".gz" else idx_path.read_bytes()
    data = gzip.open(dict_path).read() if dict_path.suffix == ".dz" else dict_path.read_bytes()
    entries, i = {}, 0
    while i < len(idx):
        end = idx.index(b"\0", i)
        word = idx[i:end].decode("utf-8", "replace")
        offset, size = struct.unpack(">II", idx[end + 1:end + 9])
        entries.setdefault(word.lower(), []).append(data[offset:offset + size].decode("utf-8", "replace"))
        i = end + 9
    return entries


POS_LABEL = re.compile(r"\s*<abr>(сущ|гл|прил|нареч|мест|предл|союз|межд|числ|частица|артикль)\.?</abr>")


def _plain(fragment):
    text = re.sub(r"<nu\s*/>'<nu\s*/>", "", fragment)  # stress marks
    text = re.sub(r"<[^>]+>", "", text)
    return " ".join(html.unescape(text).split())


def parse_entry(entry):
    """{"senses": [{"id", "gram", "text", "examples": [(es, ru)]}], "idioms": [(es, ru)], "refs": [phrase headword]}."""
    senses, idioms, refs, part, gram, in_idioms = [], [], [], None, None, False
    transcription, synonyms, domain = "", False, None
    for line in entry.splitlines():
        if "<k>" in line:
            continue
        # English entries put every line, the part labels "<b>2.</b> <abr>гл.</abr>" too, inside <blockquote>s.
        source, line = line, re.sub(r"^(\s*<blockquote>)+", "", line)
        depth = source.count("<blockquote>") - line.count("<blockquote>")
        if source != line:
            line = re.sub(r"<abr>\s*</abr>", "", line)  # "<b>1.<abr> </abr></b>": an empty tag inside a part label
        # "Syn:" / "Ant:" and the line after it name other words, not phrase entries of this one.
        if re.search(r"<b>(Syn|Ant):</b>", line):
            synonyms = "<kref>" not in line
            continue
        if synonyms:
            synonyms = False
            if "<kref>" in line and "<dtrn>" not in line:
                continue
        if "<tr>" in line and "<dtrn>" not in line:  # English entries: transcription and sound files, before the senses
            transcription = " ".join(f"[{_plain(t)}]" for t in re.findall(r"<tr>(.*?)</tr>", line))
            continue
        if transcription and POS_LABEL.match(line) and "<dtrn>" not in line:
            gram = f"{transcription} {_plain(line)}"  # "гл.; прош. вр. sought": a label, though it holds a kref
            continue
        if line.strip() == "••":  # the entry's idioms follow; they belong to no sense
            in_idioms = True
            continue
        # In English entries a part label may hold a reference ("<b>1.</b> гл.; прош. вр. <kref>ran</kref>"): it is a
        # label, read below. (Spanish entries, without <blockquote>s, keep their references there.)
        label = source != line and re.match(r"\s*<b>\d+\.</b>", line)
        if "<kref>" in line and "<dtrn>" not in line and not label:
            refs += [_plain(r) for r in re.findall(r"<kref>(.*?)</kref>", line)]
            continue
        if "<ex>" in line:
            es, _, ru = _plain(line).partition(" — ")
            if ru:
                (idioms if in_idioms or not senses else senses[-1]["examples"]).append((es, ru))
            continue
        m = re.match(r"\s*<b>(\d+)\.</b>\s*(.*)", line)
        if m:
            part, rest = m.group(1), m.group(2)
            gram = _plain(rest) or None
            if gram and transcription:
                gram = f"{transcription} {gram}"
            if "<dtrn>" not in rest:
                continue
            line = rest
        if "<dtrn>" not in line:
            if "<abr>" in line and not senses:
                gram = (f"{transcription} " if transcription else "") + _plain(line) if _plain(line) else gram  # part-of-speech label on its own line
            continue
        body = re.search(r"<dtrn>(.*)</dtrn>", line)
        text = _plain(body.group(1) if body else line)
        if not text:
            continue
        # English "1) <abr><dtrn>фин.</dtrn></abr>": a field label of the lettered senses under it, not a sense.
        if source != line and re.search(r"<abr>\s*<dtrn>.*</dtrn>\s*</abr>", line):
            domain = (depth, text)
            continue
        if domain and depth > domain[0]:
            text = f"{domain[1]} {text}"
        elif domain:
            domain = None
        n = re.match(r"\s*(\d+)\)", source)  # English "1) а) б)" nest letters under numbers: counted in order instead
        number = n.group(1) if n else str(sum(1 for s in senses if s["part"] == part) + 1)
        # English one-sense entries label the sense on its own line: "<abr>сущ.</abr>; горн. <dtrn>обогащение</dtrn>".
        inline = _plain(line.split("<dtrn>")[0]) if source != line and "<abr>" in line.split("<dtrn>")[0] else ""
        inline = re.sub(r"^(\d+|[а-я])\)\s*", "", inline)  # without the sense number "5)" or letter "а)"
        sense_gram = gram or ((f"{transcription} " if transcription else "") + inline if inline else None)
        if inline and gram:  # "5) <abr>бот.</abr> <dtrn>стелющийся побег</dtrn>": the field label goes with the sense
            text = f"{inline} {text}"
        senses.append({"id": f"{part}.{number}" if part else number, "part": part, "gram": sense_gram, "text": text,
                       "examples": []})
    for s in senses:
        del s["part"]
        if transcription and s["gram"]:  # English labels: "сущ. ; = indorsement" -> "сущ.; = indorsement"
            s["gram"] = re.sub(r"\s+([;,])", r"\1", s["gram"])
    return {"senses": senses, "idioms": idioms, "refs": refs}


def _words(text):
    return re.findall(r"\w+", text.lower())


def occurs_in(phrase, headword, sentence_words):
    """True when every content word of a phrase, other than the headword itself, occurs in the sentences.

    Words are compared by their first four letters so that inflected forms match (echó de menos ~ echar de menos).
    """
    stem = headword[:4]
    content = [w for w in _words(phrase) if len(w) > 2 and not w.startswith(stem)]
    prefixes = {w[:4] for w in sentence_words}
    return bool(content) and all(w[:4] in prefixes for w in content)


def candidate_headwords(lemma):
    """The lemma, then dictionary forms it may come from: -mente adverbs, participles, superlatives, reflexives."""
    out = [lemma]
    if lemma.endswith("mente") and len(lemma) > 7:
        base = lemma[:-5]
        out += [base, base[:-1] + "o"] if base.endswith("a") else [base]
    m = re.match(r"(.+?)ísim([oa]s?)$", lemma)
    if m:
        out.append(m.group(1) + "o")
        lemma = m.group(1) + "o"
    m = re.match(r"(.+)(ad|id|íd)[oa]s?$", lemma)
    if m:
        stem = m.group(1)
        out += [stem + "ar"] if m.group(2) == "ad" else [stem + "er", stem + "ir", stem + "ír"]
    if lemma.endswith("se") and len(lemma) > 4:
        out.append(lemma[:-2])
    elif re.search(r"[aeií]r$", lemma):
        out.append(lemma + "se")  # pronominal-only verbs: antojarse
    return list(dict.fromkeys(out))


class Dictionaries:
    """Ordered list of StarDict dictionaries; the first one that has a headword wins."""

    def __init__(self, bases):
        self.books = [load_stardict(b) for b in bases if pathlib.Path(b).with_suffix(".ifo").exists()]

    def __bool__(self):
        return bool(self.books)

    def entry(self, word):
        for book in self.books:
            for raw in book.get(word.lower(), []):
                parsed = parse_entry(raw)
                if parsed["senses"]:
                    return parsed
        return None

    def lookup(self, lemma):
        """(headword, parsed entry) for a lemma, or (None, None)."""
        for word in candidate_headwords(lemma.lower()):
            parsed = self.entry(word)
            if parsed:
                return word, parsed
        return None, None

    def inventory(self, lemma, sentences):
        """(headword, senses for the prompt) of a lemma as used in the given sentences.

        Every sense of the entry, each with the examples whose words occur in the sentences; then the entry's idioms
        (ids "F1", ...) and the phrase entries it refers to (ids "P1", ...) whose words occur too.
        """
        headword, parsed = self.lookup(lemma)
        if not headword:
            return None, []
        words = [w for s in sentences for w in _words(s)]
        out = [{"id": s["id"], "gram": s["gram"], "text": s["text"],
                "examples": [f"{es} — {ru}" for es, ru in s["examples"] if occurs_in(es, headword, words)]}
               for s in parsed["senses"]]
        idioms = [f"{es} — {ru}" for es, ru in parsed["idioms"] if occurs_in(es, headword, words)]
        out += [{"id": f"F{k}", "gram": None, "text": text, "examples": []} for k, text in enumerate(idioms, start=1)]
        for phrase in parsed["refs"]:
            if occurs_in(phrase, headword, words) and (sub := self.entry(phrase)):
                out.append({"id": f"P{sum(1 for s in out if s['id'].startswith('P')) + 1}", "gram": None,
                            "text": f"{phrase}: " + "; ".join(s["text"] for s in sub["senses"]), "examples": []})
        return headword, out
