import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Meta } from '../api/types'

/**
 * Where the lessons on screen actually came from.
 *
 * Always answered by the server. A learner - or anyone being shown a demo - has
 * no other way to tell fixture prose from a real lesson, and a coverage
 * percentage computed from fixture output looks exactly like a measured one.
 */
export function useMeta() {
  return useQuery({
    queryKey: ['meta'],
    queryFn: () => api.get<Meta>('/api/v1/meta'),
    staleTime: Infinity,
  })
}

type Tone = 'live' | 'synthetic' | 'unconfigured'

function toneOf(meta: Meta | undefined): Tone {
  if (!meta) return 'synthetic'
  if (meta.aiSynthetic) return 'synthetic'
  return meta.aiConfigured ? 'live' : 'unconfigured'
}

const STATUS_TEXT: Record<Tone, string> = {
  live: 'Đã kết nối',
  synthetic: 'Chế độ phát triển',
  unconfigured: 'Chưa cấu hình',
}

/**
 * The compact form, for the header.
 *
 * Names the provider rather than saying "AI": "Gemini · Đã kết nối" is checkable,
 * "AI đang bật" is not.
 */
export function AiStatusChip({ meta }: { meta?: Meta }) {
  const tone = toneOf(meta)
  const label = meta?.aiSynthetic ? 'Synthetic AI' : meta?.aiProviderLabel ?? '…'

  return (
    <span className={`ai-chip ${tone}`} title={modelHint(meta)}>
      <span className="dot" aria-hidden="true" />
      <span className="ai-chip-name">{label}</span>
      <span className="ai-chip-state">{STATUS_TEXT[tone]}</span>
    </span>
  )
}

/**
 * The full-width banner, shown only while output is synthetic.
 *
 * Not dismissible, and it disappears on its own the moment a real provider is
 * configured. A banner someone can close is a banner that is closed during the
 * one demo where it mattered.
 */
export function SyntheticBanner({ meta }: { meta?: Meta }) {
  if (!meta || !meta.aiSynthetic) return null
  return (
    <div className="dev-banner" role="status">
      <strong>Synthetic AI · chế độ phát triển</strong>
      <span>
        Bài giảng, câu hỏi và độ phủ trên màn hình do adapter mô phỏng sinh ra. Nội dung không
        phản ánh chất lượng thật và không dùng để đánh giá.
      </span>
    </div>
  )
}

/** The models in use, for the settings card. Never a key, never a raw config dump. */
export function modelHint(meta?: Meta): string {
  if (!meta || meta.aiSynthetic) return 'Không gọi mô hình nào; kết quả sinh cục bộ.'
  return `Nhanh: ${meta.fastModel} · Chất lượng: ${meta.qualityModel}`
}

export { toneOf, STATUS_TEXT }
