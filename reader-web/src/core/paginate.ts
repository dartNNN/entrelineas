import type { Block, Run } from './blocks'

/**
 * Pagination as in the Android reader (Layout.kt Paginator): blocks fill a screen one after another, a block that does
 * not fit is cut at a line boundary and goes on on the next screen. The algorithm is here; measuring is behind
 * [Measure] (the reader measures in a hidden element of the page's width and styles, view/measure.ts), so this module
 * has no DOM and is tested with a fake measure.
 */

/** A piece of a block: characters [start, end) of its text; cont: cut at the end (its last line is a full line). */
export interface Fragment { block: number; start: number; end: number; cont: boolean; hyphen: boolean }

export interface Page { fragments: Fragment[]; paragraph: string }

export const blockText = (b: Block) => b.runs.map((r) => r.text).join('')

/** The runs of characters [start, end) of a block, cut where needed; a run keeps its kind and word. */
export function sliceRuns(b: Block, start: number, end: number): Run[] {
  const out: Run[] = []
  let at = 0
  for (const r of b.runs) {
    const from = Math.max(start, at), to = Math.min(end, at + r.text.length)
    if (from < to) out.push({ ...r, text: r.text.slice(from - at, to - at) })
    at += r.text.length
  }
  return out
}

/**
 * "sentence:token" keys of the words a page shows (hinted, plain and title blocks), in reading order, each once. A
 * word belongs to the page where it begins, also when it is hyphenated across two pages or its gloss is on the next.
 */
export function pageTokens(page: Page, blocks: Block[]): string[] {
  const seen = new Set<string>()
  for (const f of page.fragments) {
    const b = blocks[f.block]
    if (b.kind !== 'hinted' && b.kind !== 'plain' && b.kind !== 'title') continue
    let at = 0
    for (const r of b.runs) {
      const begins = at >= f.start && at < f.end
      if (begins && r.kind === 'token' && r.text.trim()) seen.add(`${r.sentence}:${r.token}`)
      at += r.text.length
    }
  }
  return [...seen]
}

/**
 * What pagination needs to know about text on a page. begin() starts an empty page; place() puts a fragment after
 * what is on the page and says whether everything still fits; cut() tells, for the fragment placed last, the text
 * offset of its first line that does not fit (at least one line when [force]), or [fragment.start] when none does.
 */
export interface Measure {
  begin(): void
  place(block: Block, fragment: Fragment): boolean
  cut(block: Block, fragment: Fragment, force: boolean): number
}

const LETTER = /\p{L}/u

/** Resumable pagination of blocks[start, end). Each next() returns one page or null at the end. */
export class Paginator {
  private block: number
  private offset = 0
  private readonly blocks: Block[]
  private readonly end: number
  private readonly measure: Measure
  private readonly texts: string[]

  constructor(blocks: Block[], start: number, end: number, measure: Measure) {
    this.blocks = blocks
    this.block = start
    this.end = end
    this.measure = measure
    this.texts = blocks.map(blockText)
  }

  next(): Page | null {
    const fragments: Fragment[] = []
    this.measure.begin()
    while (this.block < this.end) {
      const text = this.texts[this.block]
      const whole: Fragment = { block: this.block, start: this.offset, end: text.length, cont: false, hyphen: false }
      if (this.measure.place(this.blocks[this.block], whole)) {
        fragments.push(whole)
        this.block++
        this.offset = 0
        continue
      }
      // The block does not fit: keep the lines that do, the rest starts the next page.
      const cut = this.measure.cut(this.blocks[this.block], whole, fragments.length === 0)
      if (cut <= this.offset) break // not even one line: the block starts the next page
      const hyphen = LETTER.test(text[cut - 1] ?? '') && LETTER.test(text[cut] ?? '')
      fragments.push({ block: this.block, start: this.offset, end: cut, cont: true, hyphen })
      let next = cut
      while (next < text.length && /\s/.test(text[next]) && text[next] !== '\n') next++
      if (next >= text.length) { this.block++; this.offset = 0 } else this.offset = next
      break
    }
    if (!fragments.length) return null
    return { fragments, paragraph: this.blocks[fragments[0].block].paragraph }
  }
}
