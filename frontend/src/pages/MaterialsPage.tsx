import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { MaterialView, TodayView } from '../api/types'
import { Banner, EmptyState, ErrorState, InlineError, Loading } from '../components/states'

/**
 * The learner's own files, and what the system made of them.
 *
 * Deliberately not a document manager. Uploading happens in onboarding, where it
 * is part of building a plan; this screen exists to answer "did it read my deck
 * properly" and "can I see the original", and stops there. There is no delete,
 * because completed study days still point at the revision behind the file and
 * removing it would silently rewrite what someone already studied.
 */
export function MaterialsPage() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const today = useQuery({ queryKey: ['today'], queryFn: () => api.get<TodayView>('/api/v1/today') })

  const materials = useQuery({
    queryKey: ['materials'],
    queryFn: () => api.get<MaterialView[]>('/api/v1/materials'),
  })

  const reprocess = useMutation({
    mutationFn: (materialId: string) =>
      api.post(`/api/v1/materials/${materialId}/reprocess?planId=${today.data?.planId}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['materials'] }),
  })

  const openViewer = async (materialId: string) => {
    const { url } = await api.get<{ url: string }>(`/api/v1/materials/${materialId}/viewer-url`)
    window.open(url, '_blank', 'noopener')
  }

  if (materials.isPending) return <Loading />
  if (materials.error) return <ErrorState error={materials.error} onRetry={() => materials.refetch()} />
  if (!materials.data?.length) {
    return (
      <div className="page">
        <EmptyState
          icon="📄"
          title="Bạn chưa tải tài liệu nào lên"
          hint="Tài liệu được tải lên khi bạn tạo kế hoạch — hệ thống đọc chính tài liệu đó để chia lịch."
          action={
            <button type="button" onClick={() => navigate('/onboarding')}>
              Tạo kế hoạch và tải tài liệu
            </button>
          }
        />
      </div>
    )
  }

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>Tài liệu của bạn</h1>
          <p className="muted">
            Riêng tư, không chia sẻ với ai, chỉ dùng để tạo lịch và bài giảng cho bạn.
          </p>
        </div>
      </header>

      {reprocess.error && <InlineError error={reprocess.error} />}

      <ul className="material-list">
        {materials.data.map((material) => {
          const latest = material.revisions[0]
          const flags = (latest?.quality_flags ?? {}) as Record<string, unknown>
          const imageDominant = Number(flags.imageDominantPages ?? 0)
          const status = REVISION_STATUS[latest?.status] ?? {
            label: latest?.status ?? 'Chưa xử lý',
            tone: '',
          }

          return (
            <li key={material.id} className="card material-card">
              <div className="material-head">
                <div>
                  <strong>{material.fileName}</strong>
                  <span className="muted">
                    {formatFormat(material.mime, material.fileName)} ·{' '}
                    {material.pageCount ? `${material.pageCount} trang` : 'chưa đếm trang'} ·{' '}
                    {(material.sizeBytes / 1024 / 1024).toFixed(1)} MB
                  </span>
                </div>
                <span className={`chip ${status.tone}`}>{status.label}</span>
              </div>

              <dl className="material-facts">
                <div>
                  <dt>Tải lên</dt>
                  <dd>{formatTimestamp(material.uploadedAt)}</dd>
                </div>
                <div>
                  <dt>Kế hoạch đang dùng</dt>
                  <dd>{material.plansUsing}</dd>
                </div>
                <div>
                  <dt>Lần xử lý</dt>
                  <dd>#{latest?.revision_no ?? 0}</dd>
                </div>
              </dl>

              {latest?.failure_reason && (
                <Banner tone="danger">
                  <strong>Xử lý thất bại.</strong>
                  <p>{latest.failure_reason}</p>
                </Banner>
              )}

              {imageDominant > 0 && (
                <Banner tone="warn">
                  {imageDominant} trang chủ yếu là hình hoặc sơ đồ. Bài giảng sẽ nhắc bạn mở slide
                  gốc ở những phần đó.
                </Banner>
              )}

              <div className="material-actions">
                <button type="button" className="ghost" onClick={() => openViewer(material.id)}>
                  Xem tài liệu gốc
                </button>
                <button
                  type="button"
                  className="ghost"
                  disabled={reprocess.isPending || !today.data?.planId}
                  onClick={() => reprocess.mutate(material.id)}
                >
                  Xử lý lại
                </button>
              </div>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

const REVISION_STATUS: Record<string, { label: string; tone: string }> = {
  UPLOADED: { label: 'Đã nhận', tone: '' },
  EXTRACTING: { label: 'Đang đọc', tone: 'warn' },
  EXTRACTED: { label: 'Đã đọc xong', tone: 'warn' },
  STRUCTURING: { label: 'Đang dựng cấu trúc', tone: 'warn' },
  READY: { label: 'Sẵn sàng', tone: 'good' },
  FAILED: { label: 'Thất bại', tone: 'danger' },
}

function formatFormat(mime: string, fileName: string): string {
  if (mime?.includes('pdf')) return 'PDF'
  if (mime?.includes('presentation') || fileName.toLowerCase().endsWith('.pptx')) return 'PPTX'
  return mime || 'không rõ định dạng'
}

function formatTimestamp(value?: string): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('vi-VN')
}
