import { useState } from 'react'
import type { ActionConfig, ActionHost } from '../../core/actions'
import type { Book, Chapter } from '../../core/book'
import type { Settings, ShellSettings } from '../../core/settings'
import { Icon } from './Icon'
import { ActionsSettings, FontSize, HintSettings, Margin, SchemePicker, Section, Toggle, TranslationSettings, TypographySettings, ViewSettings } from './SettingsParts'
import { Sheet } from './Sheet'

interface Common {
  settings: Settings
  shell: ShellSettings
  systemDark: boolean
  book: Book | null
  chapter: Chapter | null
  layoutMs: number
  onSettings: (s: Settings) => void
  onShell: (s: ShellSettings) => void
}

/**
 * Quick panel over the page: what the page shows (scheme, font, hints, translation), seen on the page at once.
 * The full screen holds the same and everything else, by tabs.
 */
export function QuickSettings({ settings, shell, systemDark, book, chapter, layoutMs, onSettings, onShell, onAll, onClose }: Common & { onAll: () => void; onClose: () => void }) {
  return (
    <Sheet onClose={onClose} label="Что показывать">
      <div className="row">
        <h3 className="grow">Что показывать</h3>
        <button onClick={onAll} data-testid="all-settings">Все настройки</button>
      </div>
      <SchemePicker current={shell.scheme} systemDark={systemDark} onPick={(scheme) => onShell({ ...shell, scheme })} />
      <FontSize s={settings} onChange={onSettings} />
      <Margin s={settings} onChange={onSettings} />
      <HintSettings s={settings} book={book} onChange={onSettings} />
      <TranslationSettings s={settings} chapter={chapter} onChange={onSettings} />
      <Section title="Экран" />
      <Toggle label="Полноэкранный режим" value={shell.fullscreen} note="Без панелей браузера; часы, батарея и страница — в своей строке"
        onChange={(fullscreen) => onShell({ ...shell, fullscreen })} />
      <p className="note">Разбивка главы на страницы: {layoutMs} мс</p>
    </Sheet>
  )
}

const TABS = [['text', 'Текст'], ['view', 'Вид'], ['actions', 'Действия']] as const

/** All settings, by tabs: the text and its hints, the look of the app, the selection actions. */
export function FullSettings({ settings, shell, systemDark, book, chapter, layoutMs, onSettings, onShell, actions, host, onActions, onBack }: Common & {
  actions: ActionConfig[]; host: ActionHost; onActions: (a: ActionConfig[]) => void; onBack: () => void
}) {
  const [tab, setTab] = useState<(typeof TABS)[number][0]>('text')
  return (
    <div className="full-settings" role="dialog" aria-label="Настройки">
      <header className="bar">
        <button className="icon" onClick={onBack} aria-label="Назад"><Icon name="back" /></button>
        <h1>Настройки</h1>
      </header>
      <nav className="tabs" role="tablist">
        {TABS.map(([id, label]) => <button key={id} role="tab" aria-selected={tab === id} onClick={() => setTab(id)}>{label}</button>)}
      </nav>
      <div className="full-body">
        {tab === 'text' && (
          <>
            <HintSettings s={settings} book={book} onChange={onSettings} />
            <TranslationSettings s={settings} chapter={chapter} onChange={onSettings} />
            <TypographySettings s={settings} onChange={onSettings} />
            <p className="note divided">Разбивка главы на страницы: {layoutMs} мс</p>
          </>
        )}
        {tab === 'view' && <ViewSettings shell={shell} systemDark={systemDark} onShell={onShell} />}
        {tab === 'actions' && <ActionsSettings configs={actions} host={host} onChange={onActions} />}
      </div>
    </div>
  )
}
