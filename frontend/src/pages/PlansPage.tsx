import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { PlanSummary } from '../api/types'
import { EmptyState, ErrorState, InlineError, Loading } from '../components/states'

/**
 * Every plan the learner has had, not just the one they are on.
 *
 * The product allows one plan *at a time*, which is a scheduling constraint -
 * two live plans would compete for the same hours. It is not a limit on how many
 * certifications someone studies for over a year, and before this screen existed
 * the only way to start a second plan was to lose the first.
 */
export function PlansPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const plans = useQuery({
    queryKey: ['plans'],
    queryFn: () => api.get<PlanSummary[]>('/api/v1/plans'),
  })

  const archive = useMutation({
    mutationFn: (planId: string) => api.post(`/api/v1/plans/${planId}/archive`),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['plans'] })
      await queryClient.invalidateQueries({ queryKey: ['today'] })
      await queryClient.invalidateQueries({ queryKey: ['plan-current'] })
    },
  })

  if (plans.isPending) return <Loading />
  if (plans.error) return <ErrorState error={plans.error} onRetry={() => plans.refetch()} />

  const all = plans.data ?? []
  const active = all.find((plan) => IN_FLIGHT.includes(plan.status))
  const history = all.filter((plan) => plan !== active)

  if (all.length === 0) {
    return (
      <div className="page">
        <EmptyState
          icon="📚"
          title="Bạn chưa có kế hoạch học nào"
          hint="Tạo kế hoạch từ ngày thi và tài liệu khoá học của bạn. Mất khoảng hai phút."
          action={
            <button type="button" onClick={() => navigate('/onboarding')}>
              Tạo kế hoạch học
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
          <h1>Kế hoạch của bạn</h1>
          <p className="muted">
            Mỗi lúc chỉ chạy một kế hoạch. Kế hoạch đã xong vẫn ở lại đây để bạn ôn tiếp.
          </p>
        </div>
        <button
          type="button"
          className={active ? 'ghost' : ''}
          onClick={() => navigate('/onboarding')}
        >
          + Kế hoạch mới
        </button>
      </header>

      {archive.error && <InlineError error={archive.error} />}

      <ul className="plan-cards">
        {all.map((plan) => (
          <PlanCard
            key={plan.id}
            plan={plan}
            current={plan === active}
            archiving={archive.isPending}
            onArchive={() => archive.mutate(plan.id)}
            navigate={navigate}
          />
        ))}
      </ul>

      {active && history.length === 0 && (
        <p className="muted footnote">
          Khi kế hoạch này kết thúc, nó sẽ ở lại đây và bạn có thể bắt đầu kế hoạch tiếp theo.
        </p>
      )}
    </div>
  )
}

function PlanCard({
  plan,
  current,
  archiving,
  onArchive,
  navigate,
}: {
  plan: PlanSummary
  current: boolean
  archiving: boolean
  onArchive: () => void
  navigate: (to: string) => void
}) {
  const status = STATUS[plan.status] ?? { label: plan.status, tone: '' }

  return (
    <li className={`plan-card ${current ? 'current' : ''}`}>
      <div className="plan-card-head">
        <div>
          <span className="eyebrow">{plan.provider}</span>
          <h2>{plan.examCode}</h2>
          <p className="muted">{plan.certificationName}</p>
        </div>
        <span className={`chip ${status.tone}`}>{status.label}</span>
      </div>

      <dl className="plan-facts">
        <div>
          <dt>Ngày thi</dt>
          <dd>{formatDate(plan.examDate)}</dd>
        </div>
        <div>
          <dt>Còn lại</dt>
          <dd>{plan.daysUntilExam > 0 ? `${plan.daysUntilExam} ngày` : 'Đã qua'}</dd>
        </div>
        <div>
          <dt>Tiến độ</dt>
          <dd>
            {plan.totalDays > 0
              ? `${plan.completedDays}/${plan.totalDays} buổi`
              : 'Chưa xếp lịch'}
          </dd>
        </div>
      </dl>

      {plan.totalDays > 0 && (
        <div className="progressbar" aria-label={`Tiến độ ${plan.progressPercent}%`}>
          <span style={{ width: `${plan.progressPercent}%` }} />
        </div>
      )}

      <div className="plan-card-actions">
        {primaryAction(plan, navigate)}
        {CAN_ARCHIVE.includes(plan.status) && (
          <button type="button" className="ghost" disabled={archiving} onClick={onArchive}>
            Lưu trữ
          </button>
        )}
      </div>
    </li>
  )
}

/**
 * One button per card, chosen from the plan's own state.
 *
 * A finished plan is still useful - the final review exam is exactly what the
 * week before the exam is for - so it gets "Ôn tổng hợp", never a dead card.
 */
function primaryAction(plan: PlanSummary, navigate: (to: string) => void) {
  switch (plan.status) {
    case 'ACTIVE':
      return (
        <button type="button" onClick={() => navigate('/today')}>
          Tiếp tục học
        </button>
      )
    case 'COMPLETED':
      return (
        <button type="button" onClick={() => navigate('/today')}>
          Ôn tổng hợp
        </button>
      )
    case 'DRAFT':
    case 'ANALYZING':
    case 'READY_FOR_REVIEW':
      return (
        <button type="button" onClick={() => navigate('/onboarding')}>
          Tiếp tục thiết lập
        </button>
      )
    default:
      return (
        <button type="button" className="ghost" onClick={() => navigate('/plan')}>
          Xem lại
        </button>
      )
  }
}

const IN_FLIGHT = ['DRAFT', 'ANALYZING', 'READY_FOR_REVIEW', 'ACTIVE']
const CAN_ARCHIVE = ['ACTIVE', 'COMPLETED', 'EXPIRED', 'READY_FOR_REVIEW', 'DRAFT']

const STATUS: Record<string, { label: string; tone: string }> = {
  DRAFT: { label: 'Đang thiết lập', tone: '' },
  ANALYZING: { label: 'Đang phân tích', tone: 'warn' },
  READY_FOR_REVIEW: { label: 'Chờ xác nhận', tone: 'warn' },
  ACTIVE: { label: 'Đang học', tone: 'good' },
  COMPLETED: { label: 'Đã hoàn thành', tone: 'good' },
  EXPIRED: { label: 'Đã qua kỳ thi', tone: '' },
  ARCHIVED: { label: 'Đã lưu trữ', tone: '' },
}

export function formatDate(iso: string): string {
  const [year, month, day] = iso.split('-')
  return `${day}/${month}/${year}`
}
