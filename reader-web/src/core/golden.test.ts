import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { beforeAll, describe, expect, it } from 'vitest'
import { buildBlocks } from './blocks'
import { loadBook, type Book, type Chapter } from './book'
import { select, SelectionResolver, type TextSelection } from './selection'
import { DEFAULTS, type Settings } from './settings'
import { testBook } from '../test/fixture'

/**
 * Golden results on the shared fixture (format/fixtures/expected.json), checked by this reader and by the Android one
 * (GoldenTest.kt): the blocks a chapter shows under several settings, run by run (word, gloss, literal translation,
 * plain text), and what selections resolve to. Both readers
 * port the same logic; this is where they must agree. GOLDEN_WRITE=1 npm test rewrites the file from this reader.
 */
const FILE = new URL('../../../format/fixtures/expected.json', import.meta.url)

const SETTINGS: Record<string, Partial<Settings>> = {
  defaults: {},
  referenceInText: { referenceInText: true },
  noHints: { showLiteral: false, showLexical: false, showNotes: false },
  thresholdNoRepeat: { threshold: 12, repeat: 'off', showAuthorNotes: false },
}
const SELECTIONS: Record<string, TextSelection> = {
  word: select('c01p001s1', 1),
  segment: select('c01p001s1', 2, { unit: 'segment' }),
  sentenceAndNext: select('c01p001s1', 0, { unit: 'sentence', more: 1 }),
  paragraph: select('c01p001s2', 0, { unit: 'paragraph' }),
  plainWord: select('c01p001s2', 0),
}

let book: Book
let chapter: Chapter
beforeAll(async () => { book = await loadBook(await testBook()); chapter = await book.chapter(0) })

function compute() {
  const blocks = Object.fromEntries(Object.entries(SETTINGS).map(([name, patch]) => [name,
    buildBlocks(chapter, { ...DEFAULTS, ...patch }, new Set(name === 'thresholdNoRepeat' ? ['mundo|NOUN#1'] : []))
      .map((b) => ({ kind: b.kind, runs: b.runs.map((r) => [r.kind, r.text]) }))]))
  const resolver = new SelectionResolver(book, chapter)
  const selections = Object.fromEntries(Object.entries(SELECTIONS).map(([name, sel]) => {
    const c = resolver.content(sel)
    return [name, { original: c.original, literal: c.literal, reference: c.reference, lemma: c.lemma, gloss: c.gloss, key: c.key,
      notes: c.notes, sentence: c.sentence, units: resolver.units(sel), tokens: [...resolver.tokens(sel)].sort() }]
  }))
  return { blocks, selections }
}

describe('golden results shared with the Android reader', () => {
  it('match format/fixtures/expected.json', () => {
    const actual = compute()
    if (process.env.GOLDEN_WRITE || !existsSync(FILE)) writeFileSync(FILE, JSON.stringify(actual, null, 1) + '\n')
    expect(actual).toEqual(JSON.parse(readFileSync(FILE, 'utf8')))
  })
})
