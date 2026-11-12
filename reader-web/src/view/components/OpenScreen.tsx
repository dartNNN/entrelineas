import { useRef, useState } from 'react'

/** No book yet: open a .bbook.zip by button or by dropping it. Books are not part of the site; readers build their own. */
export function OpenScreen({ onOpen, error }: { onOpen: (f: File) => void; error: string | null }) {
  const input = useRef<HTMLInputElement>(null)
  const [over, setOver] = useState(false)
  return (
    <main
      className={`open ${over ? 'over' : ''}`}
      onDragOver={(e) => { e.preventDefault(); setOver(true) }}
      onDragLeave={() => setOver(false)}
      onDrop={(e) => { e.preventDefault(); setOver(false); const f = e.dataTransfer.files[0]; if (f) onOpen(f) }}
    >
      <h2>Entrelíneas</h2>
      <p>Читалка книг с подсказками между строк, в духе метода Ильи Франка: оригинал, дословный перевод сегментов, значения слов, перевод переводчика.</p>
      <button className="primary" onClick={() => input.current?.click()}>Открыть книгу (.bbook.zip)</button>
      <p className="muted">или перетащите файл сюда. Книга открывается прямо в браузере и никуда не отправляется.</p>
      <input ref={input} type="file" accept=".zip,.bbook.zip,application/zip" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) onOpen(f); e.target.value = '' }} />
      {error && <p className="error" role="alert">{error}</p>}
      <p className="muted small">Книг на сайте нет: файл собирается сборщиком из оригинала и перевода, которые у вас есть.</p>
    </main>
  )
}
