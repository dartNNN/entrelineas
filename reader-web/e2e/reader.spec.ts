import { expect, test, type Page } from '@playwright/test'
import { longBook, sentenceIds } from './book'

/**
 * End-to-end checks of what jsdom cannot see: pagination in a real browser (nothing below the page, nothing lost or
 * repeated where a block is cut), page turns, the word card, the selection and screen repeat. Phone size.
 */
test.use({ viewport: { width: 412, height: 892 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true })

async function open(page: Page, settings?: object, book?: Buffer) {
  await page.goto('/')
  await page.evaluate((s) => { localStorage.clear(); if (s) localStorage.setItem('entrelineas:settings', JSON.stringify(s)) }, settings)
  await page.reload()
  await page.locator('input[type=file]').setInputFiles({ name: 'e2e.bbook.zip', mimeType: 'application/zip', buffer: book ?? await longBook() })
  await expect(page.getByTestId('page').locator('.w').first()).toBeVisible()
  await expect(page.getByTestId('page-counter')).toHaveAttribute('data-chapter', /^1 \/ \d+$/, { timeout: 30_000 }) // the whole chapter laid out
}

/** What a page shows: overflow below the page, hinted words in order, whether its last block is cut. */
const read = (page: Page) => page.evaluate(() => {
  const area = document.querySelector('.pages')!.getBoundingClientRect()
  const shown = document.querySelector('[data-testid=page]')!
  let over = 0
  shown.querySelectorAll('span, .block').forEach((e) => { for (const r of e.getClientRects()) over = Math.max(over, r.bottom - area.bottom) })
  const words = [...shown.querySelectorAll('.block.hinted .w')].map((w) => (w as HTMLElement).dataset.k!)
  return { over, words }
})

test('pages keep every word once, in order, and nothing goes below the page', async ({ page }) => {
  await open(page)
  const order = new Map<string, number>(sentenceIds().map((s, i) => [s, i]))
  const rank = (k: string) => { const [s, t] = k.split(':'); return order.get(s)! * 1000 + Number(t) }
  const seen: string[] = []
  for (let p = 0; p < 40; p++) {
    const { over, words } = await read(page)
    expect(over, `page ${p + 1}`).toBeLessThanOrEqual(0.5)
    for (const w of words) if (seen.at(-1) !== w) seen.push(w) // a word hyphenated across two pages shows on both
    await page.keyboard.press('ArrowRight')
  }
  const ranks = seen.map(rank)
  expect(ranks.every((r, i) => i === 0 || r > ranks[i - 1])).toBe(true)
  // No word left out between the first and the last one seen: consecutive tokens of a sentence, sentences in order.
  for (let i = 1; i < seen.length; i++) {
    const [s0, t0] = seen[i - 1].split(':'), [s1, t1] = seen[i].split(':')
    expect(s1 === s0 ? Number(t1) === Number(t0) + 1 : Number(t1) === 0 && order.get(s1) === order.get(s0)! + 1, `${seen[i - 1]} → ${seen[i]}`).toBe(true)
  }
})

test('turns pages, opens the word card and selects by units', async ({ page }) => {
  await open(page)
  await page.keyboard.press('ArrowRight')
  await expect(page.getByTestId('page-counter')).toHaveAttribute('data-chapter', /^2 \//)
  const word = page.getByTestId('page').locator('.block.hinted .w').nth(3)
  await word.tap()
  await expect(page.getByTestId('word-card')).toBeVisible()
  await page.keyboard.press('Escape')
  const box = (await word.boundingBox())!
  await page.mouse.move(box.x + 2, box.y + 2)
  await page.mouse.down()
  await page.waitForTimeout(700)
  await page.mouse.up()
  await expect(page.getByTestId('selection-panel')).toBeVisible()
  await page.getByRole('button', { name: 'Предложение' }).click()
  await expect(page.getByTestId('page').locator('.sel').first()).toBeVisible()
})

test('screen repeat shows the words of the page before it, and fits', async ({ page }) => {
  await open(page, { repeat: 'screen' })
  const before = (await read(page)).words
  await page.keyboard.press('ArrowRight')
  const repeat = page.getByTestId('repeat-screen')
  await expect(repeat).toBeVisible()
  const words = await repeat.locator('.w').evaluateAll((ws) => ws.map((w) => (w as HTMLElement).dataset.k!))
  expect(new Set(words)).toEqual(new Set(before.filter((w, i) => i < before.length - 1 || words.includes(w))))
  expect(await repeat.evaluate((r) => r.scrollHeight <= r.clientHeight + 1)).toBe(true)
})

const counter = (page: Page) => page.getByTestId('page-counter')

/** Opens the drawer; the top bar hides itself while reading, and a tap on the counter calls it up. */
async function menu(page: Page) {
  if (await page.locator('header.topbar.hidden').count()) await counter(page).click()
  await expect(page.locator('header.topbar.hidden')).toHaveCount(0)
  await page.getByLabel('Меню').click()
}

const chapterPage = (page: Page) => counter(page).getAttribute('data-chapter')

test('turns past the last page into the next chapter and back onto the last page, with the book page count', async ({ page }) => {
  await open(page, undefined, await longBook(true))
  // By default the counter shows the book: its page and pages ("~": the second chapter is estimated) and percent.
  await expect(counter(page)).toHaveText(/^1 \/ ~\d+ \(\d+%\)$/)
  const total = Number((await chapterPage(page))!.split(' / ')[1])
  for (let i = 1; i < total; i++) await page.keyboard.press('ArrowRight')
  await expect(counter(page)).toHaveAttribute('data-chapter', `${total} / ${total}`)
  await page.keyboard.press('ArrowRight')
  await expect(page.locator('header h1')).toHaveText('Capítulo corto')
  await expect(counter(page)).toHaveText(/^\d+ \/ \d+ \(\d+%\)$/)  // both chapters counted: no "~"
  await page.keyboard.press('ArrowLeft')
  await expect(page.locator('header h1')).toHaveText('Capítulo largo')
  await expect(counter(page)).toHaveAttribute('data-chapter', `${total} / ${total}`, { timeout: 30_000 })
})

test('shows the progress items chosen in the settings', async ({ page }) => {
  await page.goto('/')
  await page.evaluate(() => { localStorage.clear(); localStorage.setItem('entrelineas:shell', JSON.stringify({ page: true, chapterProgress: true, bookPages: false, bookProgress: true, progressVersion: 2 })) })
  await page.reload()
  await page.locator('input[type=file]').setInputFiles({ name: 'e2e.bbook.zip', mimeType: 'application/zip', buffer: await longBook() })
  await expect(counter(page)).toHaveText(/^глава 1 \/ \d+ \(\d+%\) +· +\d+%$/, { timeout: 30_000 })
})

test('keeps every opened book with its own position', async ({ page }) => {
  await open(page)
  for (let i = 0; i < 3; i++) await page.keyboard.press('ArrowRight')
  await expect(counter(page)).toHaveAttribute('data-chapter', /^4 \//)
  await menu(page)  // the file input of "Открыть книгу…" lives in the drawer
  await page.locator('input[type=file]').setInputFiles({ name: 'other.bbook.zip', mimeType: 'application/zip', buffer: await longBook(false, 'other') })
  await expect(counter(page)).toHaveAttribute('data-chapter', /^1 \/ \d+$/, { timeout: 30_000 })
  await menu(page)
  await page.getByTestId('book:e2e').click()
  await expect(counter(page)).toHaveAttribute('data-chapter', /^4 \//, { timeout: 30_000 })
})
