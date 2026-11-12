import { useEffect } from 'react'

/** A short message over the page that goes away by itself. */
export function Toast({ message, onDone }: { message: string; onDone: () => void }) {
  useEffect(() => {
    const t = window.setTimeout(onDone, 2500)
    return () => clearTimeout(t)
  }, [message, onDone])
  return <div className="toast" role="status">{message}</div>
}
