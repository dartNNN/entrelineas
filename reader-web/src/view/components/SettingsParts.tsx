import { useState, type ReactNode } from 'react'
import { ACTIONS, FIELDS, FIXED, moveAction, PRESETS, SAMPLE, type ActionConfig, type ActionHost } from '../../core/actions'
import type { Book, Chapter } from '../../core/book'
import { fillTemplate } from '../../core/selection'
import { hasHints, resolveScheme, SCHEME_TITLES, SCHEMES, type Repeat, type SchemeId, type Settings, type ShellSettings } from '../../core/settings'

/**
 * Settings blocks shared by the quick panel and the full settings screen, as in the Android reader (SettingsUi.kt).
 * Every switch that changes the page shows a sample of what it adds in the page's own colours, faded when off.
 */
export function Section({ title, note }: { title: string; note?: string }) {
  return (
    <>
      <h4 className="section">{title}</h4>
      {note && <p className="note">{note}</p>}
    </>
  )
}

export function Toggle({ label, value, note, sample, disabled, onChange }: {
  label: string; value: boolean; note?: string; sample?: ReactNode; disabled?: boolean; onChange: (v: boolean) => void
}) {
  return (
    <div className={`toggle ${disabled ? 'disabled' : ''}`}>
      <label>
        <span>{label}</span>
        <input type="checkbox" role="switch" aria-label={label} checked={value} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      </label>
      {note && <p className="note">{note}</p>}
      {sample && <div className={`sample ${value ? '' : 'off'}`} lang="es">{sample}</div>}
    </div>
  )
}

function Slider({ label, value, min, max, disabled, onChange, testId }: {
  label: string; value: number; min: number; max: number; disabled?: boolean; onChange: (v: number) => void; testId?: string
}) {
  return (
    <label className="slider">
      <span>{label}</span>
      <input type="range" min={min} max={max} value={value} disabled={disabled} data-testid={testId} onChange={(e) => onChange(+e.target.value)} />
    </label>
  )
}

/** Scheme choice as swatches: page colour with text, gloss and literal in their colours. */
export function SchemePicker({ current, systemDark, onPick }: { current: SchemeId; systemDark: boolean; onPick: (id: SchemeId) => void }) {
  return (
    <div className="schemes">
      {(Object.keys(SCHEME_TITLES) as SchemeId[]).map((id) => {
        const c = id === 'system' ? resolveScheme(id, systemDark) : SCHEMES[id]
        return (
          <button key={id} className="scheme" aria-pressed={current === id} onClick={() => onPick(id)}>
            <span className="swatch" style={{ background: c.page, color: c.text, borderColor: current === id ? c.accent : c.bar, borderWidth: current === id ? 2 : 1 }}>
              Aa<span className="bars"><i style={{ background: c.gloss }} /><i style={{ background: c.literal }} /></span>
            </span>
            <small>{SCHEME_TITLES[id]}</small>
          </button>
        )
      })}
    </div>
  )
}

/** What the page shows around the original: literal translations, glosses, notes, and the repeat without them. */
export function HintSettings({ s, book, onChange }: { s: Settings; book: Book | null; onChange: (s: Settings) => void }) {
  const set = (patch: Partial<Settings>) => onChange({ ...s, ...patch })
  const repeats: [Repeat, string, string][] = [
    ['off', 'Нет', ''], ['paragraph', 'Каждый абзац под ним', 'как в книгах Франка'],
    ['screen', 'Весь экран следующим экраном', 'сначала экран с подсказками, свайп — он же без них'],
  ]
  return (
    <>
      <Section title="Подсказки в тексте" note="Всё, что стоит рядом с испанским текстом. Образец под переключателем показывает, как это выглядит." />
      <Toggle label="Дословный перевод сегментов" value={s.showLiteral} note="Перевод каждого смыслового сегмента в скобках, курсивом"
        sample={<>En un lugar<span className="literal"> (В одном месте)</span> de la Mancha</>} onChange={(v) => set({ showLiteral: v })} />
      <Toggle label="Подсказки к словам" value={s.showLexical} note="Значение слова в квадратных скобках сразу после него"
        sample={<>Molinos<span className="gloss"> [мельницы]</span> de viento<span className="gloss"> [ветер]</span></>} onChange={(v) => set({ showLexical: v })} />
      <Slider label={`Подсказка к значению слова — первые ${s.threshold} раз`} value={s.threshold} min={1} max={book?.maxOccurrence ?? 20}
        disabled={!s.showLexical} testId="threshold" onChange={(v) => set({ threshold: v })} />
      <p className="note">Каждое значение подсказывается первые N раз за книгу, потом исчезает. Знакомое можно скрыть сразу: карточка слова → «Знаю это значение».</p>
      <Toggle label="Грамматика и контекст" value={s.showNotes} note="Мелкие заметки под абзацем: ◦ грамматика, ※ реалии и контекст"
        sample={<span className="notes-sample">◦ грамматика: de cuyo nombre — «чьё имя», относительное притяжательное cuyo<br />※ контекст: Ламанча — край в Кастилии</span>}
        onChange={(v) => set({ showNotes: v })} />

      <Section title="Повтор без подсказок" note="Тот же текст ещё раз, чистым, чтобы перечитать без опоры." />
      {!hasHints(s) && <p className="note warn" data-testid="repeat-off">Не показывается, пока выключены все подсказки: текст и так без них.</p>}
      {repeats.map(([mode, label, note]) => (
        <label key={mode} className={`radio ${hasHints(s) ? '' : 'disabled'}`}>
          <input type="radio" name="repeat" checked={s.repeat === mode} disabled={!hasHints(s)} onChange={() => set({ repeat: mode })} />
          <span>{label}{note && <small className="note"> — {note}</small>}</span>
        </label>
      ))}
    </>
  )
}

/** The translator's text and both kinds of footnotes; shown only when the chapter being read has them. */
export function TranslationSettings({ s, chapter, onChange }: { s: Settings; chapter: Chapter | null; onChange: (s: Settings) => void }) {
  const set = (patch: Partial<Settings>) => onChange({ ...s, ...patch })
  const sentences = chapter?.paragraphs.flatMap((p) => p.sentences) ?? []
  const hasReference = sentences.some((x) => x.reference)
  const hasFootnotes = sentences.some((x) => x.reference?.notes.length)
  const hasAuthorNotes = sentences.some((x) => x.authorNotes.length)
  if (!hasReference && !hasFootnotes && !hasAuthorNotes) return null
  return (
    <>
      <Section title="Перевод переводчика и сноски" note="Опубликованный русский перевод книги и сноски к ней." />
      {hasReference && <Toggle label="Перевод переводчика в карточке" value={s.referenceInCard} note="В карточке слова — перевод всего предложения"
        onChange={(v) => set({ referenceInCard: v })} />}
      {hasReference && <Toggle label="Перевод переводчика после абзаца" value={s.referenceInText} note="Под каждым абзацем, мелко"
        sample={<span className="reference-sample">В одном селе Ламанчи, название которого не хочу вспоминать…</span>} onChange={(v) => set({ referenceInText: v })} />}
      {hasFootnotes && <Toggle label="Сноски переводчика" value={s.showFootnotes} note="Под переводом переводчика и в карточке"
        sample={<span className="footnote-sample">* Сноска переводчика к этому месту</span>} onChange={(v) => set({ showFootnotes: v })} />}
      {hasAuthorNotes && <Toggle label="Сноски автора" value={s.showAuthorNotes} note="Сноски из испанского издания: под абзацем и в карточке"
        sample={<span className="footnote-sample">* Сноска автора книги</span>} onChange={(v) => set({ showAuthorNotes: v })} />}
    </>
  )
}

export function FontSize({ s, onChange }: { s: Settings; onChange: (s: Settings) => void }) {
  return <Slider label={`Размер шрифта ${s.fontSize}`} value={s.fontSize} min={14} max={26} testId="font-size" onChange={(v) => onChange({ ...s, fontSize: v })} />
}

export function Margin({ s, onChange }: { s: Settings; onChange: (s: Settings) => void }) {
  return <Slider label={`Поля ${s.margin}`} value={s.margin} min={4} max={48} testId="margin" onChange={(v) => onChange({ ...s, margin: v })} />
}

export function TypographySettings({ s, onChange }: { s: Settings; onChange: (s: Settings) => void }) {
  return (
    <>
      <Section title="Шрифт" />
      <FontSize s={s} onChange={onChange} />
      <Margin s={s} onChange={onChange} />
      <Toggle label="Выравнивание по ширине" value={s.justify} note="Строки ровные с обеих сторон, как в книге" onChange={(v) => onChange({ ...s, justify: v })} />
    </>
  )
}

/** Colour scheme, full screen with its status line, and the top bar. */
export function ViewSettings({ shell, systemDark, onShell }: { shell: ShellSettings; systemDark: boolean; onShell: (s: ShellSettings) => void }) {
  const set = (patch: Partial<ShellSettings>) => onShell({ ...shell, ...patch })
  const on = shell.fullscreen
  return (
    <>
      <Section title="Цветовая схема" note="«Как в системе» — «Бумага» днём и «Сумерки», когда устройство в тёмной теме." />
      <SchemePicker current={shell.scheme} systemDark={systemDark} onPick={(scheme) => set({ scheme })} />

      <Section title="Верхняя панель" note="Меню и настройки. Появляется от касания места без слова, строки внизу экрана или свайпа вверх-вниз." />
      <Slider label={`Скрывать через ${shell.autoHideSeconds} с`} value={shell.autoHideSeconds} min={2} max={10} testId="auto-hide"
        onChange={(v) => set({ autoHideSeconds: v })} />

      <Section title="Полноэкранный режим" note="Браузер во весь экран, без своих панелей; вместо них — своя строка состояния." />
      <Toggle label="Полноэкранный режим" value={shell.fullscreen} onChange={(v) => set({ fullscreen: v })} />
      <div className={on ? '' : 'faded'}>
        <p className="label">Строка состояния</p>
        {([[true, 'Сверху'], [false, 'Снизу']] as [boolean, string][]).map(([top, label]) => (
          <label key={label} className={`radio ${on ? '' : 'disabled'}`}>
            <input type="radio" name="status" checked={shell.statusOnTop === top} disabled={!on} onChange={() => set({ statusOnTop: top })} /> <span>{label}</span>
          </label>
        ))}
        <Toggle label="Часы" value={shell.clock} onChange={(v) => set({ clock: v })} />
        <Toggle label="Батарея" value={shell.battery} note="Где браузер сообщает заряд" onChange={(v) => set({ battery: v })} />
        <Toggle label="Название главы" value={shell.chapterTitle} onChange={(v) => set({ chapterTitle: v })} />
      </div>
      <Section title="Прогресс" note="Под страницей и в строке состояния полноэкранного режима. Страницы книги до конца не посчитаны — перед числом «~»." />
      <p className="field-label">Глава</p>
      <Toggle label="Страницы главы" value={shell.page} note="12 / 40" onChange={(v) => set({ page: v })} />
      <Toggle label="Прогресс главы" value={shell.chapterProgress} note="30%" onChange={(v) => set({ chapterProgress: v })} />
      <p className="field-label">Книга</p>
      <Toggle label="Страницы книги" value={shell.bookPages} note="123 / ~860" onChange={(v) => set({ bookPages: v })} />
      <Toggle label="Прогресс книги" value={shell.bookProgress} note="14%" onChange={(v) => set({ bookProgress: v })} />
    </>
  )
}

/** The actions of the selection panel: shown at once or under "Ещё", in order, with what each receives. */
export function ActionsSettings({ configs, host, onChange }: { configs: ActionConfig[]; host: ActionHost; onChange: (c: ActionConfig[]) => void }) {
  return (
    <>
      <Section title="Действия с выделенным" note="Долгое нажатие на слово выделяет его, внизу появляется панель с действиями. Здесь — какие действия там есть, какие видны сразу, а какие спрятаны в «Ещё», и что каждое получает." />
      {configs.map((cfg, i) => {
        const action = ACTIONS.find((a) => a.id === cfg.id)
        if (!action) return null
        return <ActionCard key={cfg.id} title={action.title(host, SAMPLE)} cfg={cfg} first={i === 0} last={i === configs.length - 1}
          onChange={(next) => onChange(configs.map((c, k) => (k === i ? next : c)))} onMove={(d) => onChange(moveAction(configs, i, d))} />
      })}
    </>
  )
}

const example = (template: string) => fillTemplate(template, SAMPLE, 'original').replaceAll('\n', ' ⏎ ')

function ActionCard({ title, cfg, first, last, onChange, onMove }: {
  title: string; cfg: ActionConfig; first: boolean; last: boolean; onChange: (c: ActionConfig) => void; onMove: (d: number) => void
}) {
  const [open, setOpen] = useState(false)
  const preset = PRESETS.find(([t]) => t === cfg.template)?.[1] ?? 'Свой вариант'
  return (
    <div className="action-card" data-testid={`action:${cfg.id}`}>
      <div className="row">
        <input type="checkbox" role="switch" aria-label={title} checked={cfg.enabled} onChange={(e) => onChange({ ...cfg, enabled: e.target.checked })} />
        <strong>{title}</strong>
        <button className="icon" disabled={first} onClick={() => onMove(-1)} aria-label="Выше">↑</button>
        <button className="icon" disabled={last} onClick={() => onMove(1)} aria-label="Ниже">↓</button>
      </div>
      {cfg.enabled && (
        <>
          <div className="chips">
            <button className="chip" aria-pressed={cfg.pinned} onClick={() => onChange({ ...cfg, pinned: true })}>Видна сразу</button>
            <button className="chip" aria-pressed={!cfg.pinned} onClick={() => onChange({ ...cfg, pinned: false })}>В «Ещё»</button>
          </div>
          {FIXED[cfg.id] ? <p className="note">{FIXED[cfg.id]}</p> : (
            <>
              <div className="row">
                <div className="grow">
                  <div>Передаётся: {preset}</div>
                  <div className="note">Например: {example(cfg.template)}</div>
                </div>
                <button className="text" onClick={() => setOpen(!open)} data-testid={`what:${cfg.id}`}>{open ? 'Готово' : 'Изменить'}</button>
              </div>
              {open && <TemplateChooser template={cfg.template} onChange={(template) => onChange({ ...cfg, template })} />}
            </>
          )}
        </>
      )}
    </div>
  )
}

/** Ready-made variants of the text an action receives, and an own variant built from named fields. */
function TemplateChooser({ template, onChange }: { template: string; onChange: (t: string) => void }) {
  const own = !PRESETS.some(([t]) => t === template)
  return (
    <div className="templates">
      {PRESETS.map(([value, label]) => (
        <label key={value} className="radio">
          <input type="radio" name="template" checked={template === value} onChange={() => onChange(value)} />
          <span>{label}<small className="note"><br />{example(value)}</small></span>
        </label>
      ))}
      <label className="radio">
        <input type="radio" name="template" checked={own} onChange={() => { if (!own) onChange(`${template} `) }} /> <span>Свой вариант</span>
      </label>
      {own && (
        <>
          <p className="note">Нажимайте поля, чтобы добавить их; текст между ними передаётся как есть.</p>
          <div className="chips">{FIELDS.map(([field, label]) => <button key={field} className="chip" onClick={() => onChange(template + field)}>+ {label}</button>)}</div>
          <textarea value={template} rows={2} onChange={(e) => onChange(e.target.value)} data-testid="template" />
        </>
      )}
    </div>
  )
}
