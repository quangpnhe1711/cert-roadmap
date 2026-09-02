import type { ReactNode } from 'react'
import { ApiError } from '../api/client'

export function Loading({ label = 'Đang tải…' }: { label?: string }) {
  return (
    <div className="state" role="status" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      {label}
    </div>
  )
}

/**
 * An empty state has to answer three questions: what is missing, why, and what
 * to do about it. "Không có dữ liệu" answers none of them and is banned by
 * construction here - `title` and `action` are the shape of the component.
 */
export function EmptyState({
  title,
  hint,
  action,
  icon,
}: {
  title: string
  hint?: string
  action?: ReactNode
  icon?: string
}) {
  return (
    <div className="state empty">
      {icon && (
        <span className="empty-icon" aria-hidden="true">
          {icon}
        </span>
      )}
      <strong>{title}</strong>
      {hint && <p className="muted">{hint}</p>}
      {action && <div className="empty-action">{action}</div>}
    </div>
  )
}

/**
 * Wording for the backend's stable error codes.
 *
 * The server owns the code and never localises; the client owns the sentence.
 * A code with no entry falls back to the server's own message, which is written
 * to be readable - what never reaches the screen is a stack trace or a provider
 * JSON blob.
 */
const ERROR_COPY: Record<string, { title: string; hint?: string }> = {
  AI_PROVIDER_UNAVAILABLE: {
    title: 'Dịch vụ AI đang không phản hồi',
    hint: 'Nội dung chưa tạo được. Hãy thử lại sau ít phút — tiến độ của bạn không bị mất.',
  },
  PROVIDER_QUOTA: {
    title: 'Đã hết hạn mức AI của hệ thống',
    hint: 'Hạn mức sẽ được đặt lại. Bài đã tạo trước đó vẫn xem được bình thường.',
  },
  BUDGET_EXCEEDED: {
    title: 'Kế hoạch này đã dùng hết ngân sách AI',
    hint: 'Bài giảng và câu hỏi đã tạo vẫn dùng được. Liên hệ quản trị để nâng hạn mức.',
  },
  ACTIVE_PLAN_EXISTS: {
    title: 'Bạn đang có một kế hoạch đang học',
    hint: 'Mỗi lúc chỉ chạy được một kế hoạch. Hãy hoàn thành hoặc lưu trữ kế hoạch hiện tại trước.',
  },
  SCHEDULE_INFEASIBLE: {
    title: 'Không đủ thời gian cho toàn bộ nội dung',
    hint: 'Hãy lùi ngày thi, tăng số giờ học, hoặc bỏ bớt phần bạn đã nắm.',
  },
  UNSUPPORTED_FORMAT: {
    title: 'Định dạng tệp chưa được hỗ trợ',
    hint: 'Hãy tải lên bản PDF. PPTX đang ở mức thử nghiệm.',
  },
  INVALID_PDF: {
    title: 'Không đọc được tệp PDF này',
    hint: 'Tệp có thể bị hỏng hoặc là bản scan không có lớp văn bản.',
  },
  EXAM_DUMP_REJECTED: {
    title: 'Tài liệu này giống đề thi rò rỉ',
    hint: 'Hệ thống chỉ nhận tài liệu học. Hãy tải lên slide hoặc giáo trình của khoá học.',
  },
  FILE_TOO_LARGE: {
    title: 'Tệp quá lớn',
    hint: 'Giới hạn 200 MB cho mỗi tệp.',
  },
  PROCESSING_FAILED: {
    title: 'Xử lý tài liệu thất bại',
    hint: 'Bạn có thể thử xử lý lại, hoặc tải lên một tệp khác.',
  },
  PLAN_NOT_FOUND: { title: 'Không tìm thấy kế hoạch' },
  MATERIAL_NOT_FOUND: { title: 'Không tìm thấy tài liệu' },
  NO_ACTIVE_PLAN: {
    title: 'Bạn chưa có kế hoạch đang học',
    hint: 'Tạo một kế hoạch từ ngày thi và tài liệu của bạn.',
  },
  UNAUTHENTICATED: {
    title: 'Phiên đăng nhập đã hết hạn',
    hint: 'Hãy đăng nhập lại để tiếp tục.',
  },
  INVALID_CREDENTIALS: { title: 'Email hoặc mật khẩu không đúng' },
  VALIDATION_FAILED: { title: 'Thông tin nhập vào chưa hợp lệ' },
}

export function describeError(error: unknown): { title: string; hint?: string; code?: string } {
  if (error instanceof ApiError) {
    const copy = ERROR_COPY[error.code]
    if (copy) return { ...copy, code: error.code }
    return { title: error.message, code: error.code }
  }
  if (error instanceof Error) return { title: error.message }
  return { title: 'Đã có lỗi xảy ra' }
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const { title, hint, code } = describeError(error)
  return (
    <div className="state error" role="alert">
      <strong>{title}</strong>
      {hint && <p className="muted">{hint}</p>}
      {onRetry && (
        <button type="button" className="ghost" onClick={onRetry}>
          Thử lại
        </button>
      )}
      {code && <code className="muted error-code">{code}</code>}
    </div>
  )
}

/** The inline form, for an error next to the control that caused it. */
export function InlineError({ error }: { error: unknown }) {
  const { title, hint } = describeError(error)
  return (
    <div className="banner danger" role="alert">
      <strong>{title}</strong>
      {hint && <p>{hint}</p>}
    </div>
  )
}

export function Banner({
  tone,
  children,
}: {
  tone: 'info' | 'warn' | 'danger' | 'good'
  children: ReactNode
}) {
  return <div className={`banner ${tone}`}>{children}</div>
}
