import { useEffect, useMemo, useState } from 'react'

/** Whether the system is in dark mode, following changes while the page is open. */
export function useSystemDark(): boolean {
  const query = useMemo(() => (typeof window !== 'undefined' ? window.matchMedia('(prefers-color-scheme: dark)') : null), [])
  const [dark, setDark] = useState(query?.matches ?? false)
  useEffect(() => {
    if (!query) return
    const listener = (e: MediaQueryListEvent) => setDark(e.matches)
    query.addEventListener('change', listener)
    return () => query.removeEventListener('change', listener)
  }, [query])
  return dark
}
