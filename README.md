# Entrelíneas

Bilingual reading in the manner of Ilya Frank's books: the original text with a literal translation of each sense group inserted right after it, short glosses on words, grammar and context notes, and the published literary translation one tap away. Hints fade out: each word sense is glossed only for its first N occurrences in the book, so the further you read, the cleaner the text gets.

The project builds such books from an original and its translation, and reads them on Android and in the browser. It is set up for Spanish with Russian as the support language, but nothing in the format is tied to that pair.

No books are included apart from a demo. You bring the texts (and, optionally, dictionaries); the builder turns them into a `.bbook.zip` package that both readers open.

## Demo

**[Open the demo in the browser](https://dartnnn.github.io/entrelineas/?book=don-quijote.bbook.zip)**: no installation, nothing is uploaded.

[`demo/don-quijote.bbook.zip`](demo/don-quijote.bbook.zip) is one chapter of *Don Quijote* (part I, chapter VIII, the windmills) with the Russian translation by V. A. Karelin (1866), both in the public domain. Open it in either reader to see what a built book looks like. It was built without dictionaries, so the glosses are the model's own; it cost about $0.47 (November 2026).

`demo/quijote.py` makes the source files for any chapter of part I: it downloads the Spanish text from [Project Gutenberg](https://www.gutenberg.org/ebooks/2000) and Karelin's translation from [Wikisource](https://ru.wikisource.org/wiki/Дон-Кихот_Ламанчский_(Сервантес;_Карелин)/1873_(ДО)/Часть_I), cuts out the chapter and brings the pre-1918 Russian spelling to the modern one. Karelin translates freely and sometimes condenses, so some sentences of the original have no counterpart.

## Parts

| Directory | What | Stack |
|---|---|---|
| `builder/` | Builds book packages from two FB2 files: segmentation, literal translation, glosses, notes | Python 3.12+, spaCy, OpenRouter |
| `reader/` | Android reader | Kotlin, Jetpack Compose, Android 13+ |
| `reader-web/` | Web reader with the same features, runs entirely in the browser | React, TypeScript, Vite |
| `format/` | The package format: JSON Schemas shared by all three, plus fixtures | JSON Schema 2020-12 |

The format is defined once in `format/*.schema.json`. The builder validates what it writes against it, and both readers validate what they read, with tests on both sides against the same fixtures, so the three cannot drift apart.

## Building a book

### What you need

- The original and a translation as FB2, with the same chapters in the same order. Put them in `books/` (ignored by git) and name them in `books/book.json`:

  ```json
  {"source": "original.fb2", "reference": "translation.fb2",
   "id": "don-quijote", "title": "Don Quijote de la Mancha", "author": "Miguel de Cervantes"}
  ```

  `id` becomes the package name, `build/<id>.bbook.zip`. Optional keys `studied` and `support` (default `es` and `ru`) name the two languages.
- For the model build, an [OpenRouter](https://openrouter.ai) key in `OPENROUTER_API_KEY`.
- Optionally, bilingual dictionaries in StarDict format with Lingvo-style XDXF markup, as `build/dict/lingvo/<name>.{ifo,idx,dict}` (the names are in `DICTIONARIES` in `builder/lexicon_build.py` and `builder/build_offline.py`). With a dictionary the model picks a sense from the entry for each occurrence; without one it writes its own glosses.

### Setup

```sh
./setup-env.sh              # .venv with the builder, spaCy's es_core_news_md, the aligner and the tests
./setup-env.sh --no-align   # without the aligner (no PyTorch)
```

### Steps

```sh
source ~/.config/openrouter.env                # export OPENROUTER_API_KEY=...
.venv/bin/python builder/align.py 1            # pair the chapter's sentences with the translation (LaBSE, CPU)
.venv/bin/python builder/run_chapter.py 1      # segment pass, strong model, batch mode (--sync for direct calls)
.venv/bin/python builder/run_lexicon.py        # lexical pass over every built chapter, cheap model
.venv/bin/python builder/export_package.py 1   # build/<id>.bbook.zip with the listed chapters
```

`run_chapter.py` writes `build/chNN/chapter.json` and a readable `preview.md`. The lexical pass sends every word of the book once, with all its occurrences in context and its dictionary entry, and numbers occurrences per sense across the book. Model answers are cached in `build/cache`, so a rerun with unchanged prompts costs nothing.

Models (`builder/chapter_build.py`, `builder/lexicon_build.py`): GPT-6.1 Sol for the segment pass and DeepSeek V4.1 Flash for the lexical pass. Measured on a 108,000-word novel in November 2026: about $0.76 per chapter of 11,000 words for the segment pass in batch mode, about $2 for the lexical pass over the whole book; roughly $10 per book. Prices change, so treat this as an order of magnitude.

### Without a model

```sh
.venv/bin/python builder/build_offline.py 1              # needs the alignment and dictionaries, no API key
.venv/bin/python builder/export_package.py --offline 1   # build/<id>-offline.bbook.zip
```

This gives the original, the published translation per sentence, footnotes and a dictionary gloss for every content word, with the sense chosen by overlap with the translation. There are no literal translations or notes: those need a model.

## Reading

**Web:** the reader is published at https://dartnnn.github.io/entrelineas/ (the `gh-pages` branch: the built site and the demo book). Open a `.bbook.zip` from your disk, or link to a published one with `?book=<url>` (the server must allow cross-origin reads). Locally: `cd reader-web && npm ci && npm run dev`; `npm run build` gives a static site in `dist/`. Books are read in the browser and never uploaded.

**Android:** `cd reader && gradle assembleDebug`, install `app/build/outputs/apk/debug/app-debug.apk`, then open a `.bbook.zip` from the file picker. The release build is signed with the debug key: set up your own signing before distributing it.

Both readers paginate the text with the hints in place, let you choose which hints to show (literal translation, glosses, notes, the published translation after the paragraph or in the word card), repeat each screen or paragraph without hints, mark senses as known, and send a selection to other apps (an Anki note, a dictionary, text-to-speech) through configurable actions.

## Tests

```sh
./test.sh --no-emulator   # builder (pytest), Android JVM tests, web unit tests, lint and Playwright
./test.sh                 # plus the Android instrumented tests on an emulator named reno5
```

Instrumented tests replace the app and its data, so run them on an emulator only. Test names follow the use cases: S1 build a chapter, S1a align the translation, S1b audit prompt tokens, S2 export a package, S3 open a book, S4 read, S5 word card, S6 mark as known, S7 settings, S8 chapters, S9 hidden controls, S10 act on a selection, S11 build without a model, S12 read in the browser. All test data is synthetic or taken from *Don Quijote*, which is in the public domain.

## Texts and copyright

The code is public domain (see `LICENSE`). Books and dictionaries are not part of it, and a package built from a copyrighted book contains its text and its translation: keep such packages to yourself. A package built with commercial dictionaries also contains their entries.

## License

[The Unlicense](LICENSE): do whatever you want with the code.
