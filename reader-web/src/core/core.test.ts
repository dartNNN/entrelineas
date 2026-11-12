import { beforeAll, describe, expect, it } from 'vitest'
import { ACTION_COPY, ACTION_DEFAULTS, ACTION_KNOWN, ACTION_SHARE, ACTION_TRANSLATE, mergeActions, moveAction } from './actions'
import { buildBlocks, repeatBlocks, type Block } from './blocks'
import { loadBook, type Book, type Chapter } from './book'
import { anchorBlock, screens } from './screens'
import { pageTokens, sliceRuns, type Page } from './paginate'
import { fillTemplate, layerLanguage, layersOf, select, SelectionResolver } from './selection'
import { DEFAULTS, effectiveRepeat, resolveScheme, SCHEMES, type Settings } from './settings'
import { testBook } from '../test/fixture'

let book: Book
let chapter: Chapter
beforeAll(async () => {
  book = await loadBook(await testBook())
  chapter = await book.chapter(0)
})
const text = (b: Block) => b.runs.map((r) => r.text).join('')
const kinds = (s: Settings) => buildBlocks(chapter, s, new Set()).map((b) => b.kind)

describe('blocks (S4, S6, S7)', () => {
  it('puts glosses after their words and literal translations after their segments; the heading paragraph is the title', () => {
    const blocks = buildBlocks(chapter, DEFAULTS, new Set())
    expect(blocks[0]).toMatchObject({ kind: 'title', paragraph: 'c01p000' })
    const hinted = blocks.find((b) => b.kind === 'hinted')!
    expect(text(hinted)).toBe('Hola (Привет) mundo [мир]. (мир.) Adiós. ')
  })

  it('fades a gloss past the threshold or once its sense is familiar', () => {
    const hinted = (s: Settings, known: string[] = []) => text(buildBlocks(chapter, s, new Set(known)).find((b) => b.kind === 'hinted')!)
    expect(hinted({ ...DEFAULTS, threshold: 12 })).toContain('[прощай]')
    expect(hinted(DEFAULTS)).not.toContain('[прощай]')
    expect(hinted(DEFAULTS, ['mundo|NOUN#1'])).not.toContain('[мир]')
  })

  it('switches each part off, repeats per paragraph, and not at all when every hint is off', () => {
    expect(kinds(DEFAULTS)).toEqual(['title', 'plain', 'hinted', 'notes', 'authorNotes', 'plain'])
    expect(kinds({ ...DEFAULTS, showNotes: false, showAuthorNotes: false, repeat: 'off' })).toEqual(['title', 'hinted'])
    const bare = { ...DEFAULTS, showLiteral: false, showLexical: false, showNotes: false }
    expect(effectiveRepeat(bare)).toBe('off')
    expect(kinds(bare)).not.toContain('plain')
    expect(effectiveRepeat({ ...DEFAULTS, repeat: 'screen' })).toBe('screen')
  })

  it('lists the published translation once per group, marked, with its footnotes', () => {
    const blocks = buildBlocks(chapter, { ...DEFAULTS, referenceInText: true }, new Set())
    expect(blocks.filter((b) => b.kind === 'reference').map(text)).toEqual(['Привет, мир.\n* Первая программа.'])
    const noNotes = buildBlocks(chapter, { ...DEFAULTS, referenceInText: true, showFootnotes: false }, new Set())
    expect(noNotes.filter((b) => b.kind === 'reference').map(text)).toEqual(['Привет, мир.'])
  })

  it('builds the hint-free screen from exactly the words of a screen, a block per paragraph', () => {
    const sentences = new Map(chapter.paragraphs.flatMap((p) => p.sentences.map((s) => [s.id, s] as const)))
    const blocks = repeatBlocks(['c01p000s1:2', 'c01p001s1:0', 'c01p001s1:1', 'c01p001s1:1', 'c01p001s1:2'], sentences)
    expect(blocks.map((b) => [b.paragraph, text(b)])).toEqual([['c01p000', 'prueba'], ['c01p001', 'Hola mundo.']])
  })
})

describe('screens (S4, S7.5)', () => {
  const sentences = () => new Map(chapter.paragraphs.flatMap((p) => p.sentences.map((s) => [s.id, s] as const)))
  const blocks = () => buildBlocks(chapter, DEFAULTS, new Set())
  const hinted = () => blocks().findIndex((b) => b.kind === 'hinted')
  // A page with the title and the hinted paragraph cut after "Hola (Привет) mun|do", and the next with the rest.
  const pages = (): Page[] => [
    { paragraph: 'c01p000', fragments: [{ block: 0, start: 0, end: 18, cont: false, hyphen: false }, { block: hinted(), start: 0, end: 17, cont: true, hyphen: true }] },
    { paragraph: 'c01p001', fragments: [{ block: hinted(), start: 17, end: 1000, cont: false, hyphen: false }] },
  ]

  it('cuts blocks into fragments and gives a hyphenated word to the page where it begins', () => {
    expect(sliceRuns(blocks()[hinted()], 0, 17).map((r) => r.text).join('')).toBe('Hola (Привет) mun')
    expect(pageTokens(pages()[0], blocks())).toEqual(['c01p000s1:0', 'c01p000s1:1', 'c01p000s1:2', 'c01p001s1:0', 'c01p001s1:1'])
    expect(pageTokens(pages()[1], blocks())).toEqual(['c01p001s1:2', 'c01p001s2:0', 'c01p001s2:1'])
  })

  it('follows every page with its hint-free twin in screen repeat', () => {
    expect(screens(pages(), blocks(), false, sentences()).map((s) => s.kind)).toEqual(['page', 'page'])
    const list = screens(pages(), blocks(), true, sentences())
    expect(list.map((s) => `${s.kind}${s.page}`)).toEqual(['page0', 'repeat0', 'page1', 'repeat1'])
    const twin = list[1].kind === 'repeat' ? list[1].blocks : []
    expect(twin.map((b) => b.runs.map((r) => r.text).join(''))).toEqual(['Capítulo de prueba', 'Hola mundo'])
  })

  it('starts the layout at the saved paragraph, or at the chapter start', () => {
    const b = blocks()
    expect(anchorBlock(b, '')).toBe(0)
    expect(anchorBlock(b, 'c01p000')).toBe(0)
    expect(b[anchorBlock(b, 'c01p001')].paragraph).toBe('c01p001')
  })
})

describe('selection (S10)', () => {
  const resolver = () => new SelectionResolver(book, chapter)

  it('carries every field of a word: gloss, literal, published translation, notes, languages', () => {
    const c = resolver().content(select('c01p001s1', 1))
    expect(c).toMatchObject({ original: 'mundo', literal: 'мир.', reference: 'Привет, мир.', gloss: 'мир', key: 'mundo|NOUN#1', sentence: 'Hola mundo.' })
    expect(c.notes).toContain('Существительное мужского рода.')
    expect(layersOf(c)).toEqual(['original', 'literal', 'reference', 'gloss', 'note'])
    expect([layerLanguage(c, 'original'), layerLanguage(c, 'reference'), layerLanguage(c, 'gloss')]).toEqual(['es', 'ru', 'ru'])
  })

  it('takes a segment, a sentence with the next ones, a paragraph', () => {
    const r = resolver()
    expect([...r.tokens(select('c01p001s1', 2, { unit: 'segment' }))]).toEqual(['c01p001s1:1', 'c01p001s1:2'])
    expect(r.content(select('c01p001s1', 0, { unit: 'sentence', more: 1 })).original).toBe('Hola mundo. Adiós.')
    expect([...r.paragraphs(select('c01p001s2', 0, { unit: 'paragraph' }))]).toEqual(['c01p001'])
  })

  it('offers "segment" only where the sentence has segments', () => {
    expect(resolver().units(select('c01p001s1', 1))).toEqual(['word', 'segment', 'sentence', 'paragraph'])
    expect(resolver().units(select('c01p001s2', 0))).toEqual(['word', 'sentence', 'paragraph'])
  })

  it('fills templates and drops lines with nothing in them', () => {
    const word = resolver().content(select('c01p001s1', 1))
    expect(fillTemplate('{original} — {gloss}\\n{sentence}', word, 'original')).toBe('mundo — мир\nHola mundo.')
    expect(fillTemplate('{text}\\n{lemma}', resolver().content(select('c01p001s2', 0, { unit: 'sentence' })), 'original')).toBe('Adiós.')
    expect(fillTemplate('{unknown} {original}', word, 'original')).toBe('{unknown} mundo')
  })
})

describe('actions and schemes (S9, S10)', () => {
  it('puts the reader’s own actions first, keeps saved order and settings, appends new ones', () => {
    expect(mergeActions([]).map((c) => c.id)).toEqual(ACTION_DEFAULTS.map((c) => c.id))
    expect(mergeActions([]).filter((c) => c.pinned).map((c) => c.id)).toEqual([ACTION_KNOWN, ACTION_COPY])
    const saved = [{ id: ACTION_SHARE, enabled: true, pinned: true, template: '{text}' }, { id: 'gone', enabled: true, pinned: false, template: '' },
      { id: ACTION_COPY, enabled: false, pinned: false, template: '{text}' }]
    const merged = mergeActions(saved)
    expect(merged.map((c) => c.id)).toEqual([ACTION_SHARE, ACTION_COPY, ACTION_KNOWN, ACTION_TRANSLATE])
    expect(merged[1].enabled).toBe(false)
    expect(moveAction(merged, 1, -1).map((c) => c.id).slice(0, 2)).toEqual([ACTION_COPY, ACTION_SHARE])
    expect(moveAction(merged, 0, -1)).toBe(merged)
  })

  it('follows the system for the "as in the system" scheme and uses the Android colours', () => {
    expect(resolveScheme('system', true)).toBe(SCHEMES.dusk)
    expect(resolveScheme('system', false)).toBe(SCHEMES.paper)
    expect(SCHEMES.paper.page).toBe('#F7F3EA')
  })
})
