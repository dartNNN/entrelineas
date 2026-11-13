import { del, get, set } from 'idb-keyval'
import { useCallback, useEffect, useState } from 'react'
import { loadBook, type Book, type Chapter } from '../core/book'

/**
 * A book in the library: where it was left and when it was last open; progress, the share read (0..1) when left.
 * The package itself is kept in IndexedDB under "book:<id>" (it can be several megabytes), the list in localStorage.
 */
export interface LibraryBook { id: string; title: string; author: string; chapter: number; paragraph: string; opened: number; progress: number }

const LIBRARY = 'entrelineas:library'
/** The single book and position of versions up to 0.6. */
const LEGACY_BOOK = 'book'
const LEGACY_POSITION = 'entrelineas:position'

function readLibrary(): LibraryBook[] {
  try {
    const list = JSON.parse(localStorage.getItem(LIBRARY) ?? '[]') as LibraryBook[]
    return [...list].sort((a, b) => b.opened - a.opened)
  } catch {
    return []
  }
}

function writeLibrary(list: LibraryBook[]) {
  try { localStorage.setItem(LIBRARY, JSON.stringify(list)) } catch { /* storage unavailable: keep in memory only */ }
}

const message = (e: unknown) => (e instanceof Error ? e.message : String(e))

/**
 * The library and the book being read: "?book=<url>" opens a package published somewhere (it must allow cross-origin
 * reads), otherwise the most recent book of the library comes back where it was left. open() takes a file the owner
 * chose; a book opened again (same id) keeps its position. A browser without storage just forgets.
 */
export function useLibrary() {
  const [book, setBook] = useState<Book | null>(null)
  const [library, setLibrary] = useState<LibraryBook[]>(readLibrary)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const update = useCallback((change: (list: LibraryBook[]) => LibraryBook[]) => {
    setLibrary((list) => { const next = change(list).sort((a, b) => b.opened - a.opened); writeLibrary(next); return next })
  }, [])

  /** Puts a loaded package first in the library (keeping its position) and shows it. */
  const show = useCallback((opened: Book, data: ArrayBuffer | null, start?: { chapter: number; paragraph: string }) => {
    if (data) set(`book:${opened.id}`, data).catch(() => undefined)
    update((list) => {
      const old = list.find((b) => b.id === opened.id)
      const entry: LibraryBook = { ...(old ?? { chapter: 0, paragraph: '', progress: 0 }), ...(start ?? {}),
        id: opened.id, title: opened.title, author: opened.author, opened: Date.now() }
      return [entry, ...list.filter((b) => b.id !== opened.id)]
    })
    setBook(opened)
  }, [update])

  useEffect(() => {
    const url = new URLSearchParams(window.location.search).get('book')
    const migrate = async () => {
      // Versions up to 0.6: one book under "book" and its position in localStorage.
      const data = await get<ArrayBuffer>(LEGACY_BOOK).catch(() => undefined)
      if (!data) return false
      const opened = await loadBook(data).catch(() => null)
      if (!opened) return false
      let start: { chapter: number; paragraph: string } | undefined
      try {
        const p = JSON.parse(localStorage.getItem(LEGACY_POSITION) ?? 'null') as { book: string; chapter: number; paragraph: string } | null
        if (p && p.book === opened.id) start = { chapter: p.chapter, paragraph: p.paragraph }
      } catch { /* no position */ }
      show(opened, data, start)
      await del(LEGACY_BOOK).catch(() => undefined)
      return true
    }
    const recent = async () => {
      const first = readLibrary()[0]
      const data = first && await get<ArrayBuffer>(`book:${first.id}`).catch(() => undefined)
      if (data) show(await loadBook(data), null)
    }
    const start = url
      ? fetch(url).then((r) => { if (!r.ok) throw new Error(`Не удалось скачать книгу: ${r.status}`); return r.arrayBuffer() })
        .then(async (data) => show(await loadBook(data), data))
        .catch((e: unknown) => setError(message(e)))
      : migrate().then((done) => (done ? undefined : recent())).catch(() => undefined)
    start.finally(() => setLoading(false))
  }, [show])

  const open = useCallback(async (file: File): Promise<Book | null> => {
    try {
      const data = await file.arrayBuffer()
      const opened = await loadBook(data)
      show(opened, data)
      setError(null)
      return opened
    } catch (e) {
      setError(message(e))
      return null
    }
  }, [show])

  /** Switches to another book of the library, where it was left. */
  const openFromLibrary = useCallback(async (id: string) => {
    try {
      const data = await get<ArrayBuffer>(`book:${id}`)
      if (!data) throw new Error('Файл книги не сохранился, откройте его заново')
      show(await loadBook(data), null)
      setError(null)
    } catch (e) {
      setError(message(e))
    }
  }, [show])

  /** Removes a book (not the one being read) from the library and its stored package. */
  const remove = useCallback((id: string) => {
    if (id === book?.id) return
    del(`book:${id}`).catch(() => undefined)
    try { localStorage.removeItem(`entrelineas:pages:${id}`) } catch { /* nothing stored */ }
    update((list) => list.filter((b) => b.id !== id))
  }, [book, update])

  /** The position in the book being read: chapter, paragraph of the screen shown, share of the book read. */
  const savePosition = useCallback((chapter: number, paragraph: string, progress: number) => {
    if (!book) return
    update((list) => list.map((b) => (b.id === book.id ? { ...b, chapter, paragraph, progress } : b)))
  }, [book, update])

  return { book, library, loading, error, open, openFromLibrary, remove, savePosition }
}

/**
 * Pages of the laid-out chapters of [bookId] for one layout key, kept in localStorage: a new key starts empty (the
 * old counts are for other settings or another page size).
 */
export function usePageCounts(bookId: string | undefined) {
  const [state, setState] = useState<{ book?: string; key: string; counts: Map<number, number> }>({ key: '', counts: new Map() })
  const counted = useCallback((key: string, chapter: number, pages: number | null) => {
    if (!bookId) return
    setState((s) => {
      let base = s
      if (s.book !== bookId || s.key !== key) {
        let counts = new Map<number, number>()
        try {
          const o = JSON.parse(localStorage.getItem(`entrelineas:pages:${bookId}`) ?? 'null') as { key: string; counts: Record<string, number> } | null
          if (o && o.key === key) counts = new Map(Object.entries(o.counts).map(([k, v]) => [Number(k), v]))
        } catch { /* nothing stored */ }
        base = { book: bookId, key, counts }
      }
      if (pages == null || base.counts.get(chapter) === pages) return base
      const counts = new Map(base.counts).set(chapter, pages)
      try {
        localStorage.setItem(`entrelineas:pages:${bookId}`, JSON.stringify({ key, counts: Object.fromEntries(counts) }))
      } catch { /* storage unavailable */ }
      return { ...base, counts }
    })
  }, [bookId])
  return { counts: state.book === bookId ? state.counts : new Map<number, number>(), counted }
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
