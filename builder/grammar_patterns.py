"""Grammar patterns induced from the PCIC examples: sentences reduced to their grammatical skeleton.

A sentence becomes a set of facts over its dependency tree with the content words taken out: function words keep
their spelling (si, que, de, como, se, no…), grammatical verbs keep their lemma and form (haber:Sub.Imp, ir:Ind.Imp),
other verbs keep only their form (V:Cnd), clitic pronouns become CL; nouns, adjectives, names and the like are
dropped and their kept dependents attach to the nearest kept ancestor. Facts are the node labels and the
(head, relation, dependent) triples between kept nodes, each also in a coarse version (mood without tense) so that
near forms share part of their weight.

A PCIC example is a pattern. A sentence matches it by the weighted share of the pattern's facts it contains; facts
are weighted by rarity (inverse document frequency over a corpus), so "nsubj under a verb" weighs next to nothing
and "si marking a pluperfect subjunctive" decides. Deterministic: same parser, same versions, same result.
"""
import collections
import math

KEEP_POS = {"ADP", "SCONJ", "CCONJ", "INTJ", "PART"}
KEEP_ADV = {"no", "ya", "todavía", "aún", "tan", "tanto", "más", "menos", "como", "cuando", "donde", "mientras",
            "apenas", "nunca", "siempre", "tal", "vez", "quizá", "quizás", "acaso", "ojalá", "así", "incluso", "ni",
            "sí", "solo", "sólo", "casi", "aun", "luego", "entonces", "antes", "después", "bien", "mal"}
# auxiliaries and the verbs of periphrases; modal and common verbs (poder, querer, hacer) would only add word overlap
GRAMMAR_VERBS = {"haber", "ser", "estar", "ir", "tener", "soler", "acabar", "volver", "dejar", "deber", "empezar",
                 "comenzar", "llevar", "seguir", "andar", "venir", "ponerse", "echar"}
CLITICS = {"me", "te", "lo", "la", "le", "nos", "os", "los", "las", "les"}
RELATIVES = {"que", "quien", "quienes", "cual", "cuales", "cuyo", "cuya", "cuyos", "cuyas", "donde", "cuanto",
             "cuanta", "cuantos", "cuantas", "qué", "quién", "cuál", "cómo", "dónde", "cuándo"}


def label(a, t):
    """Label of a kept token, or None for a content word."""
    word = t.lower_
    form = a.forms.get(t.i)
    if form:
        lemma = a.lemma(t)
        return f"{lemma}:{form}" if lemma in GRAMMAR_VERBS else f"V:{form}"
    if word == "se":
        return "se"
    if t.pos_ == "PRON" and word in CLITICS:
        return "CL"
    if t.pos_ == "PRON" and word in RELATIVES:
        return word
    if t.pos_ in KEEP_POS or (t.pos_ == "ADV" and word in KEEP_ADV):
        return word
    return None


def coarse(lab):
    """Mood-level version of a verb label: V:Sub.Imp.Perf -> V:Sub, haber:Cnd -> haber:Cnd."""
    if ":" in lab:
        head, form = lab.split(":", 1)
        return f"{head}:{form.split('.')[0]}"
    return lab


def facts(a):
    """The set of skeleton facts of an analysed sentence."""
    labels = {t.i: lab for t in a.doc if (lab := label(a, t))}
    out = set()
    for i, lab in labels.items():
        out |= {("node", lab), ("node~", coarse(lab))}
        t, dep = a.doc[i], a.doc[i].dep_
        head = t.head
        while head.i != head.head.i and head.i not in labels:  # climb over content words
            head = head.head
        if head.i in labels and head.i != i:
            h = labels[head.i]
            out |= {("edge", h, dep, lab), ("edge~", coarse(h), dep, coarse(lab))}
    return out


class Weights:
    """Inverse document frequency of facts over a corpus of fact sets; unseen facts weigh as the rarest."""

    def __init__(self, corpus):
        self.n = len(corpus)
        self.df = collections.Counter(f for s in corpus for f in s)

    def __call__(self, fact):
        return math.log((self.n + 1) / (self.df.get(fact, 0) + 1))


def containment(pattern, sentence, weight):
    """Weighted share of the pattern's facts found in the sentence, 0..1."""
    total = sum(weight(f) for f in pattern)
    return sum(weight(f) for f in pattern if f in sentence) / total if total else 0.0


def jaccard(x, y, weight):
    """Weighted Jaccard similarity of two fact sets, 0..1."""
    union = sum(weight(f) for f in x | y)
    return sum(weight(f) for f in x & y) / union if union else 0.0


def cosine(x, y, weight):
    """Weighted overlap normalised by both sizes, 0..1."""
    wx, wy = sum(weight(f) ** 2 for f in x), sum(weight(f) ** 2 for f in y)
    return sum(weight(f) ** 2 for f in x & y) / math.sqrt(wx * wy) if wx and wy else 0.0
