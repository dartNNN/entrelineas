import { useCallback, useEffect, useMemo, useState } from 'react'
import { ACTIONS_VERSION, mergeActions, type ActionConfig, type ActionHost } from './core/actions'
import { buildBlocks } from './core/blocks'
import { ChapterMenu } from './view/components/ChapterMenu'
import { Icon } from './view/components/Icon'
import { OpenScreen } from './view/components/OpenScreen'
import { Reader } from './view/components/Reader'
import { SelectionPanel } from './view/components/SelectionPanel'
import { FullSettings, QuickSettings } from './view/components/Settings'
import { StatusLine } from './view/components/StatusLine'
import { Toast } from './view/components/Toast'
import { WordCard } from './view/components/WordCard'
import { useChapter, useFullscreen, useLibrary, usePageCounts, useShell } from './view/hooks'
import { CHAPTER_END } from './view/components/usePagination'
import { BookPages } from './core/bookPages'
import { progressItems, type Progress } from './view/progress'
import { setLanguages } from './view/fragment'
import { SelectionResolver, type TextSelection, type WordTap } from './core/selection'
import { DEFAULTS, resolveScheme, SHELL_DEFAULTS, withProgressDefaults, type Settings, type ShellSettings } from './core/settings'
import { browserHost } from './view/host'
import { useStored } from './view/storage'
import { useSystemDark } from './view/system'

/** The app: the book and its chapter, the reader, and what is shown over it (shell, panels, card, selection). */
export default function App() {
  const { book, library, loading, error, open, openFromLibrary, remove, savePosition } = useLibrary()
  if (book) setLanguages(book.studied, book.support)  // before anything of the book is drawn or measured
  const [settings, setSettings] = useStored<Settings>('settings', DEFAULTS)
  const [storedShell, setShell] = useStored<ShellSettings>('shell', SHELL_DEFAULTS)
  const shell = useMemo(() => withProgressDefaults(storedShell), [storedShell])
  const [savedActions, setSavedActions] = useStored<{ version: number; list: ActionConfig[] }>('actions', { version: ACTIONS_VERSION, list: [] })
  const [knownList, setKnownList] = useStored<string[]>('known', [])
  // Where the book being read opens: its library entry, or a chapter just chosen (at its start or end).
  const entry = book ? library.find((b) => b.id === book.id) : undefined
  const [jump, setJump] = useState<{ book: string; chapter: number; paragraph: string } | null>(null)
  const position = jump && jump.book === book?.id ? jump : { book: book?.id ?? '', chapter: entry?.chapter ?? 0, paragraph: entry?.paragraph ?? '' }
  const { counts, counted } = usePageCounts(book?.id)
  const [menu, setMenu] = useState(false)
  const [quick, setQuick] = useState(false)
  const [full, setFull] = useState(false)
  const [tap, setTap] = useState<WordTap | null>(null)
  const [selection, setSelection] = useState<TextSelection | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const clearToast = useCallback(() => setToast(null), [])
  const [layoutMs, setLayoutMs] = useState(0)
  // The screen shown, tagged with its book and chapter: right after a turn to another chapter the old one's is not used.
  const [shown, setScreen] = useState({ at: '', index: 0, total: 0 })
  const known = useMemo(() => new Set(knownList), [knownList])
  const systemDark = useSystemDark()
  const scheme = resolveScheme(shell.scheme, systemDark)
  const actions = useMemo(() => mergeActions(savedActions.version < ACTIONS_VERSION ? [] : savedActions.list), [savedActions])

  useEffect(() => {
    const root = document.documentElement.style
    for (const [k, v] of Object.entries(scheme)) if (typeof v === 'string') root.setProperty(`--${k}`, v)
    document.documentElement.style.colorScheme = scheme.dark ? 'dark' : 'light'
    document.querySelector('meta[name=theme-color]')?.setAttribute('content', scheme.bar)
  }, [scheme])

  const chapterIndex = book && position.book === book.id ? Math.min(position.chapter, book.chapters.length - 1) : 0
  const at = `${book?.id}:${chapterIndex}`
  const screen = shown.at === at ? shown : { at, index: 0, total: 0 }
  const { chapter, error: chapterError } = useChapter(book, chapterIndex)
  const busy = menu || quick || full || tap != null || selection != null
  const bar = useShell(shell.autoHideSeconds, busy, !!book)
  useFullscreen(shell.fullscreen)

  const openFile = useCallback(async (file: File) => {
    const opened = await open(file)
    if (!opened) return
    setSelection(null)
    setJump(null)
    setMenu(false)
  }, [open])
  const goTo = useCallback((chapter: number, atEnd = false) => {
    if (!book || chapter < 0 || chapter >= book.chapters.length) return
    setJump({ book: book.id, chapter, paragraph: atEnd ? CHAPTER_END : '' })
    setSelection(null)
  }, [book])

  const host: ActionHost = useMemo(() => browserHost(known,
    (key) => setKnownList((list) => (list.includes(key) ? list.filter((k) => k !== key) : [...list, key])), setToast), [known, setKnownList])

  useEffect(() => {
    const key = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      if (full) setFull(false)
      else if (selection) setSelection(null)
    }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [full, selection])

  const blocks = useMemo(() => (chapter ? buildBlocks(chapter, settings, known) : []), [chapter, settings, known])
  const sentences = useMemo(() => new Map(chapter?.paragraphs.flatMap((p) => p.sentences.map((s) => [s.id, s] as const)) ?? []), [chapter])
  const resolver = useMemo(() => (book && chapter ? new SelectionResolver(book, chapter) : null), [book, chapter])
  const highlight = useMemo(() => (selection && resolver ? resolver.highlight(selection) : null), [selection, resolver])
  const startParagraph = book && position.book === book.id ? position.paragraph : ''

  const pagesOfBook = useMemo(() => {
    if (!book) return null
    const exact = new Map(counts)
    if (screen.total) exact.set(chapterIndex, screen.total)
    const p = new BookPages(book.chapters.map((c) => c.weight), exact)
    return p.available ? p : null
  }, [book, counts, chapterIndex, screen.total])
  const share = useCallback((index: number, total: number) => {
    if (!book) return 0
    const w = book.chapters.map((c) => c.weight), all = w.reduce((a, b) => a + b, 0)
    const before = w.slice(0, chapterIndex).reduce((a, b) => a + b, 0)
    return (before + (total ? w[chapterIndex] * (index + 1) / total : 0)) / Math.max(1, all)
  }, [book, chapterIndex])
  const onScreen = useCallback((paragraph: string, index: number, total: number) => {
    setScreen({ at, index, total })
    savePosition(chapterIndex, paragraph, share(index, total))
  }, [at, chapterIndex, savePosition, share])
  const onCount = useCallback((layout: string, screens: number) => counted(layout, chapterIndex, screens), [counted, chapterIndex])
  const onEdge = useCallback((direction: number) => goTo(chapterIndex + direction, direction < 0), [goTo, chapterIndex])
  const { toggle, reveal, hide } = bar
  const onBlank = useCallback(() => { if (selection) setSelection(null); else toggle() }, [selection, toggle])
  const onWord = useCallback((t: WordTap) => { setTap(t); hide() }, [hide])
  const onLongPress = useCallback((s: TextSelection) => { setSelection(s); hide() }, [hide])

  if (loading) return <main className="open note">Загрузка…</main>

  const progress: Progress = { page: screen.index, pages: screen.total, chapterIndex, chapters: book?.chapters.length ?? 1, chapterTitle: book?.chapters[chapterIndex]?.title ?? '',
    ...(pagesOfBook && screen.total ? { bookPage: pagesOfBook.pageInBook(chapterIndex, screen.index), bookPages: pagesOfBook.total, estimated: pagesOfBook.estimated } : {}) }
  const status = <StatusLine s={shell} progress={progress} onTap={onBlank} />

  return (
    <div className="app">
      {book && chapter && resolver ? (
        <main className="reading">
          {shell.fullscreen && shell.statusOnTop && status}
          <Reader key={`${book.id}:${chapterIndex}`} blocks={blocks} sentences={sentences} settings={settings} highlight={highlight}
            startParagraph={startParagraph} keys={!busy} onWord={onWord} onLongPress={onLongPress} onBlank={onBlank} onReveal={reveal}
            onScreen={onScreen} onTurn={hide} onLayout={setLayoutMs} onEdge={onEdge} onCount={onCount} />
          {shell.fullscreen
            ? !shell.statusOnTop && status
            // The items chosen in the settings; data-chapter always holds the chapter's "page / pages", for tests.
            : <div className="page-counter" data-testid="page-counter" data-chapter={`${screen.index + 1} / ${screen.total || '…'}`} onClick={onBlank}>
              {progressItems(progress, shell).join('   ·   ') || '\u00a0'}</div>}
          {selection && (
            <SelectionPanel sel={selection} content={resolver.content(selection)} units={resolver.units(selection)} configs={actions} host={host}
              onChange={setSelection} onClose={() => setSelection(null)} />
          )}
        </main>
      ) : book && !chapterError ? (
        <main className="open note">Загрузка главы…</main>
      ) : (
        <OpenScreen onOpen={openFile} error={error ?? chapterError} />
      )}
      <header className={`topbar ${bar.shown || !book ? '' : 'hidden'}`}>
        <button className="icon" onClick={() => setMenu(true)} aria-label="Меню"><Icon name="menu" /></button>
        <h1>{book?.chapters[chapterIndex]?.title ?? 'Entrelíneas'}</h1>
        {book && <button className="icon" onClick={() => setQuick(true)} aria-label="Настройки"><Icon name="settings" /></button>}
      </header>
      {menu && (
        <ChapterMenu book={book} current={chapterIndex} library={library} onClose={() => setMenu(false)} onOpen={openFile}
          onBook={(id) => { setJump(null); setSelection(null); setMenu(false); void openFromLibrary(id) }} onRemove={remove}
          onChapter={(i) => { goTo(i); setMenu(false) }} />
      )}
      {quick && (
        <QuickSettings settings={settings} shell={shell} systemDark={systemDark} book={book} chapter={chapter} layoutMs={layoutMs}
          onSettings={setSettings} onShell={setShell} onAll={() => { setQuick(false); setFull(true) }} onClose={() => setQuick(false)} />
      )}
      {full && (
        <FullSettings settings={settings} shell={shell} systemDark={systemDark} book={book} chapter={chapter} layoutMs={layoutMs}
          onSettings={setSettings} onShell={setShell} actions={actions} host={host}
          onActions={(list) => setSavedActions({ version: ACTIONS_VERSION, list })} onBack={() => setFull(false)} />
      )}
      {tap && chapter && (
        <WordCard tap={tap} chapter={chapter} settings={settings} known={known} onToggleKnown={host.toggleKnown} onClose={() => setTap(null)} />
      )}
      {toast && <Toast message={toast} onDone={clearToast} />}
    </div>
  )
}
