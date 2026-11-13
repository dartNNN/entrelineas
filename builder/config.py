"""Paths and book settings shared by the build scripts.

The book is not part of the code. It is described by books/book.json, or by the JSON file named in the environment
variable BB_BOOK, so several books can live side by side (BB_BOOK=books/cicero.json):

  {"source": "original.fb2", "reference": "translation.fb2",    file names inside books/ (FB2 or EPUB)
   "id": "...", "title": "...", "author": "...",                the package identity; id is also its file name
   "studied": "es", "support": "ru",                            the two languages
   "build": "build",                                            where this book's chapters and packages go
   "chapters": "^[IVXLC]+$",                                    optional: keep only sections with such titles
   "dictionaries": ["..."],                                     optional: StarDict names in build/dict/lingvo
   "reference_chapters": {"5": [5, 6]}}                         optional: translation chapters of an original one,
                                                                by hand (see builder/chapters.py)

BB_SOURCE and BB_REFERENCE (paths) override the two files. books/ is never committed. The dictionaries
(build/dict) and the model cache (build/cache) are shared by all books.
"""
import json
import os
import pathlib

ROOT = pathlib.Path(__file__).parent.parent
BOOKS = ROOT / "books"
SPACY_MODELS = {"es": "es_core_news_md", "en": "en_core_web_md"}
# Sense inventories per studied language, in priority order: StarDict files in build/dict/lingvo/<name> (local, the
# dictionaries are copyrighted). A book may name its own.
DICTIONARIES_FOR = {"es": ["UniversalEsRu", "ModernUsageEsRu", "LatinAmericanEsRu"], "en": ["LingvoUniversalEnRu", "LearningEnRu"]}
DEFAULTS = {"id": "book", "title": "Book", "author": "", "studied": "es", "support": "ru", "build": "build", "chapters": None,
            "dictionaries": None, "reference_chapters": None}


def _settings():
    path = pathlib.Path(os.environ["BB_BOOK"]) if os.environ.get("BB_BOOK") else BOOKS / "book.json"
    return json.loads(path.read_text()) if path.exists() else {}


def _file(given, kind, default):
    if os.environ.get(f"BB_{kind.upper()}"):
        return pathlib.Path(os.environ[f"BB_{kind.upper()}"])
    return BOOKS / given.get(kind, default)


_GIVEN = _settings()
SOURCE = _file(_GIVEN, "source", "source.fb2")
REFERENCE = _file(_GIVEN, "reference", "reference.fb2")
BOOK = {**DEFAULTS, **{k: v for k, v in _GIVEN.items() if k in DEFAULTS}}
BUILD = BOOK["build"]  # relative to ROOT: modules write to ROOT / BUILD, so tests that move ROOT move it too
SPACY_MODEL = SPACY_MODELS[BOOK["studied"]]
DICTIONARIES = BOOK["dictionaries"] or DICTIONARIES_FOR.get(BOOK["studied"], [])
