import { useEffect, useRef, useState } from 'react'

/**
 * A value kept in localStorage under "entrelineas:<key>". Stored settings are merged over the defaults, so a newer
 * version with more settings keeps what the owner chose before. The value is written after it changes, outside the
 * state update (updates must stay pure). Private windows without storage just do not remember.
 */
export function useStored<T>(key: string, initial: T): [T, (v: T | ((old: T) => T)) => void] {
  const name = `entrelineas:${key}`
  const [value, setValue] = useState<T>(() => {
    try {
      const raw = localStorage.getItem(name)
      if (raw == null) return initial
      const parsed = JSON.parse(raw) as T
      return initial && typeof initial === 'object' && !Array.isArray(initial) ? { ...initial, ...parsed } : parsed
    } catch {
      return initial
    }
  })
  const first = useRef(true)
  useEffect(() => {
    if (first.current) { first.current = false; return } // what was read needs no writing back
    try {
      localStorage.setItem(name, JSON.stringify(value))
    } catch { /* storage unavailable: keep in memory only */ }
  }, [name, value])
  return [value, setValue]
}
