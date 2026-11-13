import { useEffect, useState } from 'react'
import { progressItems, type Progress } from '../progress'
import type { ShellSettings } from '../../core/settings'

interface BatteryManager extends EventTarget { level: number; charging: boolean }

/** Battery level and charging from the Battery Status API where the browser has it (Chromium), null elsewhere. */
function useBattery(enabled: boolean): { level: number; charging: boolean } | null {
  const [state, setState] = useState<{ level: number; charging: boolean } | null>(null)
  useEffect(() => {
    const nav = navigator as Navigator & { getBattery?: () => Promise<BatteryManager> }
    if (!enabled || !nav.getBattery) return
    let battery: BatteryManager | null = null
    const read = () => battery && setState({ level: Math.round(battery.level * 100), charging: battery.charging })
    nav.getBattery().then((b) => {
      battery = b
      read()
      b.addEventListener('levelchange', read)
      b.addEventListener('chargingchange', read)
    }).catch(() => undefined)
    return () => {
      battery?.removeEventListener('levelchange', read)
      battery?.removeEventListener('chargingchange', read)
    }
  }, [enabled])
  return enabled ? state : null
}

function useMinute(): Date {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    let timer = 0
    const tick = () => { setNow(new Date()); timer = window.setTimeout(tick, 60_000 - (Date.now() % 60_000)) }
    timer = window.setTimeout(tick, 60_000 - (Date.now() % 60_000))
    return () => clearTimeout(timer)
  }, [])
  return now
}

/** The full-screen status line: only the items switched on, in one thin row. */
export function StatusLine({ s, progress, onTap }: { s: ShellSettings; progress: Progress; onTap: () => void }) {
  const now = useMinute()
  const battery = useBattery(s.battery)
  const items: string[] = []
  items.push(...progressItems(progress, s))
  if (battery) items.push(`${battery.charging ? '⚡' : ''}${battery.level}%`)
  if (s.clock) items.push(now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }))
  if (!items.length && !s.chapterTitle) return null
  return (
    <div className="status-line" data-testid="status-line" onClick={onTap}>
      {s.chapterTitle && <span className="title">{progress.chapterTitle}</span>}
      <span>{items.join('   ')}</span>
    </div>
  )
}
