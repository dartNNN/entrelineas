"""Paths shared by the build scripts.

The book is not part of the code: the original and the published translation (FB2) are named in books/book.json
({"source": "...", "reference": "...", "id": "...", "title": "...", "author": "..."}, file names inside books/), or by
the environment variables BB_SOURCE and BB_REFERENCE (paths), else books/source.fb2 and books/reference.fb2. books/ is
never committed. BOOK holds the package identity: id (also the package file name), title, author, studied and support
languages.
"""
import json
import os
import pathlib

ROOT = pathlib.Path(__file__).parent.parent
BOOKS = ROOT / "books"


def _book(kind, default):
    if os.environ.get(f"BB_{kind.upper()}"):
        return pathlib.Path(os.environ[f"BB_{kind.upper()}"])
    named = BOOKS / "book.json"
    if named.exists():
        return BOOKS / json.loads(named.read_text())[kind]
    return BOOKS / default


def _identity():
    named = BOOKS / "book.json"
    given = json.loads(named.read_text()) if named.exists() else {}
    book = {"id": "book", "title": "Book", "author": "", "studied": "es", "support": "ru"}
    book.update({k: v for k, v in given.items() if k in book})
    return book


SOURCE = _book("source", "source.fb2")
REFERENCE = _book("reference", "reference.fb2")
BOOK = _identity()
