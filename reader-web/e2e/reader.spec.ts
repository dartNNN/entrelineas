import { expect, test, type Page } from '@playwright/test'
import { longBook, sentenceIds } from './book'

/**
 * End-to-end checks of what jsdom cannot see: pagination in a real browser (nothing below the page, nothing lost or
 * repeated where a block is cut), page turns, the word card, the selection and screen repeat. Phone size.
 */
test.use({ viewport: { width: 412, height: 892 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true })

async function open(page: Page, settings?: object) {
  await page.goto('/')
  await page.evaluate((s) => { localStorage.clear(); if (s) localStorage.setItem('entrelineas:settings', JSON.stringify(s)) }, settings)
  await page.reload()
  await page.locator('input[type=file]').setInputFiles({ name: 'e2e.bbook.zip', mimeType: 'application/zip', buffer: await longBook() })
  await expect(page.getByTestId('page').locator('.w').first()).toBeVisible()
  await expect(page.getByTestId('page-counter')).toHaveText(/^1 \/ \d+$/, { timeout: 30_000 }) // the whole chapter laid out
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
  await expect(page.getByTestId('page-counter')).toHaveText(/^2 \//)
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
