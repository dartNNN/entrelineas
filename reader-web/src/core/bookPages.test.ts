import { describe, expect, it } from 'vitest'
import { BookPages } from './bookPages'

describe('BookPages', () => {
  it('estimates unread chapters at the pages per length of the read ones', () => {
    const pages = new BookPages([100, 200, 50], new Map([[0, 10]]))
    expect(pages.available).toBe(true)
    expect(pages.estimated).toBe(true)
    expect([0, 1, 2].map((i) => pages.pages(i))).toEqual([10, 20, 5])
    expect(pages.total).toBe(35)
    expect(pages.pageInBook(1, 2)).toBe(13)
  })

  it('is exact once every chapter is laid out, and has nothing to count from before any', () => {
    expect(new BookPages([100, 200], new Map([[0, 9], [1, 31]])).estimated).toBe(false)
    expect(new BookPages([100, 200], new Map([[0, 9], [1, 31]])).total).toBe(40)
    expect(new BookPages([100, 200], new Map()).available).toBe(false)
  })
})
