import { useEffect, useRef, useState } from 'react'
import type { Book } from '../../core/book'
import type { LibraryBook } from '../hooks'
import { Sheet } from './Sheet'

/** Chapter lists longer than this start folded, as in the Android reader. */
const FOLD_CHAPTERS = 12

/**
 * Navigation drawer: the book being read, opening a book, the other books of the library (where they were left),
 * then the chapters. A long chapter list is folded (header with the current chapter) so the books stay at hand;
 * unfolded, it is scrolled to the current chapter.
 */
export function ChapterMenu({ book, current, library, onChapter, onOpen, onBook, onRemove, onClose }: {
  book: Book | null; current: number; library: LibraryBook[]
  onChapter: (i: number) => void; onOpen: (f: File) => void; onBook: (id: string) => void; onRemove: (id: string) => void; onClose: () => void
}) {
  const input = useRef<HTMLInputElement>(null)
  const chapters = book?.chapters ?? []
  const foldable = chapters.length > FOLD_CHAPTERS
  const [unfolded, setUnfolded] = useState(!foldable)
  const [confirm, setConfirm] = useState<string | null>(null)
  const currentButton = useRef<HTMLButtonElement>(null)
  useEffect(() => { if (unfolded && foldable) currentButton.current?.scrollIntoView({ block: 'center' }) }, [unfolded, foldable])
  const others = library.filter((b) => b.id !== book?.id)
  return (
    <Sheet side="left" label="Меню" onClose={onClose}>
      {book && (
        <>
          <h2 className="drawer-title">{book.title}</h2>
          <p className="note">{book.author}</p>
        </>
      )}
      <nav className="chapters"><button onClick={() => input.current?.click()}>Открыть книгу…</button></nav>
      <input ref={input} type="file" accept=".zip,.bbook.zip,application/zip" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) onOpen(f); e.target.value = '' }} />
      {others.length > 0 && (
        <>
          <h3 className="drawer-section">Другие книги</h3>
          <nav className="chapters books">
            {others.map((b) => (
              <div key={b.id} className="library-book">
                <button onClick={() => onBook(b.id)} data-testid={`book:${b.id}`}>
                  <span>{b.title}</span>
                  <small className="note">{[b.author, `${Math.floor(100 * b.progress)}%`].filter(Boolean).join(' · ')}</small>
                </button>
                {confirm === b.id
                  ? <span className="confirm">Убрать из списка? <button onClick={() => { setConfirm(null); onRemove(b.id) }}>Убрать</button>
                    <button onClick={() => setConfirm(null)}>Нет</button></span>
                  : <button className="icon remove" aria-label={`Убрать «${b.title}»`} onClick={() => setConfirm(b.id)}>×</button>}
              </div>
            ))}
          </nav>
        </>
      )}
      {book && (
        <>
          <hr />
          <button className="drawer-section fold" disabled={!foldable} aria-expanded={unfolded} onClick={() => setUnfolded((u) => !u)}>
            <span>Главы ({chapters.length})</span>
            {!unfolded && <small className="note">{chapters[current]?.title}</small>}
            {foldable && <span aria-hidden="true">{unfolded ? '▴' : '▾'}</span>}
          </button>
          {unfolded && (
            <nav className="chapters">
              {chapters.map((c, i) => (
                <button key={c.index} ref={i === current ? currentButton : undefined} aria-current={i === current} onClick={() => onChapter(i)}>{c.title}</button>
              ))}
            </nav>
          )}
        </>
      )}
    </Sheet>
  )
}
