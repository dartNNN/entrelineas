import type { Chapter } from '../../core/book'
import type { Settings } from '../../core/settings'
import type { WordTap } from '../../core/selection'
import { Sheet } from './Sheet'

const LABELS = { grammar: 'Грамматика', context: 'Контекст', variants: 'Варианты', expression: 'Выражение' } as const

/**
 * Everything about a word in this place, as in the Android word card: entry, the whole dictionary entry for books
 * built from a dictionary, the segment with its literal translation, the published translation, footnotes, notes,
 * and the familiar toggle.
 */
export function WordCard({ tap, chapter, settings, known, onToggleKnown, onClose }: {
  tap: WordTap; chapter: Chapter; settings: Settings; known: ReadonlySet<string>; onToggleKnown: (key: string) => void; onClose: () => void
}) {
  const s = chapter.paragraphs.flatMap((p) => p.sentences).find((x) => x.id === tap.sentence)
  if (!s) return null
  const i = tap.token
  const lex = s.lexical.get(i)
  const segment = s.segments.find((g) => i >= g.first && i <= g.last)
  const entry = lex ? chapter.entries[lex.key.split('#')[0]] : undefined
  // The chosen sense in the entry: the explicit id (books built with a model, schema 1.5), else the key's sense id.
  const sense = lex?.sense ?? lex?.key.split('#')[1]
  // The entry stands right under the gloss when the sense was chosen from the dictionary alone (it is the gloss's
  // source), after everything else when a model chose it.
  const entryFirst = lex?.from === 'dictionary'
  const dictionary = entry && (
    <section data-testid="dictionary-entry">
      <div className="label">Словарь: {entry.headword}</div>
      {entry.senses.map((x) => <div key={x.id} className={`sense ${x.id === sense ? 'chosen' : ''}`}>{x.id}. {x.text}{x.gram ? ` (${x.gram})` : ''}</div>)}
    </section>
  )
  const notes = s.notes.filter((n) => i >= n.first && i <= n.last)
  const text = (a: number, b: number) => s.tokens.slice(a, b + 1).map((t, k) => t + (a + k < b ? s.ws[a + k] ?? ' ' : '')).join('')
  const ref = settings.referenceInCard ? s.reference : null
  return (
    <Sheet label="Карточка слова" onClose={onClose}>
      <div className="word-card" data-testid="word-card">
        <h2 lang="es">{s.tokens[i]}</h2>
        {lex && (
          <>
            <div className="label">{[lex.lemma, lex.gram].filter(Boolean).join(' · ')}</div>
            <div className="big" lang="ru">{lex.gloss}</div>
            {lex.form && <div>{lex.form}</div>}
            <div className="note">появление №{lex.n}</div>
          </>
        )}
        {entryFirst && dictionary}
        {segment && (
          <section>
            <div className="label" lang="es">{text(segment.first, segment.last)}</div>
            <div lang="ru">{segment.literal}</div>
            {segment.literary && <div lang="ru">= {segment.literary}</div>}
          </section>
        )}
        {ref && (
          <section lang="ru">
            <div className="label">Перевод переводчика{ref.uncertain ? ' · сопоставление сомнительное' : ref.loose ? ' · вольный' : ''}</div>
            <div>{ref.text ?? 'Переводчик пропустил это предложение'}</div>
            {settings.showFootnotes && ref.notes.map((n, k) => <div key={k} className="small">Сноска: {n}</div>)}
          </section>
        )}
        {settings.showAuthorNotes && s.authorNotes.length > 0 && <section>{s.authorNotes.map((n, k) => <div key={k} className="small">Сноска автора: {n}</div>)}</section>}
        {notes.length > 0 && <section lang="ru">{notes.map((n, k) => <p key={k}>{LABELS[n.kind]}: {n.text}</p>)}</section>}
        {!entryFirst && dictionary}
        {lex && <button className="outlined" onClick={() => onToggleKnown(lex.key)}>{known.has(lex.key) ? 'Снова показывать подсказку' : 'Знаю это значение'}</button>}
      </div>
    </Sheet>
  )
}
