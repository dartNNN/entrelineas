import type { ShellSettings } from '../core/settings'

/**
 * Progress shown by the status line and the bottom counter. bookPage, bookPages: the page in the book and their
 * number (BookPages), estimated: some chapters are not laid out yet, so the number is approximate.
 */
export interface Progress {
  page: number; pages: number; chapterIndex: number; chapters: number; chapterTitle: string
  bookPage?: number; bookPages?: number; estimated?: boolean
}
export const chapterPercent = (p: Progress) => Math.floor((100 * (p.page + 1)) / Math.max(1, p.pages))
export const bookPercent = (p: Progress) => p.bookPage && p.bookPages
  ? Math.floor((100 * p.bookPage) / p.bookPages)
  : Math.floor((100 * (p.chapterIndex + (p.page + 1) / Math.max(1, p.pages))) / Math.max(1, p.chapters))

function item(name: string | null, count: string | null, percent: number | null): string | null {
  const text = count != null && percent != null ? `${count} (${percent}%)` : count ?? (percent != null ? `${percent}%` : null)
  return text == null ? null : name ? `${name} ${text}` : text
}

/**
 * The progress items switched on in [s], as in the Android reader: "глава 12 / 40 (30%)" and the book's "123 / ~860 (14%)"
 * (no label: it is the main one), each with its pages, its percent or both; percentages and the book's pages once the chapter is laid out.
 */
export function progressItems(p: Progress, s: ShellSettings): string[] {
  const laid = p.pages > 0
  return [
    item('глава', s.page ? `${p.page + 1} / ${p.pages || '…'}` : null, s.chapterProgress && laid ? chapterPercent(p) : null),
    item(null, s.bookPages && p.bookPage && p.bookPages ? `${p.bookPage} / ${p.estimated ? '~' : ''}${p.bookPages}` : null,
      s.bookProgress && laid ? bookPercent(p) : null),
  ].filter((x): x is string => x != null)
}
