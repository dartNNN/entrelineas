"""Verb forms from spaCy checked against LanguageTool's dictionary tagger, deterministically.

spaCy guesses a verb's mood and tense statistically and gets subjunctives and imperatives wrong where they matter
(chapter 1: 28 of 1593 verbs, e.g. observen, quepa, búscate). LanguageTool looks every form up in a morphological
dictionary and removes impossible readings by context rules. Rule: one LanguageTool verb reading wins; several keep
spaCy's choice, which on chapter 1 was always among them; no verb reading means the token is not a verb, and so does
a token spaCy does not take for a verb when LanguageTool has a non-verb reading for it too (como: conjunction or
"I eat").

Forms are "Ind.Pres", "Ind.Imp", "Ind.Past", "Ind.Fut", "Cnd", "Sub.Pres", "Sub.Imp", "Sub.Fut", "Imp" (imperative),
"Inf", "Ger", "Part", or the undecided readings joined by "|" ("Ind.Past|Ind.Pres"); compound tenses with haber add ".Perf" to the auxiliary's form ("Sub.Imp.Perf" is the
pluperfect subjunctive). Verb lemmas come from LanguageTool too: spaCy lemmatises auxiliaries badly (hubieras ->
"hubierar", íbamos -> "ír"), which broke compound tenses. LanguageTool runs from a local install (LT_HOME).
"""
import os
import pathlib
import re
import subprocess
import tempfile

LT_HOME = pathlib.Path(os.environ.get("LT_HOME", pathlib.Path(__file__).parent.parent / "tools" / "LanguageTool"))
MOODS = {"I": "Ind", "S": "Sub"}
TENSES = {"P": "Pres", "I": "Imp", "S": "Past", "F": "Fut"}
NON_FINITE = {"M": "Imp", "N": "Inf", "G": "Ger", "P": "Part"}


def eagles_form(tag):
    """Form of an EAGLES verb tag such as VMSI3S0, or None for other tags."""
    if not re.match(r"^V[MAS][ISMNGP]", tag):
        return None
    mood, tense = tag[2], tag[3]
    if mood == "I" and tense == "C":
        return "Cnd"
    if mood in MOODS:
        return f"{MOODS[mood]}.{TENSES.get(tense, tense)}"
    return NON_FINITE[mood]


def spacy_form(token):
    morph = token.morph.to_dict()
    form = morph.get("VerbForm")
    if form == "Fin":
        mood = morph.get("Mood")
        return mood if mood in ("Cnd", "Imp") else f"{mood}.{morph.get('Tense')}"
    return {"Inf": "Inf", "Ger": "Ger", "Part": "Part"}.get(form)


def languagetool_tokens(texts):
    """[(word, {verb forms}, has a non-verb reading, {verb lemmas})] for all texts in order, from LanguageTool.

    The tagger splits text into its own sentences and does not mark where an input line ends, so the result is one
    token stream; analyse aligns the texts against it in order.
    """
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.write("\n".join(t.replace("\n", " ") for t in texts) + "\n")
    out = subprocess.run(["java", "-jar", str(LT_HOME / "languagetool-commandline.jar"), "-l", "es", "-b", "--taggeronly",
                          f.name], capture_output=True, text=True, check=True).stdout
    os.unlink(f.name)
    tokens = []
    for line in out.splitlines():
        if line.startswith("<S>"):
            for word, readings in re.findall(r"(\S+?)\[((?:[^\[\]]|\[[^\]]*\])*)\]", line):
                pairs = [r.rsplit("/", 1) for r in readings.split(",") if "/" in r]
                forms = {f for f in (eagles_form(tag) for _, tag in pairs) if f}
                lemmas = {lemma for lemma, tag in pairs if eagles_form(tag)}
                other = any(eagles_form(tag) is None and not tag.startswith(("_", "SENT", "PARA")) for _, tag in pairs)
                tokens.append((word.replace("<S>", "").strip(), forms, other, lemmas))
    return tokens


def align(doc, lt_tokens, start=0):
    """({spaCy token index: (forms, has non-verb reading, lemmas)}, next stream position), walking both sequences."""
    out, j = {}, start
    for t in doc:
        for k in range(j, min(j + 6, len(lt_tokens))):
            if lt_tokens[k][0] == t.text:
                out[t.i], j = lt_tokens[k][1:], k + 1
                break
    return out, j


def verb_form(token, lt):
    """Combined form of one token, or None if it is not a verb; lt is (forms, has non-verb reading, lemmas) or None."""
    guess = spacy_form(token) if token.pos_ in ("VERB", "AUX") else None
    if lt is None:
        return guess
    lt_forms, other = lt[0], lt[1]
    if guess is None and other:
        return None
    if len(lt_forms) == 1:
        return next(iter(lt_forms))
    if not lt_forms:
        return None
    # several readings and spaCy's guess is none of them (aumentamos: present or preterite): undecided, not guessed
    return guess if guess in lt_forms else "|".join(sorted(lt_forms))


class Analysis:
    """A spaCy doc with checked verb forms and lemmas by token index."""

    def __init__(self, doc, forms, lemmas):
        self.doc, self.forms, self.lemmas = doc, forms, lemmas

    def lemma(self, token):
        return self.lemmas.get(token.i, token.lemma_)

    def verb(self, head):
        """The finite or non-finite verb of a clause head: the head itself, or its copula (estuviera ocupado)."""
        if head.i in self.forms:
            return head
        return next((c for c in head.children if c.dep_ == "cop" and c.i in self.forms), None)

    def clause_form(self, head):
        """Form of the verb group of a clause.

        A compound tense takes its haber auxiliary's form plus ".Perf"; a non-finite verb under a finite auxiliary
        (tengo que eliminar, puedo sacar) takes the auxiliary's form.
        """
        auxiliaries = [c for c in head.children if c.dep_ in ("aux", "aux:pass") and c.i in self.forms]
        for child in auxiliaries:
            if self.lemma(child) == "haber":
                return self.forms[child.i] + ".Perf"
        verb = self.verb(head)
        form = self.forms.get(verb.i) if verb is not None else None
        if form in ("Inf", "Ger", "Part"):
            finite = [c for c in auxiliaries if self.forms[c.i] not in ("Inf", "Ger", "Part")]
            if finite:
                return self.forms[finite[0].i]
        return form


def analyse(nlp, texts):
    """[Analysis] for each text: spaCy parse plus checked verb forms and lemmas."""
    lt, position, result = languagetool_tokens(texts), 0, []
    for text in texts:
        doc = nlp(text)
        aligned, position = align(doc, lt, position)
        forms = {t.i: f for t in doc if (f := verb_form(t, aligned.get(t.i))) is not None}
        lemmas = {i: next(iter(a[2])) for i, a in aligned.items() if i in forms and len(a[2]) == 1}
        result.append(Analysis(doc, forms, lemmas))
    return result
