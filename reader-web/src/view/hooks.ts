import { del, get, set } from 'idb-keyval'
import { useCallback, useEffect, useState } from 'react'
import { loadBook, type Book, type Chapter } from '../core/book'

/** The book file is kept in IndexedDB (it can be several megabytes); settings and marks in localStorage. */
const BOOK_KEY = 'book'

/** Keeps the opened book for the next visit; the book is shown without waiting, and a browser without storage just forgets it. */
function remember(data: ArrayBuffer) {
  set(BOOK_KEY, data).catch(() => undefined)
}

const message = (e: unknown) => (e instanceof Error ? e.message : String(e))

/**
 * The book being read: "?book=<url>" opens a package published somewhere (it must allow cross-origin reads),
 * otherwise the last opened book comes back; open() takes a file the owner chose.
 */
export function useBook() {
  const [book, setBook] = useState<Book | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const url = new URLSearchParams(window.location.search).get('book')
    const start = url
      ? fetch(url).then((r) => { if (!r.ok) throw new Error(`Не удалось скачать книгу: ${r.status}`); return r.arrayBuffer() })
        .then(async (data) => { setBook(await loadBook(data)); remember(data) })
        .catch((e: unknown) => setError(message(e)))
      : get<ArrayBuffer>(BOOK_KEY).then(async (data) => { if (data) setBook(await loadBook(data)) }).catch(() => del(BOOK_KEY))
    start.finally(() => setLoading(false))
  }, [])

  const open = useCallback(async (file: File): Promise<Book | null> => {
    try {
      const data = await file.arrayBuffer()
      const opened = await loadBook(data)
      setBook(opened)
      remember(data)
      setError(null)
      return opened
    } catch (e) {
      setError(message(e))
      return null
    }
  }, [])

  return { book, loading, error, open }
}

/** Chapter [index] of [book], read when it is first asked for; null while it is being read. */
export function useChapter(book: Book | null, index: number): { chapter: Chapter | null; error: string | null } {
  const [state, setState] = useState<{ book: Book | null; index: number; chapter: Chapter | null; error: string | null }>({ book: null, index: -1, chapter: null, error: null })
  useEffect(() => {
    if (!book) return
    let current = true
    book.chapter(index).then(
      (chapter) => { if (current) setState({ book, index, chapter, error: null }) },
      (e: unknown) => { if (current) setState({ book, index, chapter: null, error: message(e) }) })
    return () => { current = false }
  }, [book, index])
  // A chapter of another book or another index is not shown while the right one is read.
  return state.book === book && state.index === index ? { chapter: state.chapter, error: state.error } : { chapter: null, error: null }
}

/**
 * The shell (top bar): shown on start, toggled by a tap off any word, called up by a swipe; it hides itself after
 * [seconds], on a page turn or a word tap, and stays while [busy] (a menu, settings, the card or a selection is open).
 */
export function useShell(seconds: number, busy: boolean, active: boolean) {
  const [shown, setShown] = useState(true)
  const [touched, setTouched] = useState(0)
  useEffect(() => {
    if (!shown || !active || busy) return
    const t = window.setTimeout(() => setShown(false), seconds * 1000)
    return () => clearTimeout(t)
  }, [shown, touched, busy, active, seconds])
  return {
    shown,
    toggle: useCallback(() => { setShown((s) => !s); setTouched((n) => n + 1) }, []),
    reveal: useCallback(() => { setShown(true); setTouched((n) => n + 1) }, []),
    hide: useCallback(() => setShown(false), []),
  }
}

/** Full screen through the Fullscreen API; it needs a user gesture, so it is asked for on the first touch. */
export function useFullscreen(on: boolean) {
  useEffect(() => {
    if (!on) {
      if (document.fullscreenElement) document.exitFullscreen().catch(() => undefined)
      return
    }
    const enter = () => { if (!document.fullscreenElement) document.documentElement.requestFullscreen?.().catch(() => undefined) }
    enter()
    window.addEventListener('pointerdown', enter, { capture: true })
    return () => window.removeEventListener('pointerdown', enter, { capture: true })
  }, [on])
}
