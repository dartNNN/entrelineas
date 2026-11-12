import type { Block, BlockKind, Run } from '../core/blocks'
import type { Fragment } from '../core/paginate'
import type { Highlight } from '../core/selection'

/**
 * One fragment of a block as the page draws it. The same markup is built twice: as React elements for the screen
 * being read, and as plain DOM for measuring the next screens (drawFragment), so both break lines the same way.
 */
export const TAGS: Record<BlockKind, string> = { title: 'h2', hinted: 'p', plain: 'p', notes: 'div', authorNotes: 'div', reference: 'div' }
export const LANG: Record<BlockKind, string> = { title: 'es', hinted: 'es', plain: 'es', notes: 'ru', authorNotes: 'es', reference: 'ru' }

export interface Piece { run: Run; painted: boolean }

/** The runs of a fragment with whether the selection paints each; runs cut by the fragment keep their word. */
export function pieces(b: Block, f: Fragment, flags: boolean[]): Piece[] {
  const out: Piece[] = []
  let at = 0
  b.runs.forEach((r, i) => {
    const from = Math.max(f.start, at), to = Math.min(f.end, at + r.text.length)
    if (from < to) out.push({ run: { ...r, text: r.text.slice(from - at, to - at) }, painted: flags[i] })
    at += r.text.length
  })
  return out
}

export const className = (b: Block, f: Fragment) => `block ${b.kind}${f.cont ? ' cont' : ''}`
export const firstSentence = (b: Block) => {
  const t = b.runs.find((r) => r.kind === 'token')
  return t?.kind === 'token' ? t.sentence : `${b.paragraph}s1`
}
export const key = (r: Run) => (r.kind === 'token' || r.kind === 'gloss' ? `${r.sentence}:${r.token}` : undefined)

/** The same element as plain DOM, without selection, for the measurer. */
export function drawFragment(b: Block, f: Fragment): HTMLElement {
  const el = document.createElement(TAGS[b.kind])
  el.className = className(b, f)
  el.lang = LANG[b.kind]
  for (const { run: r } of pieces(b, f, [])) {
    if (r.kind === 'text') { el.append(r.text); continue }
    const span = document.createElement('span')
    span.className = r.kind === 'token' ? 'w' : r.kind
    if (r.kind !== 'token') span.lang = 'ru'
    span.textContent = r.text
    el.append(span)
  }
  if (f.hyphen) el.append('-')
  return el
}

/**
 * Which runs of a block the selection paints: the original words without their glosses, the glosses, or the literal
 * translations, by the chosen layer; notes and the published translation paint their whole block. Spaces between two
 * painted runs are painted too, so a selected sentence is one band and not striped.
 */
export function painted(block: Block, h: Highlight | null): boolean[] {
  const none = block.runs.map(() => false)
  if (!h) return none
  const mine = h.paragraphs.has(block.paragraph)
  if ((h.layer === 'note' && block.kind === 'notes' && mine) || (h.layer === 'reference' && block.kind === 'reference' && mine)) return block.runs.map(() => true)
  if (block.kind === 'notes' || block.kind === 'reference' || block.kind === 'authorNotes') return none
  const hit = (r: Run): boolean => {
    switch (h.layer) {
      case 'gloss': return r.kind === 'gloss' && h.tokens.has(`${r.sentence}:${r.token}`)
      case 'literal': {
        if (r.kind !== 'literal') return false
        for (let i = r.first; i <= r.last; i++) if (h.tokens.has(`${r.sentence}:${i}`)) return true
        return false
      }
      default: return r.kind === 'token' && h.tokens.has(`${r.sentence}:${r.token}`)
    }
  }
  const flags = block.runs.map(hit)
  for (let i = 0; i < flags.length; i++) {
    if (flags[i] || block.runs[i].text.trim()) continue
    const before = flags.slice(0, i).lastIndexOf(true)
    const after = flags.indexOf(true, i + 1)
    // Only blank runs between two painted ones join them.
    if (before >= 0 && after >= 0 && block.runs.slice(before + 1, after).every((r, k) => flags[before + 1 + k] || !r.text.trim())) flags[i] = true
  }
  return flags
}
