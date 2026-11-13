"""Unit tests for deterministic builder logic (no network, no spaCy model needed)."""
import json
import re

import pytest

import align
import chapter_build
import dictionary
import export_package
import lexical_answers
import lexicon_build
import model_prompts
import published
import segment_answers
import text
import units
from text import Sentence, Token


def tok(i, t, lemma=None, pos="NOUN", morph=None, clitics="", ws=" ", dep=""):
    return Token(i, t, lemma or t.lower(), pos, morph or {}, clitics, ws, dep)


def test_normalise_dialogue_dashes_and_lost_question_marks():
    assert text.normalise("-Bien parece -respondió don Quijote-. Mira") == "— Bien parece — respondió don Quijote —. Mira"
    assert text.normalise("franco-español") == "franco-español"
    assert text.normalise('frase:?"Quién mató al caballero"?') == 'frase: "¿Quién mató al caballero?"'


def test_check_segments_repairs_off_by_one_and_punctuation_tail():
    s = Sentence("s", "Hola mundo .", [tok(0, "Hola"), tok(1, "mundo"), tok(2, ".", pos="PUNCT")], 1)
    over = {"s": [[0, 0, "привет", None], [1, 3, "мир.", None]]}
    assert segment_answers.check_segments(s, over) == [] and over["s"][-1][1] == 2
    short = {"s": [[0, 0, "привет", None], [1, 1, "мир", None]]}
    assert segment_answers.check_segments(s, short) == [] and short["s"][-1][1] == 2
    gap = {"s": [[0, 0, "привет", None], [2, 2, ".", None]]}
    assert segment_answers.check_segments(s, gap)


def test_form_note_templates():
    verb = {"Mood": "Ind", "Tense": "Imp", "Person": "3", "Number": "Sing", "VerbForm": "Fin"}
    assert text.form_note(tok(0, "sopesaba", "sopesar", "VERB", verb)) == "имперфект, 3 л. ед. ч. от sopesar"
    assert text.form_note(tok(0, "mirarlas", "mirar", "VERB", {"VerbForm": "Inf"}, "las")) == "инфинитив + las от mirar"
    assert text.form_note(tok(0, "copias", "copia", "NOUN", {"Number": "Plur"})) == "мн. ч. от copia"
    assert text.form_note(tok(0, "larga", "largo", "ADJ", {"Gender": "Fem", "Number": "Sing"})) == "ж. р. от largo"
    assert text.form_note(tok(0, "mesa", "mesa", "NOUN", {"Number": "Sing"})) == ""
    assert text.form_note(tok(0, "repintes", "repint", "NOUN", {"Number": "Plur"}), "repinte") == "мн. ч. от repinte"


def test_lexical_occurrences_caps_and_skips_function_words_and_letters():
    tokens = [tok(0, "el", pos="DET"), tok(1, "rayos", "rayo"), tok(2, "X.", "x.", "NOUN")]
    sentences = [Sentence(f"s{n}", "", tokens, n) for n in range(25)]
    groups = text.lexical_occurrences(sentences, 20)
    assert list(groups) == ["rayo|NOUN"]
    assert len(groups["rayo|NOUN"]) == 20 and groups["rayo|NOUN"][-1]["ordinal"] == 20


def test_apply_fix_lemma_and_part_of_speech():
    keys = {"capricho|VERB", "caracter|NOUN", "lista|NOUN", "listo|ADJ"}
    assert lexical_answers.apply_fix("capricho|VERB", None, keys) == ("capricho|VERB", None, False, "")
    assert lexical_answers.apply_fix("caracter|NOUN", "carácter", keys) == ("carácter|NOUN", "carácter", False, "")
    assert lexical_answers.apply_fix("capricho|VERB", "capricho|NOUN", keys) == ("capricho|NOUN", "capricho", True, "")
    assert lexical_answers.apply_fix("caracter|NOUN", "Carácter|noun", keys) == ("carácter|NOUN", "carácter", False, "")
    # "listo|ADJ" is another group with its own sense ids: same key, the moved senses are prefixed
    assert lexical_answers.apply_fix("lista|NOUN", "listo|ADJ", keys) == ("listo|ADJ", "listo", True, "lista.")


def test_passage_marks_the_word_in_its_unit_with_literal_translation_and_hint():
    a = Sentence("s1", "", [tok(0, "Lo"), tok(1, "miró", ws="")], 1)
    b = Sentence("s2", "", [tok(0, "Sonrió", ws="")], 1)
    line = model_prompts.passage([a, b], "s1", 1, "посмотрел", "Он посмотрел.", ["Sancho", "Dorotea"])
    assert line == "Lo [miró] Sonrió => посмотрел | RU: Он посмотрел. (в сцене: Sancho, Dorotea)"
    assert model_prompts.passage([b], "s2", 0, reference="") == "[Sonrió] | RU: —", "the translator left the sentence out"
    assert model_prompts.passage([b], "s2", 0) == "[Sonrió]", "no alignment, no RU"


def test_lexical_block_lists_dictionary_senses_then_occurrences():
    senses = [{"id": "1.1", "gram": "vt", "text": "бросать", "examples": ["echar olor — издавать запах"]},
              {"id": "P1", "gram": None, "text": "echar de menos: скучать", "examples": []}]
    block = model_prompts.lexical_block("echar|VERB @2", ["lo [echó] fuera => его выгнал вон"], ("echar", senses))
    assert block.splitlines() == ["## echar|VERB @2", "DICT echar", "D1.1: бросать (vt)", "  e.g. echar olor — издавать запах",
                                  "DP1: echar de menos: скучать", "1. lo [echó] fuera => его выгнал вон"]


def test_pack_calls_is_size_bound_and_independent_of_input_order():
    blocks = ["## a\n" + "x" * 45, "## b\n" + "x" * 25, "## c\n" + "x" * 25, "## d\n" + "x" * 95]
    calls = model_prompts.pack_calls(blocks, 80, anchor_every=10 ** 9)
    assert [[x[:4] for x in c] for c in calls] == [["## a", "## b"], ["## c"], ["## d"]]
    assert model_prompts.pack_calls(list(reversed(blocks)), 80, anchor_every=10 ** 9) == calls


def test_pack_calls_keeps_a_change_local():
    """A grown block moves call boundaries only up to the next anchor: later calls stay as they were (and cached)."""
    blocks = [f"## w{n:03d}\n" + "x" * 50 for n in range(300)]
    before = model_prompts.pack_calls(blocks, 400)
    changed = list(blocks)
    changed[10] += "y" * 300
    after = model_prompts.pack_calls(changed, 400)
    same = [c for c in after if c in before]
    assert len(before) - len(same) <= 4, "only the calls around the changed block differ"
    assert sum(1 for c in after for b in c if b.startswith("## w299")) == 1


def test_merge_parts_joins_senses_by_dictionary_id_or_gloss():
    first = {"fix": None, "senses": [{"id": "1", "gloss": "нет", "dict": "1"}, {"id": "2", "gloss": "разве не", "dict": None}],
             "occ": ["1", "2", None]}
    second = {"fix": None, "senses": [{"id": "1", "gloss": "разве не", "dict": None}, {"id": "2", "gloss": "не", "dict": "1"},
                                      {"id": "3", "gloss": "ни", "dict": "3"}], "occ": ["2", "1", "3"]}
    merged = lexical_answers.merge_parts([first, second])
    assert [(s["id"], s["gloss"]) for s in merged["senses"]] == [("1", "нет"), ("2", "разве не"), ("3", "ни")]
    assert merged["occ"] == ["1", "2", None, "1", "2", "3"]
    assert lexical_answers.merge_parts([{"skip": True}, {"skip": True}]) == {"skip": True}


def _sentence(sid, words, paragraph, subject=False, name=None, speech=False):
    tokens = [tok(i, w) for i, w in enumerate(words)]
    if subject:
        tokens[0].dep = "nsubj"
    if name:
        tokens.append(tok(len(tokens), name, pos="PROPN"))
    if speech:
        tokens.append(tok(len(tokens), "dijo", "decir", "VERB"))
    return Sentence(sid, " ".join(t.text for t in tokens), tokens, paragraph)


def test_units_glue_short_and_actorless_sentences_within_a_paragraph():
    long = "uno dos tres cuatro cinco seis siete".split()
    s = [_sentence("c01p001s1", long, 1, subject=True),
         _sentence("c01p001s2", ["Lo", "miró"], 1),                       # short: joins
         _sentence("c01p001s3", long, 1),                                 # no actor of its own: joins
         _sentence("c01p001s4", long, 1, subject=True),                   # long, own subject: new unit
         _sentence("c01p002s1", long, 2),                                 # new paragraph: new unit
         _sentence("c02p001s1", ["Sí"], 1)]                               # new chapter: new unit
    assert [[x.sid for x in u] for u in units.build(s)] == [
        ["c01p001s1", "c01p001s2", "c01p001s3"], ["c01p001s4"], ["c01p002s1"], ["c02p001s1"]]
    assert len(units.build(s, max_words=10)[0]) == 2, "a unit does not grow past max_words"


def test_units_join_short_dialogue_lines_across_paragraphs():
    s = [Sentence("c01p001s1", "— ¿Y?", [tok(0, "—"), tok(1, "Y")], 1),
         Sentence("c01p002s1", "— Nada.", [tok(0, "—"), tok(1, "Nada")], 2),
         Sentence("c01p003s1", "Salió.", [tok(0, "Salió")], 3)]
    assert [[x.sid for x in u] for u in units.build(s)] == [["c01p001s1", "c01p002s1"], ["c01p003s1"]]


def test_names_hint_puts_speakers_first_and_skips_units_with_an_actor():
    long = "uno dos tres cuatro cinco seis siete".split()
    s = [_sentence("c01p001s1", long, 1, name="Sancho", speech=True),
         _sentence("c01p002s1", long, 2, name="Dorotea"),
         _sentence("c01p003s1", long, 3),
         _sentence("c02p001s1", long, 1)]
    u = units.build(s)
    assert units.names_hint(u, 2) == ["Sancho", "Dorotea"]
    assert units.names_hint(u, 1) == [], "the unit names its own actor"
    assert units.names_hint(u, 3) == [], "the hint does not look into the previous chapter"
    assert units.names(_sentence("x", ["vio", "a"], 1, name="Dorotea") ) == ["Dorotea"]


def test_export_groups_sentences_into_paragraphs():
    data = {"chapter": 1, "title": "T", "sentences": [
        {"sid": "c01p001s1", "paragraph": 1, "tokens": ["Hola"], "ws": [""], "annotations": {"s": [[0, 0, "привет", None]]},
         "lexical": [{"token": 0, "lemma": "hola", "key": "hola|INTJ#1", "ordinal": 1, "gloss": "привет", "gram": None, "form": ""}]},
        {"sid": "c01p001s2", "paragraph": 1, "tokens": ["Sí"], "ws": [""], "annotations": None, "lexical": []},
        {"sid": "c01p002s1", "paragraph": 2, "tokens": ["No"], "ws": [""], "annotations": None, "lexical": []},
    ]}
    doc = export_package.chapter_document(data)
    assert [p["id"] for p in doc["paragraphs"]] == ["c01p001", "c01p002"]
    first = doc["paragraphs"][0]["sentences"][0]
    assert first["segments"] == [[0, 0, "привет", None]] and first["lexical"][0]["key"] == "hola|INTJ#1"
    assert doc["paragraphs"][0]["sentences"][1]["segments"] == []
    data["source_footnotes"] = {"n_1": "Nota."}
    data["sentences"][0]["notes"] = ["n_1"]
    doc = export_package.chapter_document(data)
    assert doc["footnotes"] == [{"id": "n_1", "text": "Nota.", "side": "source"}]
    assert doc["paragraphs"][0]["sentences"][0]["notes"] == ["n_1"] and doc["paragraphs"][0]["sentences"][1]["notes"] == []


def bag_of_words(texts):
    """Fake sentence embedding: normalised word counts, so a translation is a sentence with the same words."""
    vocab = sorted({w for t in texts for w in t.split()})
    rows = []
    for t in texts:
        v = [t.split().count(w) for w in vocab]
        norm = sum(x * x for x in v) ** 0.5
        rows.append([x / norm for x in v])
    return rows


def _align(es, ru):
    shared = sorted(set(" ".join(es + ru).split()))

    def embed(texts):  # one vocabulary for both sides, as a multilingual encoder would have
        return bag_of_words(texts + [" ".join(shared)])[:len(texts)]
    return [(e, r) for e, r, _ in align.align(es, ru, embed)]


def test_align_merges_splits_and_skips():
    assert _align(["a b", "c d", "e f"], ["a b", "c d", "e f"]) == [([0], [0]), ([1], [1]), ([2], [2])]
    assert _align(["a b", "c d", "e f"], ["a b c d", "e f"]) == [([0, 1], [0]), ([2], [1])], "two sentences joined"
    assert _align(["a b c d", "e f"], ["a b", "c d", "e f"]) == [([0], [0, 1]), ([1], [2])], "one sentence split"
    assert _align(["a b", "x y z w", "e f"], ["a b", "e f"]) == [([0], [0]), ([1], []), ([2], [1])], "left out"


def test_sentence_references_join_insertions_and_check_ids():
    sentences = [Sentence(sid, "", [], 1) for sid in ("s1", "s2", "s3")]
    alignment = {"ru": [{"text": "Раз."}, {"text": "Вставка.", "notes": ["n_1"]}, {"text": "Два и три."}],
                 "groups": [{"es": ["s1"], "ru": [0], "score": 0.9}, {"es": [], "ru": [1], "score": None},
                            {"es": ["s2", "s3"], "ru": [2], "score": 0.8}]}
    references, ref_of = published.sentence_references(sentences, alignment)
    assert references == [{"text": "Раз. Вставка.", "score": 0.9, "notes": ["n_1"]}, {"text": "Два и три.", "score": 0.8, "notes": []}]
    assert ref_of == {"s1": 0, "s2": 1, "s3": 1}
    try:
        published.sentence_references(sentences[:2], alignment)
        raise AssertionError("a stale alignment must be rejected")
    except ValueError:
        pass


def test_aligned_segment_prompt_puts_translation_after_its_group():
    chunk = [Sentence(sid, "", [tok(0, w)], 1) for sid, w in (("s1", "Uno"), ("s2", "Dos"), ("s3", "Tres"), ("s4", "Cua"))]
    references = [{"text": "Один."}, {"text": "Два, три."}, {"text": None}]
    prompt = model_prompts.aligned_segment_prompt(chunk, "", references, {"s1": 0, "s2": 1, "s3": 1, "s4": 2})
    assert prompt.splitlines() == ["[s1] 0 Uno", "RU: Один.", "[s2] 0 Dos", "[s3] 0 Tres", "RU: Два, три.",
                                   "[s4] 0 Cua", "RU: —"]
    assert '"RU:" lines' in model_prompts.SEGMENT_SYSTEM_ALIGNED and "REFERENCE" not in model_prompts.SEGMENT_SYSTEM_ALIGNED


ECHAR = """<k>echar</k>
<b>1.</b> <abr>vt</abr>
 1) <dtrn>бросать, швырять; метать</dtrn>
  <ex>echar olor — издавать запах</ex>
 2) <dtrn>увольнять; снимать <co>(<abr>разг.</abr>)</co></dtrn>
<b>2.</b> <abr>vi</abr>
 <dtrn>пускаться <co>(<i>в путь</i>)</co></dtrn>
- <kref>echar de menos</kref>
  ••
  <ex>echar de ver — увидеть, заметить</ex>"""
MENOS = "<k>echar de menos</k>\n <dtrn></dtrn>\n 1) <dtrn>недосчитаться</dtrn>\n 2) <dtrn>скучать</dtrn>"


def test_dictionary_entry_keeps_every_sense_example_idiom_and_reference():
    entry = dictionary.parse_entry(ECHAR)
    assert [(s["id"], s["gram"], s["text"]) for s in entry["senses"]] == [
        ("1.1", "vt", "бросать, швырять; метать"), ("1.2", "vt", "увольнять; снимать (разг.)"), ("2.1", "vi", "пускаться (в путь)")]
    assert entry["senses"][0]["examples"] == [("echar olor", "издавать запах")]
    assert entry["idioms"] == [("echar de ver", "увидеть, заметить")] and entry["refs"] == ["echar de menos"]
    stressed = "<k>acudir</k>\n <abr>vi</abr>\n 1) <dtrn>яв<nu />'<nu />иться</dtrn>\n 2) <dtrn>помочь</dtrn>"
    assert [s["text"] for s in dictionary.parse_entry(stressed)["senses"]] == ["явиться", "помочь"]
    assert [s["text"] for s in dictionary.parse_entry(MENOS)["senses"]] == ["недосчитаться", "скучать"], "empty sense dropped"


def test_dictionary_headword_candidates():
    assert dictionary.candidate_headwords("cuidadosamente")[1:] == ["cuidadosa", "cuidadoso"]
    assert "absorber" in dictionary.candidate_headwords("absorbido")
    assert "extraer" in dictionary.candidate_headwords("extraído")
    assert "aburrido" in dictionary.candidate_headwords("aburridísimo")
    assert dictionary.candidate_headwords("antojar")[-1] == "antojarse"
    assert dictionary.candidate_headwords("alzarse")[-1] == "alzar"


def test_dictionaries_lookup_order_and_fallback(tmp_path):
    from conftest import stardict
    first = stardict(tmp_path / "a", {"echar": ECHAR})
    second = stardict(tmp_path / "b", {"echar": "<k>echar</k>\n <dtrn>другое</dtrn>", "absorber": "<k>absorber</k>\n <dtrn>впитывать</dtrn>"})
    books = dictionary.Dictionaries([first, second, tmp_path / "missing"])
    assert books.lookup("Echar")[1]["senses"][0]["text"] == "бросать, швырять; метать", "the first dictionary wins"
    assert books.lookup("absorbido")[0] == "absorber"
    assert books.lookup("xyz") == (None, None)
    assert not dictionary.Dictionaries([tmp_path / "missing"])


def test_dictionary_inventory_shows_examples_idioms_and_phrases_found_in_the_text(tmp_path):
    from conftest import stardict
    books = dictionary.Dictionaries([stardict(tmp_path / "a", {"echar": ECHAR, "echar de menos": MENOS})])
    _, plain = books.inventory("echar", ["Lo echaron del trabajo."])
    assert [s["id"] for s in plain] == ["1.1", "1.2", "2.1"] and all(not s["examples"] for s in plain)
    _, rich = books.inventory("echar", ["La echó de menos.", "Echó de ver la mancha; la flor echaba olor."])
    assert rich[0]["examples"] == ["echar olor — издавать запах"]
    assert [(s["id"], s["text"]) for s in rich[3:]] == [("F1", "echar de ver — увидеть, заметить"),
                                                        ("P1", "echar de menos: недосчитаться; скучать")]


def test_check_dictionary_senses_counts_matches_misses_and_bad_ids():
    senses = [{"id": "1.1"}, {"id": "1.2"}, {"id": "F1"}]
    entry = {"senses": [{"id": "1", "dict": "1.2"}, {"id": "2", "dict": None}, {"id": "3", "dict": "7"}, {"id": "4"}, {"id": "5", "dict": "F1"}]}
    assert lexical_answers.check_dictionary_senses(entry, senses) == (2, 2, 1)


def test_dictionary_ids_lose_the_copied_line_prefix():
    entry = {"senses": [{"dict": "D1.2"}, {"dict": "DF1"}, {"dict": "DP2"}, {"dict": "F3"}, {"dict": None}, {"dict": 2}]}
    assert [s["dict"] for s in lexical_answers.normalize_dict_ids(entry)["senses"]] == ["1.2", "F1", "P2", "F3", None, "2"]


def test_prompt_audit_assigns_every_line_to_a_part():
    import prompt_audit
    import tiktoken
    encoder = tiktoken.get_encoding("o200k_base")
    lexical = ("## echar|VERB\nDICT echar\nD1.1: бросать (vt)\n  e.g. echar olor — пахнуть\nDF1: echar de ver — заметить\n"
               "1. lo [echó] => выгнал | RU: Его выгнали. (в сцене: Sancho)")
    parts = prompt_audit.classify(lexical, prompt_audit.LEXICAL_PARTS, encoder)
    assert all(parts[p] > 0 for p in ("dictionary senses", "dictionary examples", "idioms and phrases", "occurrences",
                                      "headers", "literal", "published translation", "names hint"))
    segment = "CONTEXT: Antes.\n[c01p001s1] 0 Hola|1 .\nAUTHOR NOTE: Nota.\nRU: Привет.\nNOTE: Сноска.\nREFERENCE:\nПривет."
    parts = prompt_audit.classify(segment, prompt_audit.SEGMENT_PARTS, encoder)
    assert all(parts[p] > 0 for p in ("context", "source tokens", "author footnotes", "published translation", "footnotes",
                                      "reference excerpt"))


def test_score_deviations_weigh_the_score_by_length():
    """Short translations scatter widely, long ones barely: the same low score is unusual only for a long sentence."""
    import random
    rnd = random.Random(1)
    refs = []
    for n in range(300):
        words = rnd.choice([2, 4, 8, 16, 32, 64])
        spread = 0.3 / words ** 0.5  # about the spread measured on chapter 1
        refs.append({"text": " ".join(["w"] * words), "score": 0.8 + rnd.uniform(-spread, spread)})
    refs += [{"text": "w w", "score": 0.6}, {"text": " ".join(["w"] * 64), "score": 0.6}, {"text": None, "score": None}]
    z = published.score_deviations(refs)
    short, long_, omitted = z[-3:]
    assert omitted is None and long_ < published.CHECK_BELOW_Z < short
    assert published.score_deviations(refs[:10]) == [None] * 10, "too few groups to fit"


def test_reference_marks_trust_the_model_only_for_checked_groups():
    refs = [{"text": "А.", "check": False}, {"text": "Б.", "check": True}, {"text": "В.", "check": True}, {"text": "Г.", "check": True}]
    ref_of = {"s1": 0, "s2": 1, "s3": 2, "s4": 3}
    chapter_build.reference_marks(refs, ref_of, {"s1": {"r": "none"}, "s2": {"r": "free"}, "s3": {"r": "none"}, "s4": {}})
    assert [(r["loose"], r["uncertain"]) for r in refs] == [(False, False), (True, False), (False, True), (False, True)]


def test_format_check_names_every_broken_place():
    import pytest
    good = {"index": 1, "title": "T", "paragraphs": [{"id": "c01p001", "sentences": [{
        "id": "c01p001s1", "tokens": ["Hola"], "ws": [""], "segments": [[0, 0, "Привет", None]], "grammar": [], "context": [],
        "variants": [], "expressions": [], "lexical": [{"t": 0, "lemma": "hola", "key": "hola|INTJ#1", "n": 1, "gloss": "привет"}]}]}]}
    export_package.check(good, "chapter")
    bad = json.loads(json.dumps(good))
    bad["paragraphs"][0]["sentences"][0]["lexical"][0]["key"] = "hola"
    del bad["paragraphs"][0]["sentences"][0]["ws"]
    with pytest.raises(ValueError) as e:
        export_package.check(bad, "chapter")
    assert "ws" in str(e.value) and "key" in str(e.value)


def test_offline_sense_follows_the_published_translation_else_the_first():
    import build_offline
    senses = [{"id": "1.1", "text": "держать (в руках), удерживать"}, {"id": "1.2", "text": "иметь (что-либо), владеть"},
              {"id": "F1", "text": "tener que — быть должным"}]
    assert build_offline.choose(senses, "Он должен был иметь при себе карту.") == (senses[1], "translation")
    assert build_offline.choose(senses, "Ничего общего.") == (senses[0], "first")
    assert build_offline.choose(senses, None) == (senses[0], "first")
    assert build_offline.choose([], "что-то") == (None, None)


def test_offline_gloss_is_short_and_takes_the_meaning_of_a_phrase():
    import build_offline
    assert build_offline.gloss({"id": "1", "text": "иметь (что-либо), владеть, обладать, располагать (чем-либо); держать"}) == "иметь, владеть, обладать, располагать"
    assert build_offline.gloss({"id": "F1", "text": "echar de ver — увидеть, заметить"}) == "увидеть, заметить"
    assert build_offline.gloss({"id": "1", "text": "(указательное местоимение)"}) == "(указательное местоимение)"


def test_model_chapters_get_their_dictionary_entries_and_the_sense_chosen_in_them(tmp_path):
    import types
    path = tmp_path / "chapter.json"
    path.write_text(json.dumps({"sentences": [{"lexical": [
        {"key": "rastro|NOUN#1", "lemma": "rastro"}, {"key": "lista|NOUN#lista.1", "lemma": "lista"}, {"key": "ya|ADV#1", "lemma": "ya"}]}]}))
    senses = [{"id": "1", "text": "грабли", "gram": "m", "examples": []}, {"id": "3", "text": "след", "gram": "m", "examples": []}]
    L = types.SimpleNamespace(
        groups=[("rastro|NOUN", []), ("listo|ADJ", []), ("lista|NOUN", []), ("ya|ADV", [])],
        dict_entries={"rastro|NOUN": ("rastro", senses), "listo|ADJ": ("listo", senses[:1]), "lista|NOUN": ("lista", senses[1:])},
        chapters={1: types.SimpleNamespace(path=path, data=json.loads(path.read_text()))})
    entries = {"rastro|NOUN": {"senses": [{"id": "1", "gloss": "след", "dict": "3"}]},
               "listo|ADJ": {"fix": "lista|NOUN", "senses": [{"id": "1", "gloss": "список", "dict": "1"}]},
               "lista|NOUN": {"senses": [{"id": "1", "gloss": "полоса", "dict": "3"}]},
               "ya|ADV": {"senses": [{"id": "1", "gloss": "уже"}]}}
    lexicon_build.attach_entries(L, entries)
    data = json.loads(path.read_text())
    rastro, moved, ya = data["sentences"][0]["lexical"]
    assert rastro["sense"] == "3" and "sense" not in moved and "sense" not in ya
    assert sorted(data["entries"]) == ["lista|NOUN", "rastro|NOUN"]
    assert data["entries"]["lista|NOUN"]["headword"] == "lista"  # the group that owns the corrected key keeps its entry
    assert data["entries"]["rastro|NOUN"]["senses"][1] == {"id": "3", "text": "след", "gram": "m"}


# --- EPUB import, chapter selection, English ---------------------------------------------------------------------

def _epub(path, docs):
    """A minimal EPUB: docs is a list of (id, epub:type, body markup) in spine order."""
    import zipfile
    with zipfile.ZipFile(path, "w") as z:
        z.writestr("mimetype", "application/epub+zip")
        z.writestr("META-INF/container.xml", '<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
                   '<rootfiles><rootfile full-path="OPS/book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        items = "".join(f'<item id="{i}" href="xhtml/{i}.xhtml" media-type="application/xhtml+xml"/>' for i, _, _ in docs)
        spine = "".join(f'<itemref idref="{i}"/>' for i, _, _ in docs)
        z.writestr("OPS/book.opf", f'<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf"><manifest>{items}</manifest>'
                   f"<spine>{spine}</spine></package>")
        for i, kind, body in docs:
            z.writestr(f"OPS/xhtml/{i}.xhtml", '<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">'
                       f'<body epub:type="{kind}">{body}</body></html>')
    return path


def test_epub_chapters_in_spine_order_titled_by_their_book(tmp_path):
    path = _epub(tmp_path / "b.epub", [
        ("toc", "toc", "<h1>Contents</h1><p>I</p>"),
        ("t1", "titlepage", "<p>Don Quijote</p><p>Cervantes</p>"),
        ("c1", "chapter", "<h2>I</h2><p><span>EN UN LUGAR</span> de la Mancha, de cuyo nombre&#160;no quiero acordarme.</p><p>Rocinante.</p>"),
        ("n1", "backmatter", "<h2>Note</h2><p>Not a chapter.</p>"),
        ("c2", "chapter", "<h2>II</h2><p>Sancho<br/>Panza.</p>")])
    chapters = text.read_chapters(path)
    assert [(c.index, c.title) for c in chapters] == [(1, "Don Quijote · I"), (2, "Don Quijote · II")]
    assert chapters[0].paragraphs == ["EN UN LUGAR de la Mancha, de cuyo nombre no quiero acordarme.", "Rocinante."]
    assert chapters[1].paragraphs == ["Sancho Panza."]


def test_chapters_setting_leaves_out_prefaces_and_glossaries(tmp_path):
    from conftest import FB2_HEAD, FB2_TAIL
    body = ("<section><title><p>Primera parte</p></title><section><title><p>Prólogo</p></title><p>P.</p></section>"
            "<section><title><p>I</p></title><p>Uno.</p></section><section><title><p>II</p></title><p>Dos.</p></section></section>"
            "<section><title><p>Glosario</p></title><p>G.</p></section>")
    path = tmp_path / "b.fb2"
    path.write_text(FB2_HEAD.format(lang="es") + body + FB2_TAIL, encoding="utf-8")
    assert [c.title for c in text.read_chapters(path, keep="")] == ["Prólogo", "I", "II", "Glosario"]
    assert [(c.index, c.title, c.paragraphs) for c in text.read_chapters(path, keep="^[IVXLC]+$")] == [(1, "I", ["Uno."]), (2, "II", ["Dos."])]


def test_english_form_notes():
    def form(t, lemma, pos, morph):
        return text.form_note(tok(0, t, lemma, pos, morph), lang="en")
    assert form("sought", "seek", "VERB", {"VerbForm": "Part", "Tense": "Past"}) == "причастие прош. вр. от seek"
    assert form("rode", "ride", "VERB", {"VerbForm": "Fin", "Tense": "Past"}) == "прош. вр. от ride"
    assert form("speaks", "speak", "VERB", {"VerbForm": "Fin", "Tense": "Pres", "Person": "3"}) == "наст. вр., 3 л. ед. ч. от speak"
    assert form("walking", "walk", "VERB", {"VerbForm": "Part", "Tense": "Pres"}) == "-ing форма от walk"
    assert form("men", "man", "NOUN", {"Number": "Plur"}) == "мн. ч. от man"
    assert form("greater", "great", "ADJ", {"Degree": "Cmp"}) == "сравн. ст. от great"
    assert form("power", "power", "NOUN", {"Number": "Sing"}) == ""


def test_english_entry_keeps_transcription_and_label_without_sound_files():
    entry = ("<k>seek</k>\n<tr>siːk</tr> <abr>брит.</abr> <rref>seek.wav</rref> / <abr>амер.</abr> <rref>1.wav</rref>\n"
             "<abr>гл.</abr><c>;</c> <abr>прош. вр.</abr> <kref>sought</kref>\n<blockquote>1) <dtrn>искать</dtrn></blockquote>\n"
             "<blockquote>2) <dtrn>добиваться</dtrn></blockquote>")
    parsed = dictionary.parse_entry(entry)
    assert [(s["id"], s["gram"], s["text"]) for s in parsed["senses"]] == [
        ("1", "[siːk] гл.; прош. вр. sought", "искать"), ("2", "[siːk] гл.; прош. вр. sought", "добиваться")]
    assert parsed["refs"] == [], "the past form in the label is not a phrase entry"


def test_english_entry_parts_in_blockquotes_and_synonyms_left_out():
    q = lambda s, depth=1: "<blockquote>" * depth + s + "</blockquote>" * depth
    entry = "\n".join([
        "<k>tilt</k>", "<tr>tɪlt</tr> <abr>брит.</abr> <rref>tilt.wav</rref>",
        q("<b>1.</b> <abr>сущ.</abr>"), q("1)"), q("а) <dtrn>наклон</dtrn>", 2), q("<b>Syn:</b>", 3), q("<kref>slant</kref>", 3),
        q("б) <dtrn>атака</dtrn>", 2), q("2) <dtrn>навес</dtrn>"), q("<b>Syn:</b>", 2), q("<kref>awning</kref>, <kref>canopy</kref>", 2),
        q("•"), q("- <kref>at full tilt</kref>"),
        q("<b>2.</b> <abr>гл.</abr><c>;</c> <abr>прош. вр.</abr> <kref>tilted</kref>"), q("1) <dtrn>наклонять</dtrn>"),
        q("<b>Ant:</b> <kref>level</kref>", 2), q("2) <dtrn>атаковать</dtrn>"),
    ])
    parsed = dictionary.parse_entry(entry)
    assert [(s["id"], s["gram"], s["text"]) for s in parsed["senses"]] == [
        ("1.1", "[tɪlt] сущ.", "наклон"), ("1.2", "[tɪlt] сущ.", "атака"), ("1.3", "[tɪlt] сущ.", "навес"),
        ("2.1", "[tɪlt] гл.; прош. вр. tilted", "наклонять"), ("2.2", "[tɪlt] гл.; прош. вр. tilted", "атаковать")]
    assert parsed["refs"] == ["at full tilt"], "synonyms, antonyms and the past form are not phrase entries"


def test_english_field_label_goes_with_the_senses_under_it():
    q = lambda s, depth=1: "<blockquote>" * depth + s + "</blockquote>" * depth
    entry = "\n".join(["<k>note</k>", "<tr>nəʊt</tr>", "<abr>сущ.</abr> <c>; = notice</c>",
                       q("1) <abr><dtrn>фин.</dtrn></abr>"), q("а) <dtrn>вексель</dtrn>", 2), q("б) <dtrn>расписка</dtrn>", 2),
                       q("2) <dtrn>заметка</dtrn>")])
    assert [(s["gram"], s["text"]) for s in dictionary.parse_entry(entry)["senses"]] == [
        ("[nəʊt] сущ.; = notice", "фин. вексель"), ("[nəʊt] сущ.; = notice", "фин. расписка"), ("[nəʊt] сущ.; = notice", "заметка")]


def test_offline_choice_keeps_to_the_part_of_speech():
    import build_offline
    senses = [{"id": "1.1", "gram": "[treɪl] сущ.", "text": "след"}, {"id": "2.1", "gram": "[treɪl] гл.", "text": "тащить"},
              {"id": "2.2", "gram": "[treɪl] гл.", "text": "волочиться"}]
    assert build_offline.choose(senses, "Он тащил мешок", "VERB")[0]["id"] == "2.1"
    assert build_offline.choose(senses, None, "VERB") == (senses[1], "first"), "the first sense of the verb, not of the noun"
    assert build_offline.choose(senses, None, "NOUN")[0]["id"] == "1.1"
    assert build_offline.choose(senses, None, "ADV")[0]["id"] == "1.1", "no sense of that part: all of them"
    spanish = [{"id": "1", "gram": "adj", "text": "чистый"}, {"id": "2", "gram": "m", "text": "чистильщик"}]
    assert build_offline.choose(spanish, None, "NOUN")[0]["id"] == "2"


# --- matching the chapters of two editions ------------------------------------------------------------------------

def _topic_embed(texts):
    """Fake LaBSE: a text's vector is the one-hot of the TOPICn word it holds, so 'the same content' is the same topic."""
    import numpy as np
    rows = []
    for t in texts:
        v = np.zeros(32)
        v[int(re.search(r"TOPIC(\d+)", t).group(1))] = 1.0
        rows.append(v)
    return np.array(rows)


def _ch(index, topic_open, topic_close, n_words, title=None):
    body = " ".join(["w"] * (n_words - 2))
    return text.Chapter(index, title or str(index), [f"TOPIC{topic_open} {body}", f"TOPIC{topic_close}"])


def test_chapters_match_one_to_one_split_merge_and_missing():
    import chapters
    # original: prologue(1) A(2) B(3) C(4) D(5); translation: A, B split in two, C+D merged, an afterword
    source = [_ch(1, 1, 1, 300), _ch(2, 2, 2, 3000), _ch(3, 3, 4, 3000), _ch(4, 5, 5, 2000), _ch(5, 6, 6, 2000)]
    reference = [_ch(1, 2, 2, 2800), _ch(2, 3, 9, 1500), _ch(3, 10, 4, 1400), _ch(4, 5, 6, 3700), _ch(5, 7, 7, 400)]
    groups = chapters.match(source, reference, _topic_embed)
    assert [(s, r) for s, r, _ in groups] == [([0], []), ([1], [0]), ([2], [1, 2]), ([3, 4], [3]), ([], [4])]
    rows = chapters.pairs(groups)
    assert [(p["source"], p["reference"], p["doubt"]) for p in rows] == [
        (1, [], "нет в переводе"), (2, [1], ""), (3, [2, 3], "1:2"), (4, [4], "2:1"), (5, [4], "2:1")]


def test_chapters_identity_when_the_editions_agree():
    import chapters
    source = [_ch(i, i, i, 1000 + 100 * i) for i in range(1, 6)]
    reference = [_ch(i, i, i, 900 + 90 * i) for i in range(1, 6)]
    rows = chapters.pairs(chapters.match(source, reference, _topic_embed))
    assert [(p["source"], p["reference"], p["doubt"]) for p in rows] == [(i, [i], "") for i in range(1, 6)]


def test_reference_chapter_joins_parts_and_follows_the_map(monkeypatch, tmp_path):
    import chapters
    from conftest import FB2_HEAD, FB2_TAIL
    body = ('<section><title><p>1</p></title><p>Uno.</p></section>'
            '<section><title><p>2</p></title><p>Dos<a xmlns:l="http://www.w3.org/1999/xlink" l:href="#n1" type="note">1</a>.</p></section>'
            '</body><body name="notes"><section id="n1"><p>Nota.</p></section>')
    ref = tmp_path / "ru.fb2"
    ref.write_text(FB2_HEAD.format(lang="ru") + body + FB2_TAIL, encoding="utf-8")
    monkeypatch.setattr(chapters, "REFERENCE", ref)
    monkeypatch.setattr(chapters, "ROOT", tmp_path)
    monkeypatch.setattr(chapters, "BOOK", {"reference_chapters": None})
    assert chapters.reference_chapter(2).paragraphs == ["Dos."], "no map: chapter n goes with chapter n"
    with pytest.raises(SystemExit, match="run builder/chapters.py"):
        chapters.reference_chapter(3)
    (tmp_path / "build").mkdir()
    (tmp_path / "build" / "chapters.json").write_text(json.dumps({"pairs": [
        {"source": 1, "reference": [1, 2]}, {"source": 2, "reference": []}]}))
    joined = chapters.reference_chapter(1)
    assert joined.paragraphs == ["Uno.", "Dos."] and [(f["id"], f["paragraph"]) for f in joined.footnotes] == [("n1", 2)]
    assert chapters.reference_chapter(2).paragraphs == []
    monkeypatch.setattr(chapters, "BOOK", {"reference_chapters": {"2": [2]}})
    assert chapters.reference_chapter(2).paragraphs == ["Dos."], "the book's settings win over the file"
