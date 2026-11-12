import { memo, useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { Block } from '../../core/blocks'
import type { Sentence } from '../../core/book'
import { blockText, type Fragment } from '../../core/paginate'
import { select, type Highlight, type Layer, type TextSelection, type WordTap } from '../../core/selection'
import { effectiveRepeat, type Settings } from '../../core/settings'
import { BlockView } from './BlockView'
import { usePagination } from './usePagination'

interface Props {
  blocks: Block[]
  sentences: ReadonlyMap<string, Sentence>
  settings: Settings
  highlight: Highlight | null
  startParagraph: string
  /** Whether the arrow keys turn pages (false while a panel, the card or a selection is open). */
  keys: boolean
  onWord: (tap: WordTap) => void
  onLongPress: (sel: TextSelection) => void
  /** A tap off any word: shows or hides the shell, or drops the selection. */
  onBlank: () => void
  /** A vertical swipe: calls up the shell. */
  onReveal: () => void
  /** The screen shown: its first paragraph, its index and the number of screens (0 while the chapter is laid out). */
  onScreen: (paragraph: string, index: number, total: number) => void
  onTurn: () => void
  onLayout: (ms: number) => void
}

const LONG_PRESS_MS = 500
const SWIPE = 48

/**
 * One chapter as screens (pagination: usePagination); the counter shows "…" until the chapter is complete. Only the
 * screen being read is in the page. In screen repeat a hint-free screen with exactly the same words follows each one. Turned by a
 * horizontal swipe, the arrow keys or the wheel; a tap opens the word card, a long press starts a selection, a
 * vertical swipe or a tap off any word calls up the shell.
 */
export const Reader = memo(function Reader(props: Props) {
  const { blocks, sentences, settings, highlight, startParagraph } = props
  const viewport = useRef<HTMLDivElement>(null)
  const measurer = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ width: 0, height: 0 })
  const paragraph = useRef(startParagraph)
  // Callbacks are read through a ref so a new callback does not re-run the layout.
  const handlers = useRef(props)
  useLayoutEffect(() => { handlers.current = props })

  useLayoutEffect(() => {
    const el = viewport.current
    if (!el) return
    const update = () => setSize((old) => (old.width === el.clientWidth && old.height === el.clientHeight ? old : { width: el.clientWidth, height: el.clientHeight }))
    update()
    const observer = new ResizeObserver(update)
    observer.observe(el)
    return () => observer.disconnect()
  }, [])

  const onLayout = useCallback((ms: number) => handlers.current.onLayout(ms), [])
  const { pages, list, complete, index, setIndex } = usePagination({
    blocks, sentences, screenRepeat: effectiveRepeat(settings) === 'screen', layoutKey: `${settings.fontSize}:${settings.justify}`,
    measurer, size, paragraph, onLayout,
  })
  const count = useRef(0)
  useLayoutEffect(() => { count.current = list.length })

  const screen = list[Math.min(index, list.length - 1)]
  useEffect(() => {
    if (!screen) return
    paragraph.current = screen.paragraph
    handlers.current.onScreen(screen.paragraph, index, complete ? list.length : 0)
  }, [screen, index, complete, list.length])

  const turn = useCallback((delta: number) => {
    setIndex((i) => {
      const next = Math.max(0, Math.min(count.current - 1, i + delta))
      if (next !== i) handlers.current.onTurn()
      return next
    })
  }, [setIndex])

  // The arrow keys turn pages only while nothing is open over the page.
  useEffect(() => {
    if (!props.keys) return
    const key = (e: KeyboardEvent) => {
      if ((e.target as HTMLElement).closest?.('input, textarea, button, [role=dialog]')) return
      if (['ArrowRight', 'PageDown', ' '].includes(e.key)) { e.preventDefault(); turn(1) }
      if (['ArrowLeft', 'PageUp'].includes(e.key)) { e.preventDefault(); turn(-1) }
    }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [turn, props.keys])

  const gesture = useGestures(turn, handlers)
  // One wheel gesture fires many events: one page per gesture.
  const lastWheel = useRef(0)
  const wheel = (e: React.WheelEvent) => {
    const d = Math.abs(e.deltaY) > Math.abs(e.deltaX) ? e.deltaY : e.deltaX
    if (Math.abs(d) < 20 || e.timeStamp - lastWheel.current < 400) return
    lastWheel.current = e.timeStamp
    turn(d > 0 ? 1 : -1)
  }

  const page = screen?.kind === 'page' ? pages[screen.page] : null
  return (
    <div ref={viewport} className={`pages ${settings.justify ? 'justify' : ''}`} style={{ fontSize: `${settings.fontSize}px` }} {...gesture}
      onWheel={wheel} onContextMenu={(e) => e.preventDefault()}>
      <div ref={measurer} className="measurer" aria-hidden="true" />
      {page && (
        <div className="page" data-testid="page">
          {page.fragments.map((f) => <BlockView key={`${f.block}:${f.start}`} block={blocks[f.block]} fragment={f} highlight={relevant(highlight, blocks[f.block])} />)}
        </div>
      )}
      {screen?.kind === 'repeat' && (
        <div className="page" data-testid="repeat-screen">
          {screen.blocks.map((b, i) => <BlockView key={i} block={b} fragment={whole(b)} highlight={relevant(highlight, b)} />)}
        </div>
      )}
      {!screen && (
        // Before the first layout (and where there is none, as in tests) the chapter start is drawn as it comes.
        <div className="page">{blocks.slice(0, 12).map((b, i) => <BlockView key={i} block={b} fragment={whole(b)} highlight={relevant(highlight, b)} />)}</div>
      )}
    </div>
  )
})

const whole = (b: Block): Fragment => ({ block: -1, start: 0, end: blockText(b).length, cont: false, hyphen: false })

/** A block re-renders on a selection change only when the selection touches its paragraph. */
const relevant = (h: Highlight | null, b: Block) => (h && h.paragraphs.has(b.paragraph) ? h : null)

/** Swipes turn pages or call up the shell, a tap opens a word or toggles the shell, a long press selects. */
function useGestures(turn: (d: number) => void, handlers: React.RefObject<Props>) {
  const start = useRef<{ x: number; y: number; timer: number; long: boolean; target: HTMLElement } | null>(null)
  const cancel = () => { if (start.current) clearTimeout(start.current.timer) }
  return {
    onPointerDown: (e: React.PointerEvent) => {
      if (e.button !== 0) return
      const target = e.target as HTMLElement
      const timer = window.setTimeout(() => {
        if (!start.current) return
        start.current.long = true
        const sel = selectionAt(target)
        if (sel) handlers.current.onLongPress(sel)
      }, LONG_PRESS_MS)
      start.current = { x: e.clientX, y: e.clientY, timer, long: false, target }
    },
    onPointerMove: (e: React.PointerEvent) => {
      const s = start.current
      if (s && Math.hypot(e.clientX - s.x, e.clientY - s.y) > 10) cancel()
    },
    onPointerUp: (e: React.PointerEvent) => {
      const s = start.current
      cancel()
      start.current = null
      if (!s || s.long) return
      const dx = e.clientX - s.x, dy = e.clientY - s.y
      if (Math.abs(dx) > SWIPE && Math.abs(dx) > Math.abs(dy)) return turn(dx < 0 ? 1 : -1)
      if (Math.abs(dy) > SWIPE) return handlers.current.onReveal()
      if (Math.hypot(dx, dy) > 10) return
      const word = s.target.closest<HTMLElement>('[data-k]')
      if (word) {
        const [sentence, token] = (word.dataset.k ?? '').split(':')
        handlers.current.onWord({ sentence, token: Number(token) })
      } else handlers.current.onBlank()
    },
    onPointerCancel: () => { cancel(); start.current = null },
  }
}

/** A long press on a word selects it; on notes or the published translation, that layer of the paragraph. */
function selectionAt(target: HTMLElement): TextSelection | null {
  const word = target.closest<HTMLElement>('[data-k]')
  if (word) {
    const [sentence, token] = (word.dataset.k ?? '').split(':')
    return select(sentence, Number(token))
  }
  const block = target.closest<HTMLElement>('[data-block]')
  const layer: Layer | null = block?.dataset.block === 'notes' ? 'note' : block?.dataset.block === 'reference' ? 'reference' : null
  const first = block?.dataset.first
  return layer && first ? select(first, 0, { unit: 'paragraph', layer }) : null
}
