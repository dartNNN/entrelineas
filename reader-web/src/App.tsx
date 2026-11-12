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
import { useBook, useChapter, useFullscreen, useShell } from './view/hooks'
import { SelectionResolver, type TextSelection, type WordTap } from './core/selection'
import { DEFAULTS, resolveScheme, SHELL_DEFAULTS, type Settings, type ShellSettings } from './core/settings'
import { browserHost } from './view/host'
import { useStored } from './view/storage'
import { useSystemDark } from './view/system'

type Position = { book: string; chapter: number; paragraph: string }

/** The app: the book and its chapter, the reader, and what is shown over it (shell, panels, card, selection). */
export default function App() {
  const { book, loading, error, open } = useBook()
  const [settings, setSettings] = useStored<Settings>('settings', DEFAULTS)
  const [shell, setShell] = useStored<ShellSettings>('shell', SHELL_DEFAULTS)
  const [savedActions, setSavedActions] = useStored<{ version: number; list: ActionConfig[] }>('actions', { version: ACTIONS_VERSION, list: [] })
  const [knownList, setKnownList] = useStored<string[]>('known', [])
  const [position, setPosition] = useStored<Position>('position', { book: '', chapter: 0, paragraph: '' })
  const [menu, setMenu] = useState(false)
  const [quick, setQuick] = useState(false)
  const [full, setFull] = useState(false)
  const [tap, setTap] = useState<WordTap | null>(null)
  const [selection, setSelection] = useState<TextSelection | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const clearToast = useCallback(() => setToast(null), [])
  const [layoutMs, setLayoutMs] = useState(0)
  const [screen, setScreen] = useState({ index: 0, total: 0 })
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
  const { chapter, error: chapterError } = useChapter(book, chapterIndex)
  const busy = menu || quick || full || tap != null || selection != null
  const bar = useShell(shell.autoHideSeconds, busy, !!book)
  useFullscreen(shell.fullscreen)

  const openFile = useCallback(async (file: File) => {
    const opened = await open(file)
    if (!opened) return
    setSelection(null)
    setPosition((p) => (p.book === opened.id ? p : { book: opened.id, chapter: 0, paragraph: '' }))
    setMenu(false)
  }, [open, setPosition])

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

  const onScreen = useCallback((paragraph: string, index: number, total: number) => {
    setScreen({ index, total })
    if (book) setPosition({ book: book.id, chapter: chapterIndex, paragraph })
  }, [book, chapterIndex, setPosition])
  const { toggle, reveal, hide } = bar
  const onBlank = useCallback(() => { if (selection) setSelection(null); else toggle() }, [selection, toggle])
  const onWord = useCallback((t: WordTap) => { setTap(t); hide() }, [hide])
  const onLongPress = useCallback((s: TextSelection) => { setSelection(s); hide() }, [hide])

  if (loading) return <main className="open note">Загрузка…</main>

  const progress = { page: screen.index, pages: screen.total, chapterIndex, chapters: book?.chapters.length ?? 1, chapterTitle: book?.chapters[chapterIndex]?.title ?? '' }
  const status = <StatusLine s={shell} progress={progress} onTap={onBlank} />

  return (
    <div className="app">
      {book && chapter && resolver ? (
        <main className="reading">
          {shell.fullscreen && shell.statusOnTop && status}
          <Reader key={`${book.id}:${chapterIndex}`} blocks={blocks} sentences={sentences} settings={settings} highlight={highlight}
            startParagraph={startParagraph} keys={!busy} onWord={onWord} onLongPress={onLongPress} onBlank={onBlank} onReveal={reveal}
            onScreen={onScreen} onTurn={hide} onLayout={setLayoutMs} />
          {shell.fullscreen
            ? !shell.statusOnTop && status
            : <div className="page-counter" data-testid="page-counter" onClick={onBlank}>{screen.total ? `${screen.index + 1} / ${screen.total}` : '…'}</div>}
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
        <ChapterMenu book={book} current={chapterIndex} onClose={() => setMenu(false)} onOpen={openFile}
          onChapter={(i) => { if (book) setPosition({ book: book.id, chapter: i, paragraph: '' }); setSelection(null); setMenu(false) }} />
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
