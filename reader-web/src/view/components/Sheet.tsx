import { useEffect, useRef, type ReactNode } from 'react'

/** A panel over the bottom of the page (a side panel for the drawer); Escape or the backdrop closes it. */
export function Sheet({ label, side = 'bottom', onClose, children }: { label: string; side?: 'bottom' | 'left'; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', key)
    return () => window.removeEventListener('keydown', key)
  }, [onClose])
  // Only a touch that starts on the backdrop closes the panel: the click that ends the tap opening it (on a word, on
  // touch screens) lands on the backdrop that has just appeared under the finger.
  const pressed = useRef(false)
  return (
    <div className="backdrop" onPointerDown={(e) => { pressed.current = e.target === e.currentTarget }}
      onClick={(e) => { if (pressed.current && e.target === e.currentTarget) onClose(); pressed.current = false }}>
      <aside className={`sheet ${side}`} role="dialog" aria-label={label} onClick={(e) => e.stopPropagation()}>
        {side === 'bottom' && <div className="handle" />}
        <div className="sheet-body">{children}</div>
      </aside>
    </div>
  )
}
