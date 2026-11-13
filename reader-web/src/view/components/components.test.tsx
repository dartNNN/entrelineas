// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeAll, describe, expect, it } from 'vitest'
import { mergeActions, type ActionHost } from '../../core/actions'
import { buildBlocks } from '../../core/blocks'
import { loadBook, type Book, type Chapter } from '../../core/book'
import { select, SelectionResolver, type TextSelection } from '../../core/selection'
import { DEFAULTS, SHELL_DEFAULTS } from '../../core/settings'
import { testBook } from '../../test/fixture'
import { drawFragment } from '../fragment'
import { BlockView } from './BlockView'
import { Reader } from './Reader'
import { SelectionPanel } from './SelectionPanel'
import { FullSettings } from './Settings'
import { StatusLine } from './StatusLine'
import { WordCard } from './WordCard'

let book: Book
let first: Chapter
beforeAll(async () => {
  book = await loadBook(await testBook())
  first = await book.chapter(0)
  // jsdom has no layout: the reader measures nothing there, but must still render and answer taps.
  globalThis.ResizeObserver = class { observe() {} disconnect() {} } as unknown as typeof ResizeObserver
})
afterEach(cleanup)

const host = (log: string[] = []): ActionHost => ({
  isKnown: () => false, toggleKnown: (k) => log.push(`known ${k}`), toast: (m) => log.push(m),
  copy: async (t) => { log.push(`copy ${t}`); return true }, openUrl: (u) => log.push(`open ${u}`), share: async () => false,
})

function tapOn(el: Element) {
  fireEvent.pointerDown(el, { button: 0, clientX: 5, clientY: 5 })
  fireEvent.pointerUp(el, { button: 0, clientX: 5, clientY: 5 })
}

describe('reader screens', () => {
  it('shows the hinted text and opens a tapped word; a tap off any word goes to the shell', () => {
    const taps: unknown[] = []
    const chapter = first
    const sentences = new Map(chapter.paragraphs.flatMap((p) => p.sentences.map((s) => [s.id, s] as const)))
    const { container } = render(<Reader blocks={buildBlocks(chapter, DEFAULTS, new Set())} sentences={sentences} settings={DEFAULTS} highlight={null}
      startParagraph="" keys onWord={(t) => taps.push(t)} onLongPress={() => {}} onBlank={() => taps.push('blank')} onReveal={() => {}}
      onScreen={() => {}} onTurn={() => {}} onLayout={() => {}} />)
    const gloss = container.querySelector('.gloss')!
    expect(gloss.textContent).toBe(' [мир]')
    tapOn(screen.getAllByText('mundo')[0])
    tapOn(gloss)
    tapOn(container.querySelector('.notes')!)
    tapOn(container.querySelector('[data-k="c01p001s1:2"]')!)  // the full stop: punctuation opens no card
    expect(taps).toEqual([{ sentence: 'c01p001s1', token: 1 }, { sentence: 'c01p001s1', token: 1 }, 'blank', 'blank'])
  })

  it('shows the word card with its entry, segment, the published translation and the familiar toggle', () => {
    const toggled: string[] = []
    render(<WordCard tap={{ sentence: 'c01p001s1', token: 1 }} chapter={first} settings={DEFAULTS} known={new Set()}
      onToggleKnown={(k) => toggled.push(k)} onClose={() => {}} />)
    expect(screen.getByText('Привет, мир.')).toBeTruthy()
    expect(screen.getByText('Словарь: mundo')).toBeTruthy()
    expect(screen.getByText('1. мир, свет').className).toContain('chosen')
    expect(screen.queryByText(/без модели/)).toBeNull()
    expect(screen.getByText('Сноска автора: Saludo.')).toBeTruthy()
    fireEvent.click(screen.getByText('Знаю это значение'))
    expect(toggled).toEqual(['mundo|NOUN#1'])
    // Chosen from the dictionary: the entry comes right under the gloss, before the segment.
    const order = (el: HTMLElement) => [...el.querySelectorAll('section')].map((x) => x.dataset.testid ?? x.textContent?.slice(0, 6))
    expect(order(screen.getByTestId('word-card'))[0]).toBe('dictionary-entry')
  })

  it('puts the dictionary entry after everything else in a book built with a model, marking the sense the model chose', () => {
    const chapter = first
    const model = { ...chapter, paragraphs: chapter.paragraphs.map((p) => ({ ...p, sentences: p.sentences.map((s) => ({
      ...s, lexical: new Map([...s.lexical].map(([t, l]) => [t, { ...l, from: 'model' as const, sense: '2' }])),
    })) })) }
    render(<WordCard tap={{ sentence: 'c01p001s1', token: 1 }} chapter={model} settings={DEFAULTS} known={new Set()} onToggleKnown={() => {}} onClose={() => {}} />)
    const sections = [...screen.getByTestId('word-card').querySelectorAll('section')]
    expect(sections.at(-1)?.dataset.testid).toBe('dictionary-entry')
    expect(screen.getByText('2. общество').className).toContain('chosen')
    expect(screen.getByText('1. мир, свет').className).not.toContain('chosen')
  })

  it('changes unit and layer in the selection panel and runs actions shown at once or under «Ещё»', () => {
    const resolver = new SelectionResolver(book, first)
    let sel: TextSelection = select('c01p001s1', 1)
    const log: string[] = []
    const panel = () => <SelectionPanel sel={sel} content={resolver.content(sel)} units={resolver.units(sel)} configs={mergeActions([])} host={host(log)}
      onChange={(s) => { sel = s }} onClose={() => {}} />
    const { rerender } = render(panel())
    expect(screen.getByText('Сегмент')).toBeTruthy()
    fireEvent.click(screen.getByText('Знаю это значение'))
    expect(log).toEqual(['known mundo|NOUN#1'])
    fireEvent.click(screen.getByText('Подсказка'))
    rerender(panel())
    expect(screen.getByTestId('selection-text').textContent).toBe('mundo — мир')
    fireEvent.click(screen.getByText('Предложение'))
    rerender(panel())
    expect(screen.queryByText('Знаю это значение')).toBeNull()
    fireEvent.click(screen.getByTestId('extend'))
    expect(sel).toMatchObject({ unit: 'sentence', more: 1 })
    fireEvent.click(screen.getByText(/Ещё/))
    expect(screen.getByRole('menuitem', { name: 'Google Переводчик' })).toBeTruthy()
    expect(screen.getByRole('menuitem', { name: 'Поделиться' })).toBeTruthy()
  })

  it('has settings tabs for text, look and actions, with samples and the repeat off while every hint is off', () => {
    let settings = { ...DEFAULTS, showLiteral: false, showLexical: false }
    let actions = mergeActions([])
    const view = () => <FullSettings settings={settings} shell={SHELL_DEFAULTS} systemDark={false} book={book} chapter={first} layoutMs={3}
      onSettings={(s) => { settings = s }} onShell={() => {}} actions={actions} host={host()} onActions={(a) => { actions = a }} onBack={() => {}} />
    const { rerender } = render(view())
    fireEvent.click(screen.getByRole('switch', { name: 'Грамматика и контекст' }))
    rerender(view())
    expect(screen.getByTestId('repeat-off')).toBeTruthy()
    fireEvent.click(screen.getByRole('tab', { name: 'Вид' }))
    expect(screen.getByRole('switch', { name: 'Полноэкранный режим' })).toBeTruthy()
    fireEvent.click(screen.getByRole('tab', { name: 'Действия' }))
    fireEvent.click(screen.getAllByLabelText('Ниже')[0])
    expect(actions.map((a) => a.id).slice(0, 2)).toEqual(['copy', 'known'])
  })

  it('shows only the status items switched on', () => {
    const s = { ...SHELL_DEFAULTS, clock: false, battery: false, page: true, chapterProgress: true, bookPages: false, bookProgress: false }
    render(<StatusLine s={{ ...s, chapterTitle: true }}
      progress={{ page: 1, pages: 4, chapterIndex: 0, chapters: 2, chapterTitle: 'Uno' }} onTap={() => {}} />)
    expect(screen.getByTestId('status-line').textContent).toBe('Unoглава 2 / 4 (50%)')
    cleanup()
    render(<StatusLine s={s} progress={{ page: 0, pages: 0, chapterIndex: 0, chapters: 2, chapterTitle: 'Uno' }} onTap={() => {}} />)
    expect(screen.getByTestId('status-line').textContent).toBe('глава 1 / …')
  })
})

describe('one markup for the screen and the measurer', () => {
  it('draws a fragment the same way in React and in the measurer', () => {
    const blocks = buildBlocks(first, DEFAULTS, new Set())
    for (const [i, block] of blocks.entries()) {
      const fragment = { block: i, start: 2, end: 30, cont: true, hyphen: true }
      const { container } = render(<BlockView block={block} fragment={fragment} highlight={null} />)
      const strip = (el: Element) => { const c = el.cloneNode(true) as Element; [c, ...c.querySelectorAll('*')].forEach((e) => [...e.attributes].filter((a) => a.name.startsWith('data-')).forEach((a) => e.removeAttribute(a.name))); return c.outerHTML }
      expect(strip(container.firstElementChild!)).toBe(strip(drawFragment(block, fragment)))
      cleanup()
    }
  })
})
