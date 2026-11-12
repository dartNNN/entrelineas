import { repeatBlocks, type Block } from './blocks'
import { pageTokens, type Page } from './paginate'
import type { Sentence } from './book'

/**
 * Screens as read: the pages, and in screen repeat after each page its hint-free twin with exactly the same words, as
 * in the Android reader (withRepeats).
 */
export type Screen =
  | { kind: 'page'; page: number; paragraph: string }
  | { kind: 'repeat'; page: number; paragraph: string; blocks: Block[] }

export function screens(pages: Page[], blocks: Block[], screenRepeat: boolean, sentences: ReadonlyMap<string, Sentence>): Screen[] {
  return pages.flatMap((p, i): Screen[] => {
    const page: Screen = { kind: 'page', page: i, paragraph: p.paragraph }
    const tokens = screenRepeat ? pageTokens(p, blocks) : []
    return tokens.length ? [page, { kind: 'repeat', page: i, paragraph: p.paragraph, blocks: repeatBlocks(tokens, sentences) }] : [page]
  })
}

/**
 * The block where layout starts for a saved paragraph: the first block of that paragraph, or 0 at the chapter start
 * (as Android's anchorBlock). Paragraph ids sort in reading order.
 */
export function anchorBlock(blocks: Block[], paragraph: string): number {
  if (!paragraph) return 0
  const i = blocks.findIndex((b) => b.kind !== 'title' && b.paragraph >= paragraph)
  return i <= 1 ? 0 : i
}
