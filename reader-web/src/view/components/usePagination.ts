import { useEffect, useLayoutEffect, useMemo, useRef, useState, type RefObject } from 'react'
import type { Block } from '../../core/blocks'
import type { Sentence } from '../../core/book'
import { drawFragment } from '../fragment'
import { anchorBlock, screens, type Screen } from '../../core/screens'
import { DomMeasure } from '../measure'
import { Paginator, type Page } from '../../core/paginate'

/** The paragraph marker for opening a chapter on its last screen (turning back from the next chapter). */
export const CHAPTER_END = '\uffff'

/** Screens laid out at once on opening; the rest follows in the background in slices of about this many ms. */
const FIRST_SCREENS = 3
const SLICE_MS = 12

interface Options {
  blocks: Block[]
  sentences: ReadonlyMap<string, Sentence>
  screenRepeat: boolean
  /** What else changes line breaks besides the blocks (font size, justification): a new value lays out again. */
  layoutKey: string
  measurer: RefObject<HTMLElement | null>
  size: { width: number; height: number }
  /** The paragraph to start at; afterwards the paragraph of the screen being read. */
  paragraph: RefObject<string>
  onLayout: (ms: number) => void
}

/**
 * Pagination as in the Android reader: the first screens are laid out at once from the paragraph being read, the
 * rest of the chapter in the background a slice at a time, then the screens before that paragraph are put in front
 * without moving the reader. Lays out again when the blocks, the layout key, the page size or the loaded fonts change.
 */
export function usePagination({ blocks, sentences, screenRepeat, layoutKey, measurer, size, paragraph, onLayout }: Options) {
  const [{ pages, complete }, setLayout] = useState<{ pages: Page[]; complete: boolean }>({ pages: [], complete: false })
  const [index, setIndex] = useState(0)
  const list: Screen[] = useMemo(() => screens(pages, blocks, screenRepeat, sentences), [pages, blocks, screenRepeat, sentences])
  const report = useRef(onLayout)
  useLayoutEffect(() => { report.current = onLayout })

  // Fonts loaded later change line breaks.
  const [fonts, setFonts] = useState(0)
  useEffect(() => {
    const set = document.fonts
    if (!set) return
    const loaded = () => setFonts((n) => n + 1)
    set.addEventListener('loadingdone', loaded)
    return () => set.removeEventListener('loadingdone', loaded)
  }, [])

  useLayoutEffect(() => {
    const box = measurer.current
    if (!box || !size.width || !size.height) return
    let cancelled = false
    let timer = 0
    const started = performance.now()
    const atEnd = paragraph.current === CHAPTER_END
    const anchor = atEnd ? blocks.length : anchorBlock(blocks, paragraph.current)
    const forward = new Paginator(blocks, anchor, blocks.length, new DomMeasure(box, size.height, drawFragment))
    const first: Page[] = []
    for (let k = 0; k < FIRST_SCREENS; k++) { const p = forward.next(); if (p) first.push(p) }
    // The page is measured in the DOM, which only exists after rendering: the first screens are set from the layout
    // effect on purpose (the DOM is the external system here), before the browser paints.
    // oxlint-disable-next-line react/set-state-in-effect
    setLayout({ pages: first, complete: false })
    setIndex(0)
    report.current(Math.round(performance.now() - started))

    const slice = (paginator: Paginator): [Page[], boolean] => {
      const until = performance.now() + SLICE_MS
      const got: Page[] = []
      while (performance.now() < until) {
        const p = paginator.next()
        if (!p) return [got, true]
        got.push(p)
      }
      return [got, false]
    }
    const before: Page[] = []
    const backward = anchor > 0 ? new Paginator(blocks, 0, anchor, new DomMeasure(box, size.height, drawFragment)) : null
    const step = () => {
      if (cancelled) return
      const [got, done] = slice(forward)
      if (got.length || done) setLayout((l) => ({ pages: got.length ? [...l.pages, ...got] : l.pages, complete: done && !backward }))
      if (!done) timer = window.setTimeout(step, 0)
      else if (backward) timer = window.setTimeout(back, 0)
    }
    const back = () => {
      if (cancelled || !backward) return
      const [got, done] = slice(backward)
      before.push(...got)
      if (!done) { timer = window.setTimeout(back, 0); return }
      const shift = screens(before, blocks, screenRepeat, sentences).length
      setLayout((l) => ({ pages: [...before, ...l.pages], complete: true }))
      setIndex((i) => (atEnd ? Math.max(0, shift - 1) : i + shift))
    }
    timer = window.setTimeout(step, 0)
    return () => { cancelled = true; clearTimeout(timer) }
  }, [blocks, sentences, screenRepeat, layoutKey, measurer, size, paragraph, fonts])

  return { pages, list, complete, index, setIndex }
}
