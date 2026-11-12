import type { ActionHost } from '../core/actions'

/** The browser side of actions: clipboard, a new tab, the system share sheet (see ActionHost). */
export function browserHost(known: ReadonlySet<string>, toggleKnown: (key: string) => void, toast: (m: string) => void): ActionHost {
  return {
    isKnown: (key) => known.has(key),
    toggleKnown,
    toast,
    copy: async (text) => {
      try {
        await navigator.clipboard.writeText(text)
        return true
      } catch {
        return false
      }
    },
    openUrl: (url) => { window.open(url, '_blank', 'noopener') },
    share: async (title, text) => {
      if (!navigator.share) return false
      try { await navigator.share({ title, text }) } catch { /* the owner closed the share sheet */ }
      return true
    },
  }
}
