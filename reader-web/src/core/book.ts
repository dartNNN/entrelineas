import JSZip from 'jszip'
import type { Chapter as ChapterDoc, Entry, Lexical, Sentence as SentenceDoc } from './format/chapter'
import { checkChapter, checkManifest } from './format/validate'

/**
 * The book as the reader uses it: the package (format/*.schema.json) parsed and checked, token-range tuples turned
 * into objects. Readers accept any 1.x schema; fields of later minor versions are optional.
 */
export interface Segment { first: number; last: number; literal: string; literary: string | null }
export interface Note { first: number; last: number; kind: 'grammar' | 'context' | 'variants' | 'expression'; text: string }
export interface Reference { index: number; text: string | null; loose: boolean; uncertain: boolean; notes: string[] }
export interface Sentence {
  id: string
  tokens: string[]
  ws: string[]
  segments: Segment[]
  notes: Note[]
  lexical: Map<number, Lexical>
  reference: Reference | null
  authorNotes: string[]
}
export interface Paragraph { id: string; heading: boolean; sentences: Sentence[] }
export interface Chapter { index: number; title: string; paragraphs: Paragraph[]; entries: Record<string, Entry> }
/** A chapter as listed in the manifest; its text is read when it is opened. weight: its file size, for the book progress. */
export interface ChapterInfo { index: number; title: string; weight: number }
/**
 * studied, support: language codes of the original and of the translations and notes. chapter(i) reads, checks and
 * parses chapter i on first use and keeps it, so opening a book costs its manifest and the chapter being read.
 */
export interface Book {
  id: string; title: string; author: string; maxOccurrence: number; studied: string; support: string
  chapters: ChapterInfo[]
  chapter(i: number): Promise<Chapter>
}
export type { Entry, Lexical }

/** Parsed chapters kept per book: the one being read and its neighbours. */
export const KEEP_CHAPTERS = 3

const range = (v: unknown): number => (typeof v === 'number' ? v : 0)
const str = (v: unknown): string => (typeof v === 'string' ? v : '')

function sentence(s: SentenceDoc, references: Reference[], authorNotes: Map<string, string>): Sentence {
  const notes: Note[] = [
    ...s.grammar.map((g): Note => ({ first: range(g[0]), last: range(g[1]), kind: 'grammar', text: str(g[3]) })),
    ...s.context.map((c): Note => ({ first: range(c[0]), last: range(c[1]), kind: 'context', text: str(c[2]) })),
    ...s.variants.map((v): Note => ({
      first: range(v[0]), last: range(v[1]), kind: 'variants',
      text: (Array.isArray(v[2]) ? v[2].join(', ') : str(v[2])) + (v[3] ? ` — ${str(v[3])}` : ''),
    })),
    ...s.expressions.map((e): Note => ({ first: range(e[0]), last: range(e[1]), kind: 'expression', text: `${str(e[2])} — ${str(e[3])}` })),
  ]
  const segments = (s.segments as unknown[][]).map((g): Segment => ({
    first: range(g[0]), last: range(g[1]), literal: str(g[2]), literary: typeof g[3] === 'string' && g[3] ? g[3] : null,
  }))
  return {
    id: s.id, tokens: s.tokens, ws: s.ws, segments, notes,
    lexical: new Map(s.lexical.map((l) => [l.t, l])),
    reference: s.ref != null ? references[s.ref] ?? null : null,
    authorNotes: (s.notes ?? []).map((id) => authorNotes.get(id)).filter((t): t is string => !!t),
  }
}

export function parseChapter(doc: ChapterDoc): Chapter {
  const footnotes = doc.footnotes ?? []
  const translator = new Map(footnotes.filter((f) => f.side === 'reference').map((f) => [f.id, f.text]))
  const author = new Map(footnotes.filter((f) => f.side === 'source').map((f) => [f.id, f.text]))
  const references = (doc.references ?? []).map((r, index): Reference => ({
    index, text: r.text, loose: !!r.loose, uncertain: !!r.uncertain,
    notes: (r.notes ?? []).map((id) => translator.get(id)).filter((t): t is string => !!t),
  }))
  return {
    index: doc.index, title: doc.title, entries: doc.entries ?? {},
    paragraphs: doc.paragraphs.map((p) => ({ id: p.id, heading: !!p.heading, sentences: p.sentences.map((s) => sentence(s, references, author)) })),
  }
}

/**
 * Opens a .bbook.zip: reads and checks the manifest; chapters are read when asked for. Throws with a readable message
 * when the file is not a book package or breaks the format.
 */
export async function loadBook(data: ArrayBuffer | Uint8Array): Promise<Book> {
  let zip: JSZip
  try {
    zip = await JSZip.loadAsync(data)
  } catch {
    throw new Error('Это не файл книги (.bbook.zip)')
  }
  const read = async (name: string): Promise<unknown> => {
    const file = zip.file(name)
    if (!file) throw new Error(`В пакете нет ${name}`)
    return JSON.parse(await file.async('string'))
  }
  const manifest = checkManifest(await read('manifest.json'))
  for (const c of manifest.chapters) if (!zip.file(c.file)) throw new Error(`В пакете нет ${c.file}`)
  // Only the last few chapters stay parsed (a Map keeps insertion order: the first key is the least recently used).
  const loaded = new Map<number, Promise<Chapter>>()
  const size = (name: string) => (zip.file(name) as unknown as { _data?: { uncompressedSize?: number } } | null)?._data?.uncompressedSize
  return {
    id: manifest.id, title: manifest.title, author: manifest.author, maxOccurrence: manifest.maxOccurrence,
    studied: manifest.studied, support: manifest.support,
    // JSZip keeps the uncompressed size of each entry only internally; without it every chapter weighs the same.
    chapters: manifest.chapters.map((c) => ({ index: c.index, title: c.title, weight: Math.max(1, size(c.file) ?? 1) })),
    chapter(i) {
      const info = manifest.chapters[i]
      if (!info) return Promise.reject(new Error(`В книге нет главы ${i + 1}`))
      const got = loaded.get(i) ?? read(info.file).then((doc) => parseChapter(checkChapter(doc, info.file)))
      loaded.delete(i)
      loaded.set(i, got)
      while (loaded.size > KEEP_CHAPTERS) loaded.delete(loaded.keys().next().value!)
      return got
    },
  }
}
