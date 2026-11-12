"""Demo book: one chapter of Don Quijote with V. A. Karelin's Russian translation, both in the public domain.

Downloads the Spanish text from Project Gutenberg (eBook #2000) and Karelin's translation (1866, 2nd ed. 1873) from
Russian Wikisource, cuts out one chapter of part I, brings Karelin's pre-1918 spelling to the modern one, and writes
books/quijote-es.fb2, books/quijote-ru.fb2 and, if there is none yet, books/book.json. Then build as usual:

  .venv/bin/python demo/quijote.py 8          # part I, chapter VIII: the windmills
  .venv/bin/python builder/align.py 1 && .venv/bin/python builder/run_chapter.py 1 \
    && .venv/bin/python builder/run_lexicon.py && .venv/bin/python builder/export_package.py 1

The chapter is chapter 1 of the demo book. Only the chapter's text is kept, none of Project Gutenberg's header or
licence, which the Gutenberg licence allows for public-domain texts. Karelin translates freely and sometimes
condenses, so the alignment leaves some sentences without a counterpart.
"""
import html
import json
import pathlib
import re
import sys
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
BOOKS = ROOT / "books"
GUTENBERG = "https://www.gutenberg.org/cache/epub/2000/pg2000.txt"
WIKISOURCE = ("https://ru.wikisource.org/w/index.php?action=raw&title="
              + urllib.parse.quote("Дон-Кихот Ламанчский (Сервантес; Карелин)/1873 (ДО)/Часть I".replace(" ", "_")))
ROMAN = ["I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII",
         "XVIII", "XIX", "XX", "XXI", "XXII", "XXIII", "XXIV", "XXV", "XXVI", "XXVII", "XXVIII", "XXIX", "XXX", "XXXI",
         "XXXII", "XXXIII", "XXXIV", "XXXV", "XXXVI", "XXXVII", "XXXVIII", "XXXIX", "XL", "XLI", "XLII", "XLIII",
         "XLIV", "XLV", "XLVI", "XLVII", "XLVIII", "XLIX", "L", "LI", "LII"]


def fetch(url, name):
    """The downloaded file, cached in books/ so a rerun needs no network."""
    path = BOOKS / name
    if not path.exists():
        request = urllib.request.Request(url, headers={"User-Agent": "entrelineas-demo/1.0"})
        path.write_bytes(urllib.request.urlopen(request, timeout=60).read())
    return path.read_text(encoding="utf-8")


def spanish_chapter(text, n):
    """(title, paragraphs) of chapter n of part I; paragraphs are blank-line separated, wrapped lines joined."""
    text = text.replace("\r\n", "\n")
    start = text.index("Primera parte del ingenioso hidalgo")
    heading = "Capítulo primero." if n == 1 else f"Capítulo {ROMAN[n - 1]}."
    at = text.index("\n" + heading, start) + 1
    end = re.compile(r"\n(Capítulo [IVXL]+\.|\w+ parte del ingenioso)").search(text, at + 1).start()
    blocks = [" ".join(b.split()) for b in text[at:end].split("\n\n") if b.strip()]
    return blocks[0], [re.sub(r"^— ", "—", b) for b in blocks[1:]]


def russian_chapter(wiki, n):
    """(title, paragraphs) of chapter n of part I in Karelin's translation, wiki markup and footnotes removed."""
    heads = list(re.finditer(r"^=+\s*Глава\s+([IVXL]+)\.\s*=+\s*$", wiki, re.M))
    i = next(k for k, h in enumerate(heads) if h.group(1) == ROMAN[n - 1])
    body = wiki[heads[i].end():heads[i + 1].start() if i + 1 < len(heads) else len(wiki)]
    body = re.sub(r"<ref[^>]*?/>|<ref[^>]*>.*?</ref>", "", body, flags=re.S)
    body = re.sub(r"\{\{[^{}]*\}\}", "", body)
    body = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", body)
    body = re.sub(r"<[^>]+>|'''?", "", body)
    paragraphs = [modern(" ".join(p.split())) for p in html.unescape(body).split("\n\n") if p.strip()]
    return f"Глава {ROMAN[n - 1]}", [p for p in paragraphs if not p.startswith("=")]


# Pre-1918 spelling to the modern one. The letters are certain; the endings and prefixes cover the regular cases, and
# what they miss stays readable.
LETTERS = str.maketrans({"ѣ": "е", "Ѣ": "Е", "і": "и", "І": "И", "ѳ": "ф", "Ѳ": "Ф", "ѵ": "и", "Ѵ": "И"})
VOICELESS = "кпстфхцчшщ"
KEEP = {"благо", "наго", "сантьяго", "яго", "чикаго"}  # words that end in -аго/-яго in modern spelling too


def modern(s):
    s = s.translate(LETTERS)
    s = re.sub(r"(?<=[бвгджзйклмнпрстфхцчшщБВГДЖЗЙКЛМНПРСТФХЦЧШЩ])[ъЪ]\b", "", s)  # Въ, домъ -> В, дом
    s = re.sub(r"\b(\w{2,})аго\b", lambda m: m.group(0) if m.group(0).lower() in KEEP else
               m.group(1) + ("его" if m.group(1)[-1] in "жшчщ" else "ого"), s)  # добраго -> доброго
    s = re.sub(r"\b(\w{2,})яго\b", lambda m: m.group(0) if m.group(0).lower() in KEEP else m.group(1) + "его", s)
    s = re.sub(r"\b(\w{2,})ыя\b", r"\1ые", s)                                 # добрыя -> добрые
    s = re.sub(r"\b(\w{2,}[кгх])ия\b", r"\1ие", s)                            # великия -> великие
    s = re.sub(r"\b([Бб]е|[Вв]о|[Ии]|[Нн]и|[Рр]а|[Чч]ре)з(?=[" + VOICELESS + r"]\w)", r"\1с", s)  # разсказ -> рассказ
    s = re.sub(r"\b([Оо]н|[Оо]дн)е\b", r"\1и", s)                             # оне, одне -> они, одни
    s = re.sub(r"\bея\b", "её", s)
    return re.sub(r"\bЕя\b", "Её", s)


def fb2(path, lang, title, paragraphs):
    esc = lambda t: html.escape(t, quote=False)
    body = f"<section><title><p>{esc(title)}</p></title>" + "".join(f"<p>{esc(p)}</p>" for p in paragraphs) + "</section>"
    path.write_text('<?xml version="1.0" encoding="utf-8"?>\n<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">'
                    f"<description><title-info><book-title>Don Quijote</book-title><lang>{lang}</lang></title-info>"
                    f"</description><body>{body}</body></FictionBook>", encoding="utf-8")


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 8
    BOOKS.mkdir(exist_ok=True)
    es_title, es = spanish_chapter(fetch(GUTENBERG, "pg2000.txt"), n)
    ru_title, ru = russian_chapter(fetch(WIKISOURCE, "karelin-part1.wiki"), n)
    fb2(BOOKS / "quijote-es.fb2", "es", es_title, es)
    fb2(BOOKS / "quijote-ru.fb2", "ru", ru_title, ru)
    config = BOOKS / "book.json"
    if not config.exists():
        config.write_text(json.dumps({"source": "quijote-es.fb2", "reference": "quijote-ru.fb2", "id": "don-quijote",
                                      "title": f"Don Quijote de la Mancha, capítulo {ROMAN[n - 1]}",
                                      "author": "Miguel de Cervantes"}, ensure_ascii=False, indent=1))
    words = lambda ps: sum(len(p.split()) for p in ps)
    print(f"{es_title[:60]}…: {len(es)} paragraphs, {words(es)} words; {ru_title}: {len(ru)} paragraphs, {words(ru)} words")
    print("books/book.json:", config.read_text() if config.exists() else "-")


if __name__ == "__main__":
    main()
