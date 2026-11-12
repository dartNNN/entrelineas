import type { Chapter, Lexical, Paragraph, Reference, Sentence } from './book'
import { effectiveRepeat, type Settings } from './settings'

/**
 * What the page shows, as plain data, built the same way as the Android reader (Layout.kt buildBlocks): per paragraph
 * the hinted text, its notes, the author's footnotes, the published translation if wanted, and the plain repeat.
 * Components only draw these blocks; pagination splits them into screens.
 */
export type BlockKind = 'hinted' | 'notes' | 'authorNotes' | 'reference' | 'plain' | 'title'

/** One run of text; tokens and glosses carry their word ("sentence:token"), literal translations their segment. */
export type Run =
  | { kind: 'token'; text: string; sentence: string; token: number }
  | { kind: 'gloss'; text: string; sentence: string; token: number }
  | { kind: 'literal'; text: string; sentence: string; first: number; last: number }
  | { kind: 'text'; text: string }

export interface Block { kind: BlockKind; paragraph: string; runs: Run[] }

/** A gloss is shown up to the owner's threshold of its sense's occurrences, unless the sense is marked familiar. */
export function glossShown(lex: Lexical | undefined, s: Settings, known: ReadonlySet<string>): lex is Lexical {
  return !!lex && s.showLexical && lex.n <= s.threshold && !known.has(lex.key)
}

/** "≈ " for a free rendering, "? " where the segment model doubted the published text renders these sentences. */
export function referenceMark(r: Pick<Reference, 'loose' | 'uncertain'>): string {
  return r.uncertain ? '? ' : r.loose ? '≈ ' : ''
}

const token = (s: Sentence, i: number): Run => ({ kind: 'token', text: s.tokens[i], sentence: s.id, token: i })

function hinted(par: Paragraph, s: Settings, known: ReadonlySet<string>): Run[] {
  const runs: Run[] = []
  for (const sentence of par.sentences) {
    const segments = sentence.segments.length ? sentence.segments : [{ first: 0, last: sentence.tokens.length - 1, literal: '', literary: null }]
    for (const seg of segments) {
      for (let i = seg.first; i <= Math.min(seg.last, sentence.tokens.length - 1); i++) {
        runs.push(token(sentence, i))
        const lex = sentence.lexical.get(i)
        if (glossShown(lex, s, known)) {
          runs.push({ kind: 'gloss', text: ` [${lex.gloss}${lex.form ? `; ${lex.form}` : ''}]`, sentence: sentence.id, token: i })
        }
        if (i < seg.last) runs.push({ kind: 'text', text: sentence.ws[i] ?? ' ' })
      }
      if (s.showLiteral && seg.literal.trim()) {
        runs.push({ kind: 'literal', text: ` (${seg.literal}${seg.literary ? ` = ${seg.literary}` : ''})`, sentence: sentence.id, first: seg.first, last: seg.last })
      }
      runs.push({ kind: 'text', text: sentence.ws[seg.last] || ' ' })
    }
  }
  return runs
}

function plain(par: Paragraph): Run[] {
  return par.sentences.flatMap((sentence) => sentence.tokens.flatMap((_, i): Run[] => [token(sentence, i), { kind: 'text', text: sentence.ws[i] ?? ' ' }]))
}

const text = (t: string): Run[] => [{ kind: 'text', text: t }]

export function buildBlocks(chapter: Chapter, s: Settings, known: ReadonlySet<string>): Block[] {
  const blocks: Block[] = []
  const first = chapter.paragraphs[0]
  // Packages before schema 1.3 carry the title as plain text; later ones as an annotated heading paragraph.
  if (!first?.heading) blocks.push({ kind: 'title', paragraph: first?.id ?? '', runs: text(chapter.title) })
  // A published translation follows the paragraph where its sentence group ends (groups may span paragraphs).
  const all = chapter.paragraphs.flatMap((p) => p.sentences)
  const groupEnds = new Set(all.filter((x, i) => x.reference && all[i + 1]?.reference?.index !== x.reference.index).map((x) => x.id))
  for (const par of chapter.paragraphs) {
    blocks.push({ kind: par.heading ? 'title' : 'hinted', paragraph: par.id, runs: hinted(par, s, known) })
    if (s.showNotes) {
      const notes = par.sentences.flatMap((x) => x.notes).filter((n) => n.kind === 'grammar' || n.kind === 'context')
      if (notes.length) blocks.push({ kind: 'notes', paragraph: par.id, runs: text(notes.map((n) => (n.kind === 'grammar' ? '◦ ' : '※ ') + n.text).join('\n')) })
    }
    const authorNotes = par.sentences.flatMap((x) => x.authorNotes)
    if (s.showAuthorNotes && authorNotes.length) blocks.push({ kind: 'authorNotes', paragraph: par.id, runs: text(authorNotes.map((n) => `* ${n}`).join('\n')) })
    if (s.referenceInText) {
      const refs = par.sentences.filter((x) => groupEnds.has(x.id)).map((x) => x.reference).filter((r): r is Reference => !!r?.text)
      if (refs.length) {
        const body = refs.map((r) => referenceMark(r) + r.text).join(' ')
        const notes = s.showFootnotes ? refs.flatMap((r) => r.notes) : []
        blocks.push({ kind: 'reference', paragraph: par.id, runs: text([body, ...notes.map((n) => `* ${n}`)].join('\n')) })
      }
    }
    if (effectiveRepeat(s) === 'paragraph') blocks.push({ kind: 'plain', paragraph: par.id, runs: plain(par) })
  }
  return blocks
}

/**
 * The hint-free screen for a hinted one: exactly the tokens shown on it, in order, one plain block per paragraph,
 * still tappable. [tokens] are the "sentence:token" keys found on the screen, in reading order.
 */
export function repeatBlocks(tokens: string[], sentences: ReadonlyMap<string, Sentence>): Block[] {
  const seen = new Set<string>()
  const byParagraph = new Map<string, [Sentence, number][]>()
  for (const key of tokens) {
    if (seen.has(key)) continue // a gloss carries its word's key too
    seen.add(key)
    const [sid, i] = key.split(':')
    const sentence = sentences.get(sid)
    if (!sentence) continue
    const paragraph = sid.slice(0, sid.lastIndexOf('s'))
    byParagraph.set(paragraph, [...(byParagraph.get(paragraph) ?? []), [sentence, Number(i)]])
  }
  return [...byParagraph].map(([paragraph, items]) => ({
    kind: 'plain', paragraph,
    runs: items.flatMap(([sentence, i], n): Run[] => (n < items.length - 1 ? [token(sentence, i), { kind: 'text', text: sentence.ws[i] ?? ' ' }] : [token(sentence, i)])),
  }))
}
