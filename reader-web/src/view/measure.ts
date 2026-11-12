import type { Block } from '../core/blocks'
import type { Fragment, Measure } from '../core/paginate'

/** Builds a fragment's element the same way the reader draws it (see BlockView). */
export type Draw = (b: Block, f: Fragment) => HTMLElement

/**
 * Measuring for pagination in the browser: fragments are drawn into [box], a hidden element as wide as the page with
 * the page's styles, and compared with the page [height].
 */
export class DomMeasure implements Measure {
  private readonly box: HTMLElement
  private readonly height: number
  private readonly draw: Draw
  private top = 0
  private last: HTMLElement | null = null

  constructor(box: HTMLElement, height: number, draw: Draw) {
    this.box = box
    this.height = height
    this.draw = draw
  }

  begin() {
    this.box.replaceChildren()
    this.top = this.box.getBoundingClientRect().top
    this.last = null
  }

  place(block: Block, fragment: Fragment): boolean {
    this.last = this.draw(block, fragment)
    this.box.append(this.last)
    return this.last.getBoundingClientRect().bottom - this.top <= this.height + 0.5
  }

  cut(_block: Block, fragment: Fragment, force: boolean): number {
    return this.last ? cutAt(this.last, fragment.start, this.top + this.height, force) : fragment.start
  }
}

/**
 * Where to cut a laid-out block so that only its lines above [limit] stay: the text offset of the first line that does
 * not fit. Lines are found from the rectangles of the text (glyph boxes of neighbouring lines do not overlap, the line
 * height leaves leading between them). With [force], at least one line stays.
 */
function cutAt(el: HTMLElement, base: number, limit: number, force: boolean): number {
  const nodes: { node: Text; at: number }[] = []
  const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
  let total = 0
  for (let n = walker.nextNode(); n; n = walker.nextNode()) { nodes.push({ node: n as Text, at: total }); total += (n as Text).length }
  const range = document.createRange()
  range.selectNodeContents(el)
  // Lines: rectangles grouped by vertical overlap.
  const lines: { top: number; bottom: number }[] = []
  for (const r of [...range.getClientRects()].filter((x) => x.height > 0).sort((a, b) => a.top - b.top)) {
    const line = lines.at(-1)
    if (line && r.top < line.bottom - 1) line.bottom = Math.max(line.bottom, r.bottom)
    else lines.push({ top: r.top, bottom: r.bottom })
  }
  let keep = lines.findIndex((l) => l.bottom > limit) // the first line that does not fit
  if (keep < 0) return base + total
  if (keep === 0) { if (!force) return base; keep = 1 }
  if (keep >= lines.length) return base + total
  const lastBottom = lines[keep - 1].bottom
  // The first character whose glyph reaches below the last kept line.
  const bottomOf = (i: number): number => {
    for (let k = i; k < total; k++) {
      const n = nodes.findLast((x) => x.at <= k)!
      range.setStart(n.node, k - n.at)
      range.setEnd(n.node, k - n.at + 1)
      const rect = [...range.getClientRects()].find((x) => x.height > 0)
      if (rect) return rect.bottom
    }
    return Infinity
  }
  let lo = 0, hi = total
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (bottomOf(mid) > lastBottom + 0.5) hi = mid
    else lo = mid + 1
  }
  return base + lo
}
