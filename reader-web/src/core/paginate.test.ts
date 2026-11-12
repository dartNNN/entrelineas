import { describe, expect, it } from 'vitest'
import type { Block } from './blocks'
import { blockText, Paginator, type Fragment, type Measure } from './paginate'

/**
 * The pagination algorithm with a fake measure: text is set in lines of WIDTH characters, a page holds HEIGHT lines
 * and blocks are a line apart. Every character must be on exactly one page, in order, and no page may overflow.
 */
const WIDTH = 10, HEIGHT = 6
const lines = (n: number) => Math.max(1, Math.ceil(n / WIDTH))

class FakeMeasure implements Measure {
  used = 0
  begin() { this.used = 0 }
  place(_b: Block, f: Fragment) {
    const need = (this.used ? 1 : 0) + lines(f.end - f.start)
    const fits = this.used + need <= HEIGHT
    this.last = { at: this.used + (this.used ? 1 : 0), f }
    this.used += need
    return fits
  }
  last: { at: number; f: Fragment } | null = null
  cut(_b: Block, f: Fragment, force: boolean) {
    const room = HEIGHT - this.last!.at
    const keep = Math.max(room, force ? 1 : 0)
    return keep <= 0 ? f.start : Math.min(f.end, f.start + keep * WIDTH)
  }
}

const block = (paragraph: string, text: string): Block => ({ kind: 'hinted', paragraph, runs: [{ kind: 'text', text }] })

describe('paginate', () => {
  it('puts every character on exactly one page, in order, and cuts blocks at line ends', () => {
    const blocks = [block('p1', 'a'.repeat(25)), block('p2', 'b'.repeat(70)), block('p3', 'c'.repeat(5))]
    const paginator = new Paginator(blocks, 0, blocks.length, new FakeMeasure())
    const pages = []
    for (let p = paginator.next(); p; p = paginator.next()) pages.push(p)
    const text = pages.flatMap((p) => p.fragments.map((f) => blockText(blocks[f.block]).slice(f.start, f.end))).join('')
    expect(text).toBe(blocks.map(blockText).join(''))
    for (const p of pages) {
      const used = p.fragments.reduce((n, f, i) => n + (i ? 1 : 0) + lines(f.end - f.start), 0)
      expect(used).toBeLessThanOrEqual(HEIGHT)
      p.fragments.slice(0, -1).forEach((f) => expect(f.cont).toBe(false))
    }
    expect(pages.map((p) => p.paragraph)).toEqual(['p1', 'p2', 'p3']) // p3 does not fit after the rest of p2: it starts the next page
    expect(pages[0].fragments.at(-1)).toMatchObject({ block: 1, cont: true, end: 20, hyphen: true })
  })
})
