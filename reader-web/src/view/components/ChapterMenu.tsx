import { useRef } from 'react'
import type { Book } from '../../core/book'
import { Sheet } from './Sheet'

/** Navigation drawer: the book, its chapters (the current one selected), opening another book. */
export function ChapterMenu({ book, current, onChapter, onOpen, onClose }: {
  book: Book | null; current: number; onChapter: (i: number) => void; onOpen: (f: File) => void; onClose: () => void
}) {
  const input = useRef<HTMLInputElement>(null)
  return (
    <Sheet side="left" label="Меню" onClose={onClose}>
      {book && (
        <>
          <h2 className="drawer-title">{book.title}</h2>
          <p className="note">{book.author}</p>
          <hr />
          <nav className="chapters">
            {book.chapters.map((c, i) => <button key={c.index} aria-current={i === current} onClick={() => onChapter(i)}>{c.title}</button>)}
          </nav>
          <hr />
        </>
      )}
      <nav className="chapters"><button onClick={() => input.current?.click()}>Открыть книгу</button></nav>
      <input ref={input} type="file" accept=".zip,.bbook.zip,application/zip" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) onOpen(f); e.target.value = '' }} />
    </Sheet>
  )
}
