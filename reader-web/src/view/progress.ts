/** Progress shown by the status line and the bottom counter. */
export interface Progress { page: number; pages: number; chapterIndex: number; chapters: number; chapterTitle: string }
export const chapterPercent = (p: Progress) => Math.floor((100 * (p.page + 1)) / Math.max(1, p.pages))
export const bookPercent = (p: Progress) => Math.floor((100 * (p.chapterIndex + (p.page + 1) / Math.max(1, p.pages))) / Math.max(1, p.chapters))
