import type { Book, Chapter, Segment, Sentence } from './book'

/**
 * Selection by units of the annotated text, not by characters, as in the Android reader (Selection.kt): a rendered
 * line mixes the original with bracketed glosses and literal translations, so a character range would cut across
 * layers. A selection is an anchor word, a unit around it, how many following sentences or paragraphs it takes in,
 * and the layer to act on. It resolves to a [SelectionContent] that carries every field at once.
 */
export type SelectionUnit = 'word' | 'segment' | 'sentence' | 'paragraph'
export const UNIT_TITLES: Record<SelectionUnit, string> = { word: 'Слово', segment: 'Сегмент', sentence: 'Предложение', paragraph: 'Абзац' }
export const UNITS: SelectionUnit[] = ['word', 'segment', 'sentence', 'paragraph']

export type Layer = 'original' | 'literal' | 'reference' | 'gloss' | 'note'
export const LAYER_TITLES: Record<Layer, string> = { original: 'Оригинал', literal: 'Дословно', reference: 'Перевод', gloss: 'Подсказка', note: 'Заметки' }
export const LAYERS: Layer[] = ['original', 'literal', 'reference', 'gloss', 'note']

/** A tap on a word: opens its card. */
export interface WordTap { sentence: string; token: number }

export interface TextSelection {
  sentence: string
  token: number
  unit: SelectionUnit
  /** Following sentences (unit sentence) or paragraphs (unit paragraph) taken in as well. */
  more: number
  layer: Layer
}

export const select = (sentence: string, token: number, patch: Partial<TextSelection> = {}): TextSelection =>
  ({ sentence, token, unit: 'word', more: 0, layer: 'original', ...patch })

export interface SelectionContent {
  original: string
  literal: string
  reference: string
  lemma: string
  gloss: string
  form: string
  /** Familiar-mark key of the word's sense; empty unless a single word with a gloss is selected. */
  key: string
  notes: string
  sentence: string
  chapter: string
  book: string
  /** Language codes from the manifest: the original's and the one of translations and notes. */
  studied: string
  support: string
}

export function layerText(c: SelectionContent, layer: Layer): string {
  switch (layer) {
    case 'original': return c.original
    case 'literal': return c.literal
    case 'reference': return c.reference
    case 'gloss': return [c.lemma, c.gloss].filter((x) => x.trim()).join(' — ')
    case 'note': return c.notes
  }
}

/** Language a layer is written in: the original is in the studied language, everything else in the support one. */
export const layerLanguage = (c: SelectionContent, layer: Layer) => (layer === 'original' ? c.studied : c.support)

/** Layers that have text for this selection, in display order. */
export const layersOf = (c: SelectionContent): Layer[] => LAYERS.filter((l) => l === 'original' || layerText(c, l).trim())

/** What the page paints for the active selection: words, the paragraphs of notes and translations, the layer. */
export interface Highlight { tokens: ReadonlySet<string>; paragraphs: ReadonlySet<string>; layer: Layer }

/** Resolves selections against one chapter of a book. */
export class SelectionResolver {
  private readonly book: Book
  private readonly chapter: Chapter
  private readonly sentences: Sentence[]
  private readonly paragraphOf = new Map<string, string>()

  constructor(book: Book, chapter: Chapter) {
    this.book = book
    this.chapter = chapter
    this.sentences = chapter.paragraphs.flatMap((p) => p.sentences)
    for (const p of chapter.paragraphs) for (const s of p.sentences) this.paragraphOf.set(s.id, p.id)
  }

  private anchor(sel: TextSelection): Sentence {
    return this.sentences.find((s) => s.id === sel.sentence) ?? this.sentences[0]
  }

  /** Sentences the selection covers, in order; word and segment stay inside the anchor sentence. */
  private covered(sel: TextSelection): Sentence[] {
    const at = Math.max(0, this.sentences.findIndex((s) => s.id === sel.sentence))
    switch (sel.unit) {
      case 'word': case 'segment': return [this.sentences[at]]
      case 'sentence': return this.sentences.slice(at, at + 1 + sel.more)
      case 'paragraph': {
        const pars = this.chapter.paragraphs
        const first = pars.findIndex((p) => p.id === this.paragraphOf.get(this.sentences[at].id))
        return pars.slice(first, first + 1 + sel.more).flatMap((p) => p.sentences)
      }
    }
  }

  private segmentOf(s: Sentence, token: number): Segment {
    return s.segments.find((g) => token >= g.first && token <= g.last) ?? { first: 0, last: s.tokens.length - 1, literal: '', literary: null }
  }

  /** Units offered for a selection: "segment" only where the sentence has segments (a book built without a model has none). */
  units(sel: TextSelection): SelectionUnit[] {
    return UNITS.filter((u) => u !== 'segment' || this.anchor(sel).segments.length > 0)
  }

  /** Selected tokens as "sentence:token"; what the page highlights for the original-based layers. */
  tokens(sel: TextSelection): Set<string> {
    const covered = this.covered(sel)
    if (sel.unit === 'word') return new Set([`${sel.sentence}:${sel.token}`])
    if (sel.unit === 'segment') {
      const seg = this.segmentOf(covered[0], sel.token)
      return new Set(Array.from({ length: seg.last - seg.first + 1 }, (_, k) => `${sel.sentence}:${seg.first + k}`))
    }
    return new Set(covered.flatMap((s) => s.tokens.map((_, i) => `${s.id}:${i}`)))
  }

  /** Paragraphs the selection touches; notes and published translation blocks of these are highlighted. */
  paragraphs(sel: TextSelection): Set<string> {
    return new Set(this.covered(sel).map((s) => this.paragraphOf.get(s.id)).filter((p): p is string => !!p))
  }

  highlight(sel: TextSelection): Highlight {
    return { tokens: this.tokens(sel), paragraphs: this.paragraphs(sel), layer: sel.layer }
  }

  content(sel: TextSelection): SelectionContent {
    const covered = this.covered(sel)
    const anchor = this.anchor(sel)
    const range = (s: Sentence): [number, number] => {
      if (sel.unit === 'word') return [sel.token, sel.token]
      if (sel.unit === 'segment') { const g = this.segmentOf(s, sel.token); return [g.first, g.last] }
      return [0, s.tokens.length - 1]
    }
    const literal = sel.unit === 'word' || sel.unit === 'segment'
      ? (() => { const g = this.segmentOf(anchor, sel.token); return [g.literal.trim() ? g.literal : null, g.literary ? `= ${g.literary}` : null].filter(Boolean).join(' ') })()
      : covered.flatMap((s) => s.segments).map((g) => g.literal).join(' ')
    const refs = new Map<number, string | null>()
    for (const s of covered) if (s.reference && !refs.has(s.reference.index)) refs.set(s.reference.index, s.reference.text)
    const lex = sel.unit === 'word' ? anchor.lexical.get(sel.token) : undefined
    const notes = covered.flatMap((s) => {
      const [a, b] = range(s)
      return s.notes.filter((n) => n.first <= b && n.last >= a).map((n) => n.text)
    })
    return {
      original: covered.map((s) => text(s, ...range(s))).join(' '),
      literal,
      reference: [...refs.values()].filter((t): t is string => !!t).join(' '),
      lemma: lex?.lemma ?? '',
      gloss: lex?.gloss ?? '',
      form: lex?.form ?? '',
      key: lex?.key ?? '',
      notes: notes.join('\n'),
      sentence: text(anchor, 0, anchor.tokens.length - 1),
      chapter: this.chapter.title,
      book: this.book.title,
      studied: this.book.studied,
      support: this.book.support,
    }
  }
}

function text(s: Sentence, a: number, b: number): string {
  let out = ''
  for (let i = a; i <= b; i++) out += s.tokens[i] + (i < b ? s.ws[i] ?? ' ' : '')
  return out.trim()
}

/**
 * Fills an action template. Placeholders: {text} (the chosen layer), {original}, {literal}, {reference}, {lemma},
 * {gloss}, {form}, {notes}, {sentence}, {chapter}, {book}; "\n" in a template is a line break. A line whose
 * placeholders are all empty is dropped, so one template serves a word and a paragraph alike.
 */
export function fillTemplate(template: string, c: SelectionContent, layer: Layer): string {
  const values: Record<string, string> = {
    text: layerText(c, layer), original: c.original, literal: c.literal, reference: c.reference, lemma: c.lemma,
    gloss: c.gloss, form: c.form, notes: c.notes, sentence: c.sentence, chapter: c.chapter, book: c.book,
  }
  const placeholder = /\{(\w+)\}/g
  return template.replaceAll('\\n', '\n').split('\n').flatMap((line) => {
    const names = [...line.matchAll(placeholder)].map((m) => m[1]).filter((n) => n in values)
    if (names.length && names.every((n) => !values[n].trim())) return []
    return [line.replace(placeholder, (m, n: string) => values[n] ?? m)]
  }).join('\n').trim()
}
