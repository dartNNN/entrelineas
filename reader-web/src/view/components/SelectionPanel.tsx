import { useState } from 'react'
import { ACTIONS, runAction, type ActionConfig, type ActionHost } from '../../core/actions'
import { LAYER_TITLES, layersOf, layerText, UNIT_TITLES, type SelectionContent, type SelectionUnit, type TextSelection } from '../../core/selection'
import { Icon } from './Icon'

/**
 * The panel of an active selection, over the bottom of the page: what is selected and a close button, what to select
 * (unit, extension), what to take (layer), a preview, and the actions in a row of their own — the ones set "shown at
 * once" as compact buttons, the rest under "Ещё".
 */
export function SelectionPanel({ sel, content, units, configs, host, onChange, onClose }: {
  sel: TextSelection; content: SelectionContent; units: SelectionUnit[]; configs: ActionConfig[]; host: ActionHost
  onChange: (s: TextSelection) => void; onClose: () => void
}) {
  const [more, setMore] = useState(false)
  const usable = configs.flatMap((cfg) => {
    const action = ACTIONS.find((a) => a.id === cfg.id)
    return action && cfg.enabled && (action.accepts?.(content) ?? true) ? [{ action, cfg }] : []
  })
  const pinned = usable.filter((u) => u.cfg.pinned)
  const rest = usable.filter((u) => !u.cfg.pinned)
  const run = (u: (typeof usable)[number]) => { setMore(false); void runAction(u.action, u.cfg, host, content, sel.layer) }
  return (
    <section className="selection-panel" data-testid="selection-panel">
      <div className="row">
        <span className="grow note">Выделено: {UNIT_TITLES[sel.unit].toLowerCase()} · {LAYER_TITLES[sel.layer].toLowerCase()}</span>
        <button className="icon" onClick={onClose} aria-label="Снять выделение"><Icon name="close" /></button>
      </div>
      <div className="chips scroll">
        {units.map((u) => <button key={u} className="chip" aria-pressed={sel.unit === u} onClick={() => onChange({ ...sel, unit: u, more: 0 })}>{UNIT_TITLES[u]}</button>)}
        {(sel.unit === 'sentence' || sel.unit === 'paragraph') && (
          <button className="chip" onClick={() => onChange({ ...sel, more: sel.more + 1 })} data-testid="extend"><Icon name="add" /> ещё</button>
        )}
      </div>
      <div className="chips scroll">
        {layersOf(content).map((l) => <button key={l} className="chip" aria-pressed={sel.layer === l} onClick={() => onChange({ ...sel, layer: l })}>{LAYER_TITLES[l]}</button>)}
      </div>
      <p className="preview" data-testid="selection-text" lang={sel.layer === 'original' ? content.studied : content.support}>{layerText(content, sel.layer)}</p>
      <div className="chips scroll actions">
        {pinned.map((u) => <button key={u.cfg.id} className="chip" onClick={() => run(u)}>{u.action.title(host, content)}</button>)}
        {rest.length > 0 && (
          <span className="more">
            <button className="chip" onClick={() => setMore(!more)} aria-expanded={more}>Ещё <Icon name="more" /></button>
            {more && (
              <span className="menu" role="menu">
                {rest.map((u) => <button key={u.cfg.id} role="menuitem" onClick={() => run(u)}>{u.action.title(host, content)}</button>)}
              </span>
            )}
          </span>
        )}
      </div>
    </section>
  )
}
