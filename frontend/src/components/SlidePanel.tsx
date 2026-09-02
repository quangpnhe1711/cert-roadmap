import { useQuery } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import { Banner, Loading, ErrorState } from './states'

/**
 * The original slide, next to the lesson.
 *
 * This panel is what makes "companion, not replacement" true rather than a
 * claim: every generated block cites a page, and the learner can always look at
 * that page. It is also the honest answer for diagram-heavy slides, where the
 * extracted text cannot represent what is on the page.
 *
 * The browser renders the PDF from a short-lived signed URL, so no server-side
 * render pipeline is needed.
 */
export function SlidePanel({
  materialId,
  pageStart,
  pageEnd,
  onClose,
}: {
  materialId: string
  pageStart: number
  pageEnd?: number
  onClose: () => void
}) {
  const { data, isPending, error, refetch } = useQuery({
    queryKey: ['viewer-url', materialId],
    queryFn: () => api.get<{ url: string; expiresInSeconds: number }>(
      `/api/v1/materials/${materialId}/viewer-url`,
    ),
    staleTime: 10 * 60 * 1000,
  })

  return (
    <aside className="slide-panel">
      <header>
        <strong>
          Tài liệu gốc · trang {pageStart}
          {pageEnd && pageEnd !== pageStart ? `–${pageEnd}` : ''}
        </strong>
        <button type="button" className="ghost" onClick={onClose}>
          Đóng
        </button>
      </header>
      {isPending && <Loading label="Đang mở tài liệu…" />}
      {/* PPTX has no rendition pipeline yet, so say so plainly rather than
          showing an empty frame the learner will read as a bug. */}
      {error instanceof ApiError && error.code === 'VIEWER_UNAVAILABLE' ? (
        <Banner tone="warn">
          Chưa mở được tài liệu gốc trong ứng dụng cho định dạng này. Bài giảng vẫn ghi rõ số trang —
          hãy mở tệp bằng phần mềm của bạn ở trang {pageStart}.
        </Banner>
      ) : (
        error && <ErrorState error={error} onRetry={() => refetch()} />
      )}
      {data && (
        <iframe
          title="Tài liệu gốc"
          src={`${data.url}#page=${pageStart}&view=FitH`}
          className="pdf-frame"
        />
      )}
    </aside>
  )
}
