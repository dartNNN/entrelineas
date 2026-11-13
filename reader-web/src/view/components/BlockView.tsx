import { memo, useMemo } from 'react'
import type { Block } from '../../core/blocks'
import { className, firstSentence, key, langOf, languages, painted, pieces, TAGS } from '../fragment'
import type { Fragment } from '../../core/paginate'
import type { Highlight } from '../../core/selection'

/** One fragment of a block on the screen being read, with the selection painted (markup shared with drawFragment). */
export const BlockView = memo(function BlockView({ block, fragment, highlight }: { block: Block; fragment: Fragment; highlight: Highlight | null }) {
  const list = useMemo(() => pieces(block, fragment, painted(block, highlight)), [block, fragment, highlight])
  const Tag = TAGS[block.kind] as 'p'
  return (
    <Tag className={className(block, fragment)} data-block={block.kind} data-paragraph={block.paragraph} data-first={firstSentence(block)} lang={langOf(block.kind)}>
      {list.map(({ run: r, painted: on }, i) => {
        const sel = on ? ' sel' : ''
        switch (r.kind) {
          case 'token': return <span key={i} className={`w${sel}`} data-k={key(r)}>{r.text}</span>
          case 'gloss': return <span key={i} className={`gloss${sel}`} lang={languages.support} data-k={key(r)}>{r.text}</span>
          case 'literal': return <span key={i} className={`literal${sel}`} lang={languages.support}>{r.text}</span>
          default: return on ? <span key={i} className="sel">{r.text}</span> : r.text
        }
      })}
      {fragment.hyphen && '-'}
    </Tag>
  )
})
