import { describe, expect, it } from 'vitest'
import { senseHeadings } from './entry'

describe('senseHeadings', () => {
  it('heads each part of the entry once and leaves an entry with one label without headings', () => {
    expect(senseHeadings(['сущ.', 'сущ.', 'гл.', 'гл.', null])).toEqual(['сущ.', null, 'гл.', null, null])
    expect(senseHeadings(['сущ.', 'сущ.', null])).toEqual([null, null, null])
  })
})
