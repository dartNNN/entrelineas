import schemes from '@format/schemes.json'

/**
 * Reader settings, the same choices and defaults as the Android reader: [Settings] change what the page shows,
 * [ShellSettings] the app around it (scheme, full screen and its status line, how long the top bar stays).
 */

/**
 * Rereading without hints: none; per paragraph, the plain paragraph right under its hinted version (Frank's books);
 * per screen, after each screen with hints the next one holds exactly the same text without any hints.
 */
export type Repeat = 'off' | 'paragraph' | 'screen'
export type SchemeId = 'system' | 'paper' | 'sepia' | 'dusk' | 'night'

export interface Settings {
  threshold: number
  showLiteral: boolean
  showLexical: boolean
  showNotes: boolean
  repeat: Repeat
  fontSize: number
  referenceInCard: boolean
  referenceInText: boolean
  showFootnotes: boolean
  showAuthorNotes: boolean
  /** Body text justified to both margins. */
  justify: boolean
}

export const DEFAULTS: Settings = {
  threshold: 10, showLiteral: true, showLexical: true, showNotes: true, repeat: 'paragraph', fontSize: 18,
  referenceInCard: true, referenceInText: false, showFootnotes: true, showAuthorNotes: true, justify: true,
}

export interface ShellSettings {
  scheme: SchemeId
  fullscreen: boolean
  statusOnTop: boolean
  clock: boolean
  battery: boolean
  page: boolean
  chapterProgress: boolean
  bookProgress: boolean
  chapterTitle: boolean
  /** Seconds the top bar stays after the last touch. */
  autoHideSeconds: number
}

export const SHELL_DEFAULTS: ShellSettings = {
  scheme: 'system', fullscreen: false, statusOnTop: true, clock: true, battery: true, page: true,
  chapterProgress: false, bookProgress: false, chapterTitle: false, autoHideSeconds: 3,
}

/** Whether the hinted text differs from the original at all: literal translations, glosses or notes. */
export const hasHints = (s: Settings) => s.showLiteral || s.showLexical || s.showNotes

/**
 * The repeat actually used: with every hint off the text already is the plain original, so a repeat would only
 * duplicate it. The chosen mode is kept and comes back as soon as any hint is on again.
 */
export const effectiveRepeat = (s: Settings): Repeat => (hasHints(s) ? s.repeat : 'off')

/**
 * Colour schemes, the same values as the Android reader (Schemes.kt), modelled on ReadEra and Cool Reader: a warm
 * neutral page, one petrol accent; body text 8.5–13:1 against the page, literal, gloss and note colours 4.8–7:1.
 */
export interface Scheme { page: string; text: string; literal: string; gloss: string; note: string; bar: string; accent: string; selection: string; dark: boolean }
/** The colours live in format/schemes.json, the one source the Android reader's tests check Schemes.kt against. */
export const SCHEMES: Record<Exclude<SchemeId, 'system'>, Scheme> = schemes
export const SCHEME_TITLES: Record<SchemeId, string> = { system: 'Как в системе', paper: 'Бумага', sepia: 'Сепия', dusk: 'Сумерки', night: 'Ночь' }

/** The scheme to draw with: "as in the system" is paper by day and dusk by night. */
export const resolveScheme = (id: SchemeId, systemDark: boolean): Scheme =>
  id === 'system' ? SCHEMES[systemDark ? 'dusk' : 'paper'] : SCHEMES[id]
