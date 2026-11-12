"""Shared fixtures: synthetic FB2 books and a fake OpenRouter."""
import json
import pathlib
import re

import pytest


FB2_HEAD = '<?xml version="1.0" encoding="utf-8"?>\n<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">' \
           '<description><title-info><book-title>T</book-title><lang>{lang}</lang></title-info></description><body>'
FB2_TAIL = "</body></FictionBook>"


def fb2(path, lang, chapters):
    """chapters: list of (title, [paragraph, ...])."""
    body = "".join(
        "<section><title><p>{}</p></title>{}</section>".format(t, "".join(f"<p>{p}</p>" for p in ps))
        for t, ps in chapters)
    path.write_text(FB2_HEAD.format(lang=lang) + body + FB2_TAIL, encoding="utf-8")
    return path


SPANISH = [
    ("I. Primero", [
        "Don Quijote miró a su contendor.",
        "-¿Qué? -respondió don Quijote-. Santiguarnos y levar ferro; quiero decir, embarcarnos y cortar la amarra con que este barco está atado.",
        "Las fotografías con rayos X mostraban la casa.",
    ] + [f"La casa número {n} era grande." for n in range(25)]),
    ("II. Segundo", ["Otra casa pequeña.", "El caballero miraba los molinos."]),
]
RUSSIAN = [
    ("1. ПЕРВАЯ", ["Дон Кихот посмотрел на противника.", "— Что? — ответил Дон Кихот. — Перекреститься и поднять якорь, то есть сесть в лодку и перерезать канат."] + ["Дом был большим."] * 26),
    ("2. ВТОРАЯ", ["Ещё один маленький дом.", "Рыцарь смотрел на мельницы."]),
]


@pytest.fixture
def books(tmp_path):
    return fb2(tmp_path / "es.fb2", "es", SPANISH), fb2(tmp_path / "ru.fb2", "ru", RUSSIAN)


class FakeLLM:
    """Deterministic stand-in for llm.complete / llm.batch; records calls."""

    def __init__(self, fail_lexical_once_with=None, verdicts=None, drop_once=None, short_once=None):
        self.calls = []
        self.short_once = set(short_once or ())  # lexical groups answered with one occurrence missing, once
        self.drop_once = set(drop_once or ())  # lexical groups left out of the first answer that includes them
        self.verdicts = verdicts or {}
        self.fail_marker = fail_lexical_once_with
        self.failed = False

    def complete(self, model, system, user, max_tokens=16000, retries=2, extra=None):
        self.calls.append((model, system[:20], user, extra))
        if "learner's dictionary" in system:
            groups = re.findall(r"^## (\S+)", user, re.M)
            if self.fail_marker and not self.failed and len(groups) > 1 and any(g.startswith(self.fail_marker) for g in groups):
                self.failed = True
                raise RuntimeError("simulated provider failure")
            result = {}
            for block in user.split("\n\n"):
                lines = block.strip().split("\n")
                if not lines[0].startswith("## "):
                    continue
                key = lines[0][3:]
                if key in self.drop_once:
                    self.drop_once.discard(key)
                    continue
                n = sum(1 for line in lines if re.match(r"\d+\. ", line))
                if key.startswith("x"):
                    result[key] = {"skip": True}
                else:
                    short = key in self.short_once
                    self.short_once.discard(key)
                    result[key] = {"fix": None, "senses": [{"id": "1", "gloss": f"глосса {key.split('|')[0]}", "gram": "м."}],
                                   "occ": ["1"] * (n - 1 if short else n)}
            return result, 0.001, False
        out = {}
        for line in user.split("\n"):
            m = re.match(r"\[(\S+)\] (.*)", line)
            if not m:
                continue
            n = len(m.group(2).split("|"))
            # one index past the end on purpose: the pipeline must repair it
            out[m.group(1)] = {"s": [[0, n, "перевод предложения", None]], "g": [[0, 0, "topic", "заметка"]],
                               "c": [], "v": [], "e": [], "r": self.verdicts.get(m.group(1), "close")}
        return out, 0.01, False

    def batch(self, model, system, users, max_tokens=32000, poll_seconds=30, log=print):
        results, cost = {}, 0.0
        for cid, user in users.items():
            r, c, _ = self.complete(model, system, user, max_tokens)
            results[cid] = r
            cost += c
        return results, cost, {}


def spacy_model_available():
    try:
        import spacy
        spacy.load("es_core_news_md")
        return True
    except Exception:
        return False


needs_spacy = pytest.mark.skipif(not spacy_model_available(), reason="es_core_news_md not installed")


def stardict(base, entries):
    """Writes an uncompressed StarDict dictionary: entries is {headword: XDXF text}."""
    import struct
    base = pathlib.Path(base)
    base.parent.mkdir(parents=True, exist_ok=True)
    data, idx = b"", b""
    for word in sorted(entries):
        body = entries[word].encode()
        idx += word.encode() + b"\0" + struct.pack(">II", len(data), len(body))
        data += body
    base.with_suffix(".dict").write_bytes(data)
    base.with_suffix(".idx").write_bytes(idx)
    base.with_suffix(".ifo").write_text(f"StarDict's dict ifo file\nversion=2.4.2\nwordcount={len(entries)}\nbookname=T\n")
    return base


def read_json(path):
    return json.loads(pathlib.Path(path).read_text())
