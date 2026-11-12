import { fillTemplate, layerLanguage, type Layer, type SelectionContent } from './selection'

/**
 * Actions on a selection, the same model as the Android reader (Actions.kt, ActionConfig.kt) without the ones a
 * browser cannot do: Anki cards, speech and handing text to another app. A new action is one more entry in
 * [ACTIONS]; the panel and the settings pick it up. What an action does to the outside world goes through
 * [ActionHost], which the page implements (view/host.ts), so this module has no browser APIs.
 */
export interface ActionHost {
  isKnown(key: string): boolean
  toggleKnown(key: string): void
  toast(message: string): void
  /** Puts text on the clipboard; false when the browser refused. */
  copy(text: string): Promise<boolean>
  /** Opens a web page in a new tab. */
  openUrl(url: string): void
  /** Hands text to the system share sheet; false where there is none. */
  share(title: string, text: string): Promise<boolean>
}

export interface TextAction {
  id: string
  title(host: ActionHost, c: SelectionContent): string
  /** Whether the action makes sense for this selection; the panel hides it otherwise. */
  accepts?(c: SelectionContent): boolean
  /** [text] is the action's template filled from the selection; [layer] is the layer chosen in the panel. */
  run(host: ActionHost, text: string, c: SelectionContent, layer: Layer): void | Promise<void>
}

export const ACTION_KNOWN = 'known'
export const ACTION_COPY = 'copy'
export const ACTION_TRANSLATE = 'translate'
export const ACTION_SHARE = 'share'

async function copy(host: ActionHost, text: string) {
  host.toast(await host.copy(text) ? 'Скопировано' : 'Браузер не дал скопировать текст')
}

/** The Google Translate page for a text in the given languages. */
export const translateUrl = (text: string, from: string, to: string) =>
  `https://translate.google.com/?sl=${from}&tl=${to}&op=translate&text=${encodeURIComponent(text)}`

export const ACTIONS: TextAction[] = [
  {
    id: ACTION_KNOWN,
    title: (host, c) => (host.isKnown(c.key) ? 'Снова показывать подсказку' : 'Знаю это значение'),
    accepts: (c) => c.key !== '',
    run: (host, _text, c) => host.toggleKnown(c.key),
  },
  { id: ACTION_COPY, title: () => 'Копировать', run: (host, text) => copy(host, text) },
  {
    id: ACTION_TRANSLATE,
    title: () => 'Google Переводчик',
    run: (host, text, c, layer) => host.openUrl(translateUrl(text, layerLanguage(c, layer), layer === 'original' ? c.support : c.studied)),
  },
  {
    // The system share sheet where the browser has one (phones), otherwise the text is copied.
    id: ACTION_SHARE,
    title: () => 'Поделиться',
    run: async (host, text, c) => { if (!(await host.share(c.book, text))) await copy(host, text) },
  },
]

/**
 * The owner's setup of one action: shown or not, shown at once in the selection panel or under "Ещё", and the
 * text template it receives (see [fillTemplate]). The list order is the display order.
 */
export interface ActionConfig { id: string; enabled: boolean; pinned: boolean; template: string }

/** Default order: the reader's own actions first and shown at once (I know this sense, copy), the rest under "Ещё". */
export const ACTION_DEFAULTS: ActionConfig[] = [
  { id: ACTION_KNOWN, enabled: true, pinned: true, template: '' },
  { id: ACTION_COPY, enabled: true, pinned: true, template: '{text}' },
  { id: ACTION_TRANSLATE, enabled: true, pinned: false, template: '{text}' },
  { id: ACTION_SHARE, enabled: true, pinned: false, template: '{text}' },
]

/** Version of the defaults; saved configs of an older version are replaced by the defaults once. */
export const ACTIONS_VERSION = 1

/** Saved configs merged with the actions available: saved ones keep their order and settings, new ones are appended. */
export function mergeActions(saved: ActionConfig[], available: string[] = ACTIONS.map((a) => a.id)): ActionConfig[] {
  const kept = saved.filter((c) => available.includes(c.id))
  const order = ACTION_DEFAULTS.map((c) => c.id)
  const rank = (id: string) => (order.includes(id) ? order.indexOf(id) : order.length)
  const added = available.filter((id) => !kept.some((c) => c.id === id)).sort((a, b) => rank(a) - rank(b))
  return [...kept, ...added.map((id) => ACTION_DEFAULTS.find((c) => c.id === id) ?? { id, enabled: true, pinned: false, template: '{text}' })]
}

/** Moves the action at [index] by [delta] places; out-of-range moves leave the list as it is. */
export function moveAction(list: ActionConfig[], index: number, delta: number): ActionConfig[] {
  const target = index + delta
  if (index < 0 || index >= list.length || target < 0 || target >= list.length) return list
  const next = [...list]
  next.splice(target, 0, ...next.splice(index, 1))
  return next
}

export const runAction = (a: TextAction, cfg: ActionConfig, host: ActionHost, c: SelectionContent, layer: Layer) =>
  a.run(host, fillTemplate(cfg.template, c, layer), c, layer)

/** What an action receives: ready-made choices and an own variant; names map to [fillTemplate] placeholders. */
export const PRESETS: [string, string][] = [
  ['{text}', 'Только выделенное'],
  ['{text}\\n{reference}', 'Выделенное и перевод переводчика'],
  ['{text}\\n{literal}', 'Выделенное и дословный перевод'],
  ['{original} — {gloss}\\n{sentence}', 'Слово, его значение и предложение (для карточек)'],
]
export const FIELDS: [string, string][] = [
  ['{text}', 'выделенное'], ['{original}', 'оригинал'], ['{literal}', 'дословно'], ['{reference}', 'перевод'],
  ['{lemma}', 'словарная форма'], ['{gloss}', 'значение'], ['{sentence}', 'предложение'], ['{chapter}', 'глава'],
  ['{book}', 'книга'], ['\\n', 'новая строка'],
]
/** What the reader's own fixed-content actions do, shown instead of the variants of the sent text. */
export const FIXED: Record<string, string> = {
  [ACTION_KNOWN]: 'Скрывает подсказку к значению слова (или возвращает её). Работает, когда выделено одно слово.',
}
export const SAMPLE: SelectionContent = {
  original: 'molinos', literal: 'мельниц', reference: 'Тут увидели они тридцать или сорок ветряных мельниц.', lemma: 'molino', gloss: 'мельница', form: '',
  key: '', notes: '', sentence: 'En esto, descubrieron treinta o cuarenta molinos de viento que hay en aquel campo.', chapter: 'Capítulo VIII',
  book: 'Don Quijote de la Mancha', studied: 'es', support: 'ru',
}
