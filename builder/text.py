"""FB2 import and linguistic preparation."""
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

NS = {"f": "http://www.gribuser.ru/xml/fictionbook/2.0"}

# Parts of speech that never get lexical entries; grammar notes cover them.
NO_LEXICAL_POS = {"DET", "ADP", "CCONJ", "SCONJ", "PUNCT", "NUM", "SPACE", "SYM", "PROPN", "AUX", "PRON", "X"}
CLITICS = re.compile(r"((?:me|te|se|nos|os|lo|la|le|los|las|les)+)$")


@dataclass
class Token:
    i: int
    text: str
    lemma: str
    pos: str
    morph: dict
    clitics: str = ""
    ws: str = " "
    dep: str = ""  # dependency relation, used to find the sentence's own subject


@dataclass
class Sentence:
    sid: str
    text: str
    tokens: list
    paragraph: int
    notes: list = field(default_factory=list)  # ids of the footnotes referenced in this sentence


@dataclass
class Chapter:
    index: int
    title: str
    paragraphs: list
    sentences: list = field(default_factory=list)
    # Footnotes referenced from the chapter: {"id", "text", "paragraph" (1-based), "offset" (character in that paragraph)}.
    footnotes: list = field(default_factory=list)


# Text lines of a section in document order: plain paragraphs, verse lines, quote authors (subtitles are headings,
# and converters put their credits there).
TEXT_TAGS = {"p", "v", "text-author"}
# Containers whose lines belong to the chapter text (epigraphs, quoted documents, poems).
BLOCK_TAGS = {"epigraph", "cite", "poem", "stanza"}
NOTE_MARK = "\ue000"  # stands for a footnote reference while whitespace is normalised


def _local(element):
    return element.tag.split("}")[-1]


def _note_target(anchor):
    return next((v for k, v in anchor.attrib.items() if k.endswith("href")), "").lstrip("#")


def _marked_text(element):
    """Element text with each footnote reference replaced by NOTE_MARK, and the referenced note ids in order."""
    parts, ids = [], []

    def walk(el):
        parts.append(el.text or "")
        for child in el:
            if _local(child) == "a" and child.get("type") == "note":
                ids.append(_note_target(child))
                parts.append(NOTE_MARK)
            else:
                walk(child)
            parts.append(child.tail or "")
    walk(element)
    return " ".join("".join(parts).split()).replace(" " + NOTE_MARK, NOTE_MARK), ids


def _strip_marks(line):
    """(text without marks, character offset of each mark)."""
    out, offsets = [], []
    for ch in line:
        if ch == NOTE_MARK:
            offsets.append(len(out))
        else:
            out.append(ch)
    text = "".join(out)
    if text.startswith(" "):
        text, offsets = text[1:], [max(0, o - 1) for o in offsets]
    return text, offsets


def _lines(element, nested=False):
    """(marked line, note ids) in document order; titles count only inside nested blocks (a poem title)."""
    for child in element:
        tag = _local(child)
        if tag in TEXT_TAGS:
            yield _marked_text(child)
        elif tag in BLOCK_TAGS or (tag == "title" and nested):
            yield from _lines(child, nested=True)


def _chapter_sections(container):
    """Chapter sections in order; a section that holds sections is a part, and its own chapters are the chapters."""
    for section in container.findall("f:section", NS):
        if section.find("f:section", NS) is not None:
            yield from _chapter_sections(section)
        else:
            yield section


def read_notes(root):
    """{note id: text} from the notes body."""
    notes = {}
    for body in root.findall("f:body", NS):
        if body.get("name") == "notes":
            for section in body.iter(f"{{{NS['f']}}}section"):
                if section.get("id"):
                    notes[section.get("id")] = " ".join(_strip_marks(line)[0] for line, _ in _lines(section) if line)
    return notes


def read_chapters(path):
    root = ET.parse(path).getroot()
    notes = read_notes(root)
    chapters = []
    for idx, section in enumerate(_chapter_sections(root.find("f:body", NS)), start=1):
        title = " ".join("".join(p.itertext()).strip() for p in section.findall("f:title/f:p", NS))
        chapter = Chapter(idx, title, [])
        for line, ids in _lines(section):
            text, offsets = _strip_marks(line)
            if not text:
                continue
            chapter.paragraphs.append(text)
            chapter.footnotes += [{"id": i, "text": notes.get(i, ""), "paragraph": len(chapter.paragraphs), "offset": o}
                                  for i, o in zip(ids, offsets)]
        chapters.append(chapter)
    return chapters


def normalise(text):
    """Repairs digitisation artefacts that would otherwise confuse segmentation."""
    text = text.replace("`", "'")
    text = re.sub(r":\?\"(.+?)\"\?", r': "¿\1?"', text)  # lost inverted question mark around quotes
    # Dialogue dashes are typed as hyphens glued to words ("-dijo", "-concluyó-."): make them real dashes.
    text = re.sub(r"(^|\s)-+(?=\S)", r"\1— ", text)
    text = re.sub(r"(?<=\S)-+(?=\s|$|[.,;:!?…])", " —", text)
    return text


def analyse(nlp, chapter):
    """Sentences of the chapter in order. The title is paragraph 0 (sids cNNp000sK), so it is annotated like text."""
    sentences = []
    numbered = ([(0, chapter.title)] if chapter.title.strip() else []) + list(enumerate(chapter.paragraphs, start=1))
    for p_idx, text in numbered:
        doc = nlp(normalise(text))
        # a footnote's offset in the normalised text: normalisation is local, so the normalised prefix has its length
        marks = [(len(normalise(text[:f["offset"]]).rstrip()), f["id"]) for f in chapter.footnotes if f["paragraph"] == p_idx]
        spans = [doc[:]] if p_idx == 0 else list(doc.sents)  # a title is one sentence ("II. Segundo" is not two)
        for s_idx, sent in enumerate(spans, start=1):
            tokens = []
            for i, t in enumerate(sent):
                morph = t.morph.to_dict()
                lemma = t.lemma_.split(" ")[0].lower()  # spaCy appends clitic pronouns to verb lemmas
                clitics = ""
                if t.pos_ == "VERB" and morph.get("PronType") == "Prs":
                    m = CLITICS.search(t.text.lower())
                    clitics = m.group(1) if m else ""
                tokens.append(Token(i, t.text, lemma, t.pos_, morph, clitics, t.whitespace_, t.dep_))
            last = s_idx == len(spans)
            notes = [i for o, i in marks if sent.start_char <= o < sent.end_char or (last and o >= sent.end_char)]
            sentences.append(Sentence(f"c{chapter.index:02d}p{p_idx:03d}s{s_idx}", sent.text, tokens, p_idx, notes))
    chapter.sentences = sentences
    return sentences


def lexical_occurrences(sentences, max_occurrence):
    """Groups content-word occurrences by (lemma, pos), numbering them in book order."""
    groups = {}
    for sent in sentences:
        for t in sent.tokens:
            if (t.pos in NO_LEXICAL_POS or not re.search(r"[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]", t.text)
                    or len(t.text.strip(".")) < 2):
                continue
            key = f"{t.lemma}|{t.pos}"
            occ = groups.setdefault(key, [])
            occ.append({"oid": f"{sent.sid}t{t.i}", "sid": sent.sid, "token": t.i, "ordinal": len(occ) + 1})
    return {k: v[:max_occurrence] for k, v in groups.items()}


TENSE = {"Pres": "наст. вр.", "Imp": "имперфект", "Past": "претерит", "Fut": "буд. вр."}
PERSON = {"1": "1 л.", "2": "2 л.", "3": "3 л."}
NUMBER = {"Sing": "ед. ч.", "Plur": "мн. ч."}


def form_note(token, lemma=None):
    """Template description of an inflected form; empty when the form equals the lemma."""
    m = token.morph
    surface = token.text.lower()
    lemma = lemma or token.lemma
    if surface == lemma:
        return ""
    parts = []
    if token.pos == "VERB":
        form = m.get("VerbForm")
        if form == "Fin":
            mood = m.get("Mood")
            if mood == "Sub":
                parts.append("субхунтив " + TENSE.get(m.get("Tense", ""), "").strip())
            elif mood == "Cnd":
                parts.append("кондиционал")
            elif mood == "Imp":
                parts.append("императив")
            else:
                parts.append(TENSE.get(m.get("Tense", ""), ""))
            parts.append(" ".join(x for x in (PERSON.get(m.get("Person", "")), NUMBER.get(m.get("Number", ""))) if x))
        elif form == "Ger":
            parts.append("герундий")
        elif form == "Part":
            parts.append("причастие")
            if m.get("Gender") == "Fem" or m.get("Number") == "Plur":
                parts.append(" ".join(x for x in ("ж. р." if m.get("Gender") == "Fem" else "", NUMBER.get(m.get("Number"), "") if m.get("Number") == "Plur" else "") if x))
        elif form == "Inf":
            parts.append("инфинитив")
        note = ", ".join(p for p in parts if p)
        if token.clitics:
            note = f"{note} + {token.clitics}" if note else f"+ {token.clitics}"
        return f"{note} от {lemma}" if note else ""
    if token.pos in ("NOUN", "ADJ"):
        if m.get("Gender") == "Fem" and token.pos == "ADJ":
            parts.append("ж. р.")
        if m.get("Number") == "Plur":
            parts.append("мн. ч.")
        return f"{', '.join(parts)} от {lemma}" if parts else ""
    return ""
