"""Semantic units: short sentences and sentences without their own actor are glued to their neighbours.

A model that sees "Lo miró." alone cannot tell who looks at whom. Within a paragraph a sentence joins the previous
one when either is shorter than MIN_WORDS or when it names no actor of its own (no noun or name as subject, no
name at all; Spanish drops pronoun subjects). Short dialogue lines join across paragraph boundaries. A unit stays
within MAX_WORDS and its chapter. Units without an actor get a names hint: people named just before them.
"""
from text import Sentence

MIN_WORDS = 6
MAX_WORDS = 60
HINT_UNITS = 6  # how many previous units the names hint looks at
HINT_NAMES = 3
SPEECH = {"decir", "preguntar", "responder", "contestar", "añadir", "murmurar", "reponer", "comentar", "insistir",
          "exclamar", "susurrar", "replicar", "observar", "concluir", "apuntar", "protestar", "sonreír"}
DASHES = ("—", "-", "–", "«", '"')


def chapter_of(s: Sentence):
    return s.sid.split("p")[0]


def words(s: Sentence):
    return sum(1 for t in s.tokens if t.text.isalpha())


def names(s: Sentence):
    """Proper names in the sentence; consecutive name tokens form one name (Sancho Panza)."""
    found, current = [], []
    for t in s.tokens + [None]:
        if t is not None and t.pos == "PROPN":
            current.append(t.text)
        elif current:
            found.append(" ".join(current))
            current = []
    return found


def anchored(s: Sentence):
    """The sentence says who acts: a noun or name as subject, or a name anywhere."""
    return any(t.dep in ("nsubj", "nsubj:pass") and t.pos in ("NOUN", "PROPN") for t in s.tokens) or bool(names(s))


def dialogue(s: Sentence):
    return s.text.lstrip().startswith(DASHES)


def build(sentences, min_words=MIN_WORDS, max_words=MAX_WORDS):
    """Units as lists of sentences, in book order; every sentence is in exactly one unit."""
    units, current, size, prev = [], [], 0, None
    for s in sentences:
        n = words(s)
        same_chapter = prev is not None and chapter_of(s) == chapter_of(prev)
        same_paragraph = same_chapter and s.paragraph == prev.paragraph
        short = n < min_words or size < min_words
        join = bool(current) and same_chapter and size + n <= max_words and (
            (same_paragraph and (short or not anchored(s)))
            or (not same_paragraph and dialogue(s) and dialogue(prev) and short))
        if not join and current:
            units.append(current)
            current, size = [], 0
        current.append(s)
        size += n
        prev = s
    if current:
        units.append(current)
    return units


def names_hint(units, k):
    """People named just before unit k (same chapter), most recent first; speakers of a speech verb go first,
    since untagged dialogue lines usually belong to the last tagged speakers. Empty when the unit has its own actor."""
    if any(anchored(s) for s in units[k]):
        return []
    chapter = chapter_of(units[k][0])
    speakers, others = [], []
    for j in range(k - 1, max(-1, k - 1 - HINT_UNITS), -1):
        if chapter_of(units[j][0]) != chapter:
            break
        for s in reversed(units[j]):
            found = names(s)
            if any(t.lemma in SPEECH for t in s.tokens):
                speakers += [n for n in found if n not in speakers]
            others += [n for n in reversed(found) if n not in others]
    return (speakers + [n for n in others if n not in speakers])[:HINT_NAMES]
