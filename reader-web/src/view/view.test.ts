import { beforeAll, describe, expect, it } from 'vitest'
import { buildBlocks } from '../core/blocks'
import { loadBook, type Book, type Chapter } from '../core/book'
import { select, SelectionResolver } from '../core/selection'
import { DEFAULTS } from '../core/settings'
import { testBook } from '../test/fixture'
import { painted } from './fragment'
import { bookPercent, chapterPercent } from './progress'

let book: Book
let chapter: Chapter
beforeAll(async () => {
  book = await loadBook(await testBook())
  chapter = await book.chapter(0)
})

describe('selection painting (S10)', () => {
  const resolver = () => new SelectionResolver(book, chapter)
  it('paints only the chosen layer, as one band', () => {
    const hinted = buildBlocks(chapter, DEFAULTS, new Set()).find((b) => b.kind === 'hinted')!
    const r = resolver()
    const marked = (sel: ReturnType<typeof select>) => hinted.runs.filter((_, i) => painted(hinted, r.highlight(sel))[i]).map((x) => x.text).join('|')
    expect(marked(select('c01p001s1', 1))).toBe('mundo')
    expect(marked(select('c01p001s1', 1, { layer: 'gloss' }))).toBe(' [мир]')
    expect(marked(select('c01p001s1', 1, { layer: 'literal' }))).toBe(' (мир.)')
    expect(marked(select('c01p001s1', 0, { unit: 'sentence' }))).toBe('Hola|mundo|.')
    const plain = buildBlocks(chapter, DEFAULTS, new Set()).find((b) => b.kind === 'plain' && b.paragraph === 'c01p001')!
    expect(plain.runs.filter((_, i) => painted(plain, r.highlight(select('c01p001s1', 0, { unit: 'sentence' })))[i]).map((x) => x.text).join('|'))
      .toBe('Hola| |mundo||.')
    const notes = buildBlocks(chapter, DEFAULTS, new Set()).find((b) => b.kind === 'notes')!
    expect(painted(notes, r.highlight(select('c01p001s1', 0, { unit: 'paragraph', layer: 'note' })))).toEqual([true])
  })
})

describe('progress (S9)', () => {
  it('reports chapter and book progress', () => {
    const p = { page: 4, pages: 10, chapterIndex: 1, chapters: 4, chapterTitle: '' }
    expect([chapterPercent(p), bookPercent(p)]).toEqual([50, 37])
  })
})
