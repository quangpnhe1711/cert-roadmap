import { useState } from 'react'
import type { ContentBlock } from '../api/types'
import { api } from '../api/client'

/**
 * Renders the typed blocks a Learning Pack is made of.
 *
 * <p>A registry rather than a switch on markup: the backend contract is a list
 * of typed blocks, so adding a block type is one entry here and nothing else
 * changes. That is also what lets a future mobile client render the same pack.
 *
 * Two things are always visible, because the product depends on them being
 * visible: where a block came from in the learner's own material, and that the
 * text was generated rather than written by the course author.
 */

const RELEVANCE_LABEL: Record<string, string> = {
  MUST_KNOW: 'Bắt buộc nhớ',
  SHOULD_KNOW: 'Nên biết',
  GOOD_TO_KNOW: 'Biết thì tốt',
  NOT_REQUIRED: 'Không cần cho kỳ thi',
}

type Props = {
  blocks: ContentBlock[]
  onOpenSource?: (pageStart: number, pageEnd: number) => void
}

export function BlockRenderer({ blocks, onOpenSource }: Props) {
  return (
    <div className="pack">
      {blocks.map((block) => (
        <Block key={block.id} block={block} onOpenSource={onOpenSource} />
      ))}
    </div>
  )
}

function Block({ block, onOpenSource }: { block: ContentBlock; onOpenSource?: Props['onOpenSource'] }) {
  const [flagged, setFlagged] = useState(false)
  const [flagging, setFlagging] = useState(false)

  const flag = async () => {
    setFlagging(true)
    try {
      // Reporting a block also invalidates its cached artifact, so a bad
      // generation does not get served to the next learner forever.
      await api.post('/api/v1/content-flags', { blockId: block.id, reason: 'USER_REPORTED_INCORRECT' })
      setFlagged(true)
    } finally {
      setFlagging(false)
    }
  }

  return (
    <section className={`block block-${block.type} origin-${block.origin.toLowerCase()}`}>
      <header className="block-head">
        <span className="block-type">{BLOCK_LABEL[block.type] ?? block.type}</span>
        {block.examRelevance && (
          <span className={`chip rel-${block.examRelevance.toLowerCase()}`}>
            {RELEVANCE_LABEL[block.examRelevance]}
          </span>
        )}
        {block.origin === 'AI_SUPPLEMENT' && (
          <span className="chip supplement" title="Nội dung nền do AI bổ sung, không có trong tài liệu gốc">
            AI bổ sung
          </span>
        )}
        {block.sourcePageStart && (
          <button
            type="button"
            className="source-link"
            onClick={() => onOpenSource?.(block.sourcePageStart!, block.sourcePageEnd ?? block.sourcePageStart!)}
          >
            Trang {block.sourcePageStart}
            {block.sourcePageEnd && block.sourcePageEnd !== block.sourcePageStart
              ? `–${block.sourcePageEnd}`
              : ''}
          </button>
        )}
        <button type="button" className="flag" onClick={flag} disabled={flagging || flagged}>
          {flagged ? 'Đã báo' : 'Báo sai'}
        </button>
      </header>
      <BlockBody block={block} />
    </section>
  )
}

const BLOCK_LABEL: Record<string, string> = {
  overview: 'Tổng quan',
  concept: 'Khái niệm',
  exam_tip: 'Mẹo thi',
  keyword: 'Thuật ngữ',
  comparison: 'So sánh',
  example: 'Ví dụ',
  warning: 'Lưu ý',
  relationship: 'Quan hệ',
  source_reference: 'Xem lại tài liệu gốc',
  check_understanding: 'Tự kiểm tra',
}

function BlockBody({ block }: { block: ContentBlock }) {
  const payload = block.payload ?? {}

  switch (block.type) {
    case 'keyword':
      return (
        <dl className="keyword">
          <dt>{String(payload.term ?? '')}</dt>
          {payload.simple != null && <dd>{String(payload.simple)}</dd>}
          {payload.technical != null && (
            <dd className="muted">{String(payload.technical)}</dd>
          )}
          {payload.example != null && <dd>Ví dụ: {String(payload.example)}</dd>}
          {payload.examNote != null && (
            <dd className="exam-note">Liên quan bài thi: {String(payload.examNote)}</dd>
          )}
        </dl>
      )

    case 'comparison': {
      const rows = Array.isArray(payload.rows) ? (payload.rows as Record<string, unknown>[]) : []
      if (rows.length === 0) return <Text payload={payload} />
      const columns = Object.keys(rows[0])
      return (
        <div className="tablewrap">
          <table>
            <thead>
              <tr>
                {columns.map((c) => (
                  <th key={c}>{c}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, i) => (
                <tr key={i}>
                  {columns.map((c) => (
                    <td key={c}>{String(row[c] ?? '')}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )
    }

    case 'relationship': {
      const chain = Array.isArray(payload.chain) ? (payload.chain as string[]) : null
      if (!chain) return <Text payload={payload} />
      return (
        <ol className="chain">
          {chain.map((step, i) => (
            <li key={i}>{step}</li>
          ))}
        </ol>
      )
    }

    default:
      return <Text payload={payload} />
  }
}

function Text({ payload }: { payload: Record<string, unknown> }) {
  const text = payload.text ?? payload.body ?? payload.content
  if (typeof text === 'string') {
    return (
      <>
        {text.split('\n').filter(Boolean).map((line, i) => (
          <p key={i}>{line}</p>
        ))}
      </>
    )
  }
  const items = payload.items
  if (Array.isArray(items)) {
    return (
      <ul>
        {items.map((item, i) => (
          <li key={i}>{String(item)}</li>
        ))}
      </ul>
    )
  }
  return <pre className="raw">{JSON.stringify(payload, null, 2)}</pre>
}
