"""Scenario S1 (build a chapter) and S2 (export a package): step-level and end-to-end tests."""
import json
import re
import sys
import zipfile

import pytest

import chapter_build
import export_package
import lexicon_build
import llm
import model_prompts
import published
import run_chapter
import run_lexicon
import segment_answers
import text
from conftest import FakeLLM, needs_spacy, read_json
from text import Sentence, Token


# --- S1.1 import -----------------------------------------------------------------------------------------------

def test_s1_1_read_chapters_titles_and_paragraphs(books):
    es, _ = books
    chapters = text.read_chapters(es)
    assert [c.title for c in chapters] == ["I. Primero", "II. Segundo"]
    assert len(chapters[0].paragraphs) == 28 and chapters[1].paragraphs[1] == "El caballero miraba los molinos."


def test_s1_1_read_chapters_keeps_epigraphs_documents_and_verse_in_order(tmp_path):
    from conftest import FB2_HEAD, FB2_TAIL
    body = ("<section><title><p>1. ПЕРВАЯ</p></title>"
            "<epigraph><p>Свобода, Санчо, есть один из драгоценнейших даров неба.</p><text-author>Сервантес</text-author></epigraph>"
            "<p>Санчо открыл глаза.</p>"
            "<cite><p>Здесь лежит идальго.</p><p>Ламанча, 1605.</p></cite>"
            "<empty-line/><poem><title><p>Песня</p></title><stanza><v>Строка один</v><v>Строка два</v></stanza></poem>"
            "<subtitle>This file was created with BookDesigner program</subtitle>"
            "<p>Конец.</p></section>")
    path = tmp_path / "ru.fb2"
    path.write_text(FB2_HEAD.format(lang="ru") + body + FB2_TAIL, encoding="utf-8")
    chapter = text.read_chapters(path)[0]
    assert chapter.title == "1. ПЕРВАЯ"
    assert chapter.paragraphs == ["Свобода, Санчо, есть один из драгоценнейших даров неба.", "Сервантес", "Санчо открыл глаза.",
                                  "Здесь лежит идальго.", "Ламанча, 1605.", "Песня", "Строка один", "Строка два",
                                  "Конец."]


@needs_spacy
def test_s1_3_source_footnotes_attach_to_their_sentence(tmp_path):
    import spacy
    from conftest import FB2_HEAD, FB2_TAIL
    body = ('<section><title><p>I</p></title><p>-Se llamaba <emphasis>Rocinante</emphasis>'
            '<a xmlns:l="http://www.w3.org/1999/xlink" l:href="#n_1" type="note">[1]</a> -dijo ella-. '
            'Nadie lo sabía.<a xmlns:l="http://www.w3.org/1999/xlink" l:href="#n_2" type="note">[2]</a></p></section>'
            '</body><body name="notes"><section id="n_1"><title><p>1</p></title><p>Rocín antes.</p></section>'
            '<section id="n_2"><title><p>2</p></title><p>Nota final.</p></section>')
    path = tmp_path / "es.fb2"
    path.write_text(FB2_HEAD.format(lang="es") + body + FB2_TAIL, encoding="utf-8")
    chapter = text.read_chapters(path)[0]
    assert chapter.paragraphs == ["-Se llamaba Rocinante -dijo ella-. Nadie lo sabía."]
    assert [(f["id"], f["text"]) for f in chapter.footnotes] == [("n_1", "Rocín antes."), ("n_2", "Nota final.")]
    sentences = text.analyse(spacy.load("es_core_news_md"), chapter)
    assert [s.notes for s in sentences] == [[], ["n_1"], ["n_2"]], "the title comes first, without notes"
    prompt = model_prompts.segment_prompt(sentences, "", "", {f["id"]: f["text"] for f in chapter.footnotes})
    assert "AUTHOR NOTE: Rocín antes." in prompt.splitlines()[2]


# --- S1.2 normalisation and S1.3 analysis ----------------------------------------------------------------------

@needs_spacy
def test_s1_3_analyse_ids_clitics_whitespace_and_dialogue(books):
    import spacy
    chapter = text.read_chapters(books[0])[0]
    sentences = text.analyse(spacy.load("es_core_news_md"), chapter)
    assert [(s.sid, s.paragraph, s.text) for s in sentences[:2]] == [("c01p000s1", 0, "I. Primero"),
                                                                      ("c01p001s1", 1, "Don Quijote miró a su contendor.")]
    assert all(s.sid.startswith("c01p") for s in sentences)
    words = {t.text: t for s in sentences for t in s.tokens}
    assert words["embarcarnos"].lemma == "embarcar" and words["embarcarnos"].clitics == "nos"
    assert "—" in words and "-dijo" not in words, "dialogue hyphens become standalone dashes"
    rebuilt = "".join(t.text + t.ws for t in sentences[1].tokens)
    assert rebuilt.strip() == "Don Quijote miró a su contendor."


# --- S1.4 chunking and reference excerpt -----------------------------------------------------------------------

def _chapter(n_paragraphs, words_per_paragraph):
    ch = text.Chapter(1, "T", [f"p{i}" for i in range(n_paragraphs)])
    ch.sentences = [Sentence(f"c01p{i + 1:03d}s1", "", [Token(k, "w", "w", "NOUN", {}) for k in range(words_per_paragraph)], i + 1)
                    for i in range(n_paragraphs)]
    return ch


def test_s1_4_chunks_keep_whole_paragraphs_and_size():
    ch = _chapter(30, 100)
    chunks = model_prompts.chunk_sentences(ch, words_per_chunk=1000)
    assert [len(c) for c in chunks] == [10, 10, 10]
    assert sum(len(c) for c in chunks) == 30
    big = _chapter(3, 1500)
    assert [len(c) for c in model_prompts.chunk_sentences(big)] == [1, 1, 1], "an oversize paragraph is its own chunk"


def test_s1_4_reference_excerpt_is_proportional_with_margin():
    ch = _chapter(10, 100)
    ref = [f"ref{i}" for i in range(10)]
    first = model_prompts.reference_excerpt(ch, ref, ch.sentences[:5], margin=0.0)
    last = model_prompts.reference_excerpt(ch, ref, ch.sentences[5:], margin=0.0)
    assert first.split("\n") == ref[:5] and last.split("\n") == ref[5:]
    wide = model_prompts.reference_excerpt(ch, ref, ch.sentences[:5], margin=0.15)
    assert "ref5" in wide and "ref6" in wide


def test_s1_5_segment_prompt_numbers_tokens_and_appends_reference():
    s = Sentence("c01p001s1", "", [Token(0, "Hola", "hola", "INTJ", {}), Token(1, ".", ".", "PUNCT", {})], 1)
    prompt = model_prompts.segment_prompt([s], "Antes.", "Привет.")
    assert prompt.splitlines() == ["CONTEXT: Antes.", "[c01p001s1] 0 Hola|1 .", "REFERENCE:", "Привет."]


# --- S1.6 segment validation -----------------------------------------------------------------------------------

@pytest.mark.parametrize("segments,expected_problem", [
    ([[0, 1, "x", None]], None),
    ([[0, 0, "x", None], [1, 1, "y", None]], None),
    ([[0, 0, "x"], [0, 1, "y", None]], "gap/overlap"),
    ([[0, 0, "x", None], "bad"], "bad segment shape"),
    ([], "covers 0/2"),
])
def test_s1_6_check_segments_cases(segments, expected_problem):
    s = Sentence("s", "", [Token(0, "Hola", "hola", "INTJ", {}), Token(1, "mundo", "mundo", "NOUN", {})], 1)
    problems = segment_answers.check_segments(s, {"s": segments})
    if expected_problem is None:
        assert problems == []
    else:
        assert any(expected_problem in p for p in problems)


# --- LLM access (used by S1.5, S1.8) ---------------------------------------------------------------------------

class FakeResponse:
    def __init__(self, payload, status=200):
        self.payload, self.status_code, self.text = payload, status, json.dumps(payload)

    def json(self):
        return self.payload

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


def _chat(content, cost=0.5, finish="stop"):
    return FakeResponse({"choices": [{"message": {"content": content}, "finish_reason": finish}], "usage": {"cost": cost}})


@pytest.fixture
def offline_llm(tmp_path, monkeypatch):
    monkeypatch.setattr(llm, "CACHE", tmp_path / "cache")
    monkeypatch.setenv("OPENROUTER_API_KEY", "test")
    monkeypatch.setattr(llm.time, "sleep", lambda s: None)
    return monkeypatch


def test_llm_complete_parses_fenced_json_caches_and_passes_extra(offline_llm):
    sent = []
    offline_llm.setattr(llm.requests, "post", lambda url, **kw: (sent.append(kw["json"]), _chat('```json\n{"a": 1}\n```'))[1])
    assert llm.complete("m", "sys", "user", extra={"reasoning": {"enabled": False}}) == ({"a": 1}, 0.5, False)
    assert sent[0]["reasoning"] == {"enabled": False} and sent[0]["provider"] == {"data_collection": "deny"}
    assert llm.complete("m", "sys", "user", extra={"reasoning": {"enabled": False}}) == ({"a": 1}, 0.0, True)
    assert len(sent) == 1, "the same call must come from the cache"
    llm.complete("m", "sys", "user")
    assert len(sent) == 2, "other request settings are another cache entry"


def test_llm_complete_retries_empty_content_then_fails(offline_llm):
    calls = []
    offline_llm.setattr(llm.requests, "post", lambda url, **kw: (calls.append(1), _chat(None, finish="length"))[1])
    with pytest.raises(RuntimeError, match="empty content, finish_reason=length"):
        llm.complete("m", "s", "u", retries=2)
    assert len(calls) == 3


def test_llm_complete_recovers_after_bad_json(offline_llm):
    replies = iter([_chat("{broken"), _chat('{"ok": true}')])
    offline_llm.setattr(llm.requests, "post", lambda url, **kw: next(replies))
    assert llm.complete("m", "s", "u")[0] == {"ok": True}


def test_llm_batch_submits_polls_collects_and_caches(offline_llm):
    posted, deleted = [], []

    def post(url, **kw):
        posted.append(json.loads(kw["data"]))
        return FakeResponse({"id": "b1", "status": "validating"})

    states = iter([
        FakeResponse({"id": "b1", "status": "in_progress", "request_counts": {"total": 2}}),
        FakeResponse({"id": "b1", "status": "completed", "results": [
            {"custom_id": "a", "response": {"body": {"choices": [{"message": {"content": '{"x": 1}'}}], "usage": {"cost": 0.2}}}},
            {"custom_id": "b", "error": {"message": "boom"}},
        ]}),
    ])
    offline_llm.setattr(llm.requests, "post", post)
    offline_llm.setattr(llm.requests, "get", lambda url, **kw: next(states))
    offline_llm.setattr(llm.requests, "delete", lambda url, **kw: deleted.append(url))
    results, cost, errors = llm.batch("m", "sys", {"a": "ua", "b": "ub"}, poll_seconds=0, log=lambda *a: None)
    assert results == {"a": {"x": 1}} and cost == pytest.approx(0.2) and list(errors) == ["b"]
    body = posted[0]
    assert list(body)[:2] == ["endpoint", "model"], "endpoint and model must precede requests"
    assert {r["custom_id"] for r in body["requests"]} == {"a", "b"} and deleted
    # cached request is not resubmitted
    again, cost2, _ = llm.batch("m", "sys", {"a": "ua"}, poll_seconds=0, log=lambda *a: None)
    assert again == {"a": {"x": 1}} and cost2 == 0.0 and len(posted) == 1


def test_llm_batch_raises_when_job_fails(offline_llm):
    offline_llm.setattr(llm.requests, "post", lambda url, **kw: FakeResponse({"id": "b2"}))
    offline_llm.setattr(llm.requests, "get", lambda url, **kw: FakeResponse({"id": "b2", "status": "failed", "error": "x"}))
    with pytest.raises(RuntimeError, match="batch ended failed"):
        llm.batch("m", "s", {"a": "u"}, poll_seconds=0, log=lambda *a: None)


# --- S1 end to end and S2 export -------------------------------------------------------------------------------

def _run(monkeypatch, tmp_path, books, fake, *args):
    es, ru = books
    monkeypatch.setattr(chapter_build, "ROOT", tmp_path)
    monkeypatch.setattr(chapter_build, "SOURCE", es)
    monkeypatch.setattr(chapter_build, "REFERENCE", ru)
    monkeypatch.setattr(llm, "complete", fake.complete)
    monkeypatch.setattr(llm, "batch", fake.batch)
    monkeypatch.setattr(sys, "argv", ["run_chapter.py", *args])
    run_chapter.main()
    return tmp_path / "build" / f"ch{int(args[0]):02d}"


def _lexicon(monkeypatch, tmp_path, books, fake):
    monkeypatch.setattr(lexicon_build, "ROOT", tmp_path)
    monkeypatch.setattr(lexicon_build, "SOURCE", books[0])
    monkeypatch.setattr(llm, "complete", fake.complete)
    run_lexicon.main()
    return tmp_path / "build" / "lexicon"


@needs_spacy
@pytest.mark.parametrize("mode", ["--sync", "--batch"])
def test_s1_end_to_end_chapter_build(monkeypatch, tmp_path, books, mode):
    out = _run(monkeypatch, tmp_path, books, FakeLLM(), "1", mode)
    assert all(s["lexical"] == [] for s in read_json(out / "chapter.json")["sentences"]), "lexical comments come later"
    lexicon = _lexicon(monkeypatch, tmp_path, books, FakeLLM())
    chapter, stats = read_json(out / "chapter.json"), read_json(out / "stats.json")
    assert stats["segment_problems"] == 0, "off-by-one segment ends are repaired"
    assert stats["total_cost"] > 0 and stats["lexical_comments"] > 20
    book = read_json(lexicon / "stats.json")
    assert book["chapters"] == [1] and book["groups"] > 5 and book["lexical_cost"] > 0 and book["unanswered_groups"] == 0
    title, first = chapter["sentences"][:2]
    assert title["sid"] == "c01p000s1" and title["paragraph"] == 0 and title["annotations"]["s"], "the title is annotated"
    assert first["sid"] == "c01p001s1" and first["annotations"]["s"][0][1] == len(first["tokens"]) - 1
    assert len(first["ws"]) == len(first["tokens"])
    lex = {l["lemma"]: l for s in chapter["sentences"] for l in s["lexical"]}
    assert lex["mirar"]["key"] == "mirar|VERB#1" and lex["mirar"]["form"].startswith("претерит")
    assert lex["embarcar"]["form"] == "инфинитив + nos от embarcar"
    casa = [l for s in chapter["sentences"] for l in s["lexical"] if l["lemma"] == "casa"]
    assert [l["ordinal"] for l in casa] == list(range(1, 21)), "occurrences numbered in book order and capped at 20"
    assert all(l["key"] == "casa|NOUN#1" for l in casa)
    assert not any(l["lemma"].startswith("x") for l in lex.values()), "single letters never get entries"
    assert (out / "preview.md").read_text().startswith("# Глава 1")


@needs_spacy
def test_s1_lexical_failure_is_split_and_retried(monkeypatch, tmp_path, books):
    out = _run(monkeypatch, tmp_path, books, FakeLLM(), "1", "--sync")
    monkeypatch.setattr(lexicon_build, "CALL_CHARS", 10 ** 6)  # one call with every group, so the split is needed
    fake = FakeLLM(fail_lexical_once_with="casa")
    _lexicon(monkeypatch, tmp_path, books, fake)
    assert fake.failed
    lex = [l for s in read_json(out / "chapter.json")["sentences"] for l in s["lexical"]]
    assert any(l["lemma"] == "casa" for l in lex), "the failed group is retried in a smaller call"


@needs_spacy
def test_s1_lexical_calls_disable_reasoning_and_include_literals(monkeypatch, tmp_path, books):
    _run(monkeypatch, tmp_path, books, FakeLLM(), "1", "--sync")
    fake = FakeLLM()
    _lexicon(monkeypatch, tmp_path, books, fake)
    lexical_calls = [c for c in fake.calls if c[0] == lexicon_build.LEXICAL_MODEL]
    assert lexical_calls and all(c[3] == {"reasoning": {"enabled": False}} for c in lexical_calls)
    assert all("=> перевод предложения" in c[2] for c in lexical_calls)
    # "-¿Qué? -respondió don Quijote-." is short: the next sentence travels in its unit
    line = next(l for c in lexical_calls for l in c[2].splitlines() if "[embarcarnos]" in l)
    assert "¿Qué?" in line


def test_s1_lexical_answer_without_part_suffix_is_taken_when_unambiguous():
    call = ["## no|ADV @2\n1. [No] => нет", "## casa|NOUN\n1. la [casa] => дом"]
    answers = lexicon_build.call_answers(call, {"no|ADV": {"senses": [{"id": "1", "dict": "D1"}], "occ": ["1"]}, "casa|NOUN": {}})
    assert set(answers) == {"no|ADV @2", "casa|NOUN"} and answers["no|ADV @2"]["senses"][0]["dict"] == "1"
    both = ["## no|ADV @1\n1. [No]", "## no|ADV @2\n1. [No]"]
    assert lexicon_build.call_answers(both, {"no|ADV": {}}) == {}, "two parts in one call: the answer is ambiguous"


@needs_spacy
def test_s1_groups_left_out_by_the_model_are_asked_again(monkeypatch, tmp_path, books):
    out = _run(monkeypatch, tmp_path, books, FakeLLM(), "1", "--sync")
    fake = FakeLLM(drop_once={"casa|NOUN"})
    lexicon = _lexicon(monkeypatch, tmp_path, books, fake)
    assert sum(1 for c in fake.calls if "## casa|NOUN" in c[2]) == 2
    assert read_json(lexicon / "stats.json")["unanswered_groups"] == 0
    assert any(l["lemma"] == "casa" for s in read_json(out / "chapter.json")["sentences"] for l in s["lexical"])


def test_s1_one_sense_per_occurrence_without_occ_is_read_as_assignments():
    answer = {"fix": None, "occ": [], "senses": [{"id": "1", "gloss": "картина", "dict": "2"}, {"id": "2", "gloss": "рама", "dict": "3"},
                                                 {"id": "3", "gloss": "картина, полотно", "dict": "2"}]}
    fixed = lexicon_build.per_occurrence(answer, 3)
    assert [s["gloss"] for s in fixed["senses"]] == ["картина", "рама"] and fixed["occ"] == ["1", "2", "1"]
    assert lexicon_build.complete(fixed, 3)
    assert lexicon_build.per_occurrence(answer, 4) is answer, "a different count is not this shape"


@needs_spacy
def test_s1_incomplete_answers_are_asked_again(monkeypatch, tmp_path, books):
    out = _run(monkeypatch, tmp_path, books, FakeLLM(), "1", "--sync")
    fake = FakeLLM(short_once={"casa|NOUN"})
    lexicon = _lexicon(monkeypatch, tmp_path, books, fake)
    assert sum(1 for c in fake.calls if "## casa|NOUN" in c[2]) == 2
    assert read_json(lexicon / "stats.json")["unassigned_occurrences"] == 0
    casa = [l for s in read_json(out / "chapter.json")["sentences"] for l in s["lexical"] if l["lemma"] == "casa"]
    assert len(casa) == 20


@needs_spacy
def test_s1_frequent_polysemous_word_is_split_into_parts_and_merged(monkeypatch, tmp_path, books):
    from conftest import stardict
    stardict(tmp_path / "build" / "dict" / "lingvo" / "UniversalEsRu",
             {"casa": "<k>casa</k>\n <abr>f</abr>\n 1) <dtrn>дом</dtrn>\n 2) <dtrn>семья</dtrn>"})
    _run(monkeypatch, tmp_path, books, FakeLLM(), "1", "--sync")
    _run(monkeypatch, tmp_path, books, FakeLLM(), "2", "--sync")
    monkeypatch.setattr(lexicon_build, "PART_OCCURRENCES", 10)
    fake = FakeLLM()
    _lexicon(monkeypatch, tmp_path, books, fake)
    headers = {h for c in fake.calls for h in re.findall(r"^## (casa\S* ?\S*)", c[2], re.M)}
    assert headers == {"casa|NOUN @1", "casa|NOUN @2", "casa|NOUN @3"}, "27 occurrences in parts of 10, all of them sent"
    casa = [l for n in (1, 2) for s in read_json(tmp_path / "build" / f"ch{n:02d}" / "chapter.json")["sentences"]
            for l in s["lexical"] if l["lemma"] == "casa"]
    assert len(casa) == 20 and casa[-1]["ordinal"] == 20 and {l["key"] for l in casa} == {"casa|NOUN#1"}


@needs_spacy
def test_s1_aligned_reference_goes_into_prompt_chapter_and_package(monkeypatch, tmp_path, books):
    out = _run(monkeypatch, tmp_path, books, FakeLLM(), "1", "--sync")
    # the title (paragraph 0) is aligned with nothing
    sids = [s["sid"] for s in read_json(out / "chapter.json")["sentences"] if s["paragraph"] > 0]
    # translator joined the first two sentences, left the third out, rendered the rest one by one
    # scores: the first and third groups are far below the chapter's usual score, so the segment model checks them
    groups = [{"es": sids[:2], "ru": [0], "score": 0.3}, {"es": [sids[2]], "ru": [], "score": None}]
    groups += [{"es": [sid], "ru": [k + 1], "score": 0.4 if k == 0 else 0.8 + 0.01 * (k % 3)} for k, sid in enumerate(sids[3:])]
    monkeypatch.setattr(published, "MIN_GROUPS_FOR_FIT", 5)
    ru = [{"paragraph": 1, "text": f"Русское {k}.", "notes": ["n_1"] if k == 0 else []} for k in range(len(sids) - 2)]
    (out / "alignment.json").write_text(json.dumps({"ru": ru, "groups": groups, "footnotes": {"n_1": "Сноска."}},
                                                   ensure_ascii=False))

    # the segment model's verdicts count only for checked groups: the first is not this text, the third is free,
    # and a "none" on an unchecked group is ignored
    fake = FakeLLM(verdicts={sids[1]: "none", sids[3]: "free", sids[5]: "none"})
    _run(monkeypatch, tmp_path, books, fake, "1", "--sync")
    segment_prompts = [c[2] for c in fake.calls if c[0] == chapter_build.SEGMENT_MODEL]
    assert segment_prompts and all("REFERENCE" not in p for p in segment_prompts)
    lines = segment_prompts[0].splitlines()
    assert lines[0].startswith("[c01p000s1] ") and lines[1].startswith(f"[{sids[0]}] "), "no RU line after the title"
    lines = lines[1:]
    assert [line.split(" ")[0] for line in lines[:5]] == [f"[{sids[0]}]", f"[{sids[1]}]", "RU?:", "NOTE:", f"[{sids[2]}]"]
    assert lines[2] == "RU?: Русское 0." and lines[3] == "NOTE: Сноска." and lines[5] == "RU: —"
    assert f"[{sids[3]}]" in lines[6] and lines[7] == "RU?: Русское 1." and lines[9] == "RU: Русское 2."

    chapter, stats = read_json(out / "chapter.json"), read_json(out / "stats.json")
    assert [s["ref"] for s in chapter["sentences"][:5]] == [None, 0, 0, 1, 2]
    first = chapter["references"][0]
    assert first["text"] == "Русское 0." and first["notes"] == ["n_1"] and first["check"] and first["z"] < -1.5
    assert first["loose"] is False and first["uncertain"] is True
    assert chapter["references"][1]["text"] is None and chapter["references"][2]["loose"] is True
    assert chapter["footnotes"] == {"n_1": "Сноска."}
    assert stats["references"] == len(sids) - 1 and stats["loose_references"] == 1 and stats["uncertain_references"] == 1
    assert stats["references_checked_by_model"] == 2
    fake = FakeLLM()
    _lexicon(monkeypatch, tmp_path, books, fake)
    lexical = [c[2] for c in fake.calls if c[0] == lexicon_build.LEXICAL_MODEL]
    assert any("| RU: Русское" in p for p in lexical), "the lexical pass sees the published translation too"
    assert any("| RU: —" in p for p in lexical), "and knows where the translator left a sentence out"
    assert "- перевод: Русское 0." in (out / "preview.md").read_text()

    doc = export_package.chapter_document(chapter)
    assert doc["references"][0] == {"text": "Русское 0.", "loose": False, "uncertain": True, "notes": ["n_1"]}
    assert doc["footnotes"] == [{"id": "n_1", "text": "Сноска.", "side": "reference"}]
    assert doc["paragraphs"][0]["heading"] is True and doc["paragraphs"][1]["sentences"][0]["ref"] == 0


@needs_spacy
def test_s1_lexical_pass_gets_dictionary_senses_and_reports_misses(monkeypatch, tmp_path, books):
    from conftest import stardict
    stardict(tmp_path / "build" / "dict" / "lingvo" / "UniversalEsRu",
             {"casa": "<k>casa</k>\n <abr>f</abr>\n 1) <dtrn>дом</dtrn>\n 2) <dtrn>семья</dtrn>"})
    _run(monkeypatch, tmp_path, books, FakeLLM(), "1", "--sync")
    fake = FakeLLM()
    lexicon = _lexicon(monkeypatch, tmp_path, books, fake)
    prompt = next(c[2] for c in fake.calls if c[0] == lexicon_build.LEXICAL_MODEL and "## casa|NOUN" in c[2])
    assert "DICT casa\nD1: дом (f)\nD2: семья (f)" in prompt
    stats = read_json(lexicon / "stats.json")
    assert stats["dictionary_groups"].startswith("1/") and stats["senses_outside_dictionary"] == 1
    assert read_json(lexicon / "dictionary_misses.json") == [{"key": "casa|NOUN", "headword": "casa", "gloss": "глосса casa", "dict": None}]


@needs_spacy
def test_s2_export_package_zip(monkeypatch, tmp_path, books):
    _run(monkeypatch, tmp_path, books, FakeLLM(), "1", "--sync")
    _run(monkeypatch, tmp_path, books, FakeLLM(), "2", "--sync")
    _lexicon(monkeypatch, tmp_path, books, FakeLLM())
    monkeypatch.setattr(export_package, "ROOT", tmp_path)
    monkeypatch.setattr(sys, "argv", ["export_package.py", "1", "2"])
    export_package.main()
    with zipfile.ZipFile(next((tmp_path / "build").glob("*.bbook.zip"))) as z:
        assert set(z.namelist()) == {"manifest.json", "chapters/001.json", "chapters/002.json"}
        manifest = json.loads(z.read("manifest.json"))
        assert manifest["schema"] == "1.5" and [c["index"] for c in manifest["chapters"]] == [1, 2]
        doc = json.loads(z.read("chapters/002.json"))
        assert doc["title"] == "II. Segundo" and doc["paragraphs"][0]["id"] == "c02p000"
        assert doc["paragraphs"][0]["heading"] is True and "heading" not in doc["paragraphs"][1]
        assert "".join(t + w for t, w in zip(doc["paragraphs"][0]["sentences"][0]["tokens"],
                                             doc["paragraphs"][0]["sentences"][0]["ws"])).strip() == "II. Segundo"
        export_package.check(manifest, "manifest")
        export_package.check(doc, "chapter")
        sentence = doc["paragraphs"][1]["sentences"][0]
        assert set(sentence) >= {"id", "tokens", "ws", "segments", "grammar", "context", "variants", "expressions", "lexical"}
