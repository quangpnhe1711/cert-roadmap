import { useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type {
  PlanDay,
  ProgressView,
  QuizResponse,
  TodayView,
  WeaknessExplanation,
  WeakTopic,
} from '../api/types'
import { Banner, EmptyState, ErrorState, InlineError, Loading } from '../components/states'
import { SyntheticMappingNotice } from '../components/SyntheticMappingNotice'
import { formatDay, formatMinutes } from './TodayPage'

/**
 * Schedule and progress in one screen with two tabs.
 *
 * They are read together - "where am I" and "what is left" are the same
 * question - so splitting them into separate routes would only add navigation.
 * The schedule is grouped into done / today / ahead rather than shown as one
 * flat list, because a learner scanning forty rows for today's date is doing the
 * screen's job for it.
 */
export function PlanPage() {
  const [tab, setTab] = useState<'schedule' | 'progress'>('schedule')
  const navigate = useNavigate()

  const today = useQuery({ queryKey: ['today'], queryFn: () => api.get<TodayView>('/api/v1/today') })
  const planId = today.data?.planId

  const days = useQuery({
    queryKey: ['plan-days', planId],
    enabled: Boolean(planId),
    queryFn: () => api.get<PlanDay[]>(`/api/v1/plans/${planId}/days`),
  })

  const progress = useQuery({
    queryKey: ['progress'],
    enabled: Boolean(planId) && tab === 'progress',
    queryFn: () => api.get<ProgressView>('/api/v1/progress'),
  })

  const startExam = useMutation({
    mutationFn: () =>
      api.post<QuizResponse>(`/api/v1/plans/${planId}/quizzes`, { kind: 'FINAL_REVIEW' }),
    onSuccess: (data) => navigate(`/quiz/${data.attempt.id}`),
  })

  if (today.isPending) return <Loading />
  if (!today.data?.hasPlan) {
    return (
      <div className="page">
        <EmptyState
          icon="🗂"
          title="Chưa có kế hoạch đang học"
          hint="Lịch và tiến độ xuất hiện ở đây sau khi bạn tạo kế hoạch và bắt đầu học."
          action={
            <button type="button" onClick={() => navigate('/plans')}>
              Xem kế hoạch của tôi
            </button>
          }
        />
      </div>
    )
  }

  const todayId = today.data.studyDayId

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>Lịch &amp; tiến độ</h1>
          <p className="muted">Bạn đã học tới đâu, hôm nay làm gì, và phần nào còn yếu.</p>
        </div>
        {/* Toggle buttons rather than an ARIA tablist: a tablist without managed
            focus and aria-controls announces a pattern it does not implement,
            and aria-pressed is exactly true of what these are. */}
        <div className="tabs">
          <button
            type="button"
            aria-pressed={tab === 'schedule'}
            className={tab === 'schedule' ? 'on' : ''}
            onClick={() => setTab('schedule')}
          >
            Lịch học
          </button>
          <button
            type="button"
            aria-pressed={tab === 'progress'}
            className={tab === 'progress' ? 'on' : ''}
            onClick={() => setTab('progress')}
          >
            Tiến độ
          </button>
        </div>
      </header>

      {tab === 'schedule' && (
        <>
          {days.isPending && <Loading />}
          {days.error && <ErrorState error={days.error} onRetry={() => days.refetch()} />}
          {days.data && <Schedule days={days.data} todayId={todayId} />}
        </>
      )}

      {tab === 'progress' && (
        <>
          {progress.isPending && <Loading />}
          {progress.error && <ErrorState error={progress.error} />}
          {progress.data && (
            <ProgressPanel
              data={progress.data}
              planId={planId!}
              onStartExam={() => startExam.mutate()}
            />
          )}
          {startExam.error && <InlineError error={startExam.error} />}
        </>
      )}
    </div>
  )
}

/** Done, today, ahead. Three answers to three different questions. */
function Schedule({ days, todayId }: { days: PlanDay[]; todayId?: string }) {
  const todayIndex = todayId ? days.findIndex((d) => d.id === todayId) : -1
  const done = days.filter((d) => d.status === 'COMPLETED' || d.status === 'SKIPPED')
  const current = todayIndex >= 0 ? [days[todayIndex]] : []
  const ahead = days.filter(
    (d) => !done.includes(d) && !current.includes(d),
  )

  return (
    <div className="schedule-groups">
      <ScheduleGroup
        title="Hôm nay"
        empty="Không còn buổi nào được xếp cho hôm nay."
        days={current}
        highlight
      />
      <ScheduleGroup
        title={`Sắp tới (${ahead.length})`}
        empty="Không còn buổi nào phía trước."
        days={ahead}
      />
      <ScheduleGroup
        title={`Đã qua (${done.length})`}
        empty="Bạn chưa hoàn thành buổi nào."
        days={done}
        collapsible
      />
    </div>
  )
}

function ScheduleGroup({
  title,
  empty,
  days,
  highlight,
  collapsible,
}: {
  title: string
  empty: string
  days: PlanDay[]
  highlight?: boolean
  collapsible?: boolean
}) {
  // Open by default even when collapsible. "What did I finish?" is one of the
  // questions this screen exists to answer, and answering it behind a click
  // means the learner has to already suspect the answer is there.
  const [open, setOpen] = useState(true)

  return (
    <section className={`schedule-group ${highlight ? 'highlight' : ''}`}>
      <h2>
        {collapsible ? (
          <button type="button" className="linky" onClick={() => setOpen(!open)}>
            {open ? '▾' : '▸'} {title}
          </button>
        ) : (
          title
        )}
      </h2>
      {open &&
        (days.length === 0 ? (
          <p className="muted">{empty}</p>
        ) : (
          <ol className="schedule">
            {days.map((day) => (
              <li key={day.id} className={`day ${day.status.toLowerCase()}`}>
                <div className="day-date">
                  <strong>{formatDay(day.date)}</strong>
                  <span className="muted">Ngày {day.dayIndex}</span>
                </div>
                <div className="day-body">
                  <div className="day-chips">
                    <span className={`chip status-${day.status.toLowerCase()}`}>
                      {STATUS_LABEL[day.status] ?? day.status}
                    </span>
                    {day.kind === 'REVIEW' && <span className="chip warn">Ôn tập</span>}
                  </div>
                  <ul>
                    {/* Not "no content": an empty day is a deliberate outcome of
                        the scheduler, and it is where a missed day gets caught up. */}
                    {day.itemTitles.length === 0 && (
                      <li className="muted">Ngày trống — dự phòng để học bù</li>
                    )}
                    {day.itemTitles.map((title, i) => (
                      <li key={i}>{title}</li>
                    ))}
                  </ul>
                </div>
                <div className="day-load muted">
                  {formatMinutes(day.allocatedMinutes)}
                  <span> / {formatMinutes(day.capacityMinutes)}</span>
                </div>
              </li>
            ))}
          </ol>
        ))}
    </section>
  )
}

function ProgressPanel({
  data,
  planId,
  onStartExam,
}: {
  data: ProgressView
  planId: string
  onStartExam: () => void
}) {
  return (
    <div className="progress">
      <div className="stat-row">
        <Stat label="Buổi đã hoàn thành" value={`${data.completedDays}/${data.totalDays}`} />
        <Stat label="Buổi bỏ lỡ" value={String(data.skippedDays)} />
        <Stat label="Ngày tới kỳ thi" value={String(data.daysUntilExam)} />
        <Stat label="Câu hỏi trong ngân hàng" value={String(data.finalExam?.bankSize ?? 0)} />
      </div>

      <section>
        <h2>Theo domain của kỳ thi</h2>
        <p className="muted">Đây là tiến độ học, không phải điểm thi dự đoán.</p>
        <div className="coverage-bars">
          {data.domains.map((d) => (
            <div key={d.code} className="coverage-row">
              <span className="coverage-label">
                {d.code} · {d.title} <span className="muted">{d.weightPercent}%</span>
              </span>
              <span className="bar">
                <span style={{ width: `${d.accuracyPercent}%` }} />
              </span>
              <span className="muted">
                {d.total > 0 ? `${d.accuracyPercent}% · ${d.correct}/${d.total}` : 'chưa kiểm tra'}
              </span>
            </div>
          ))}
        </div>
      </section>

      <section>
        <h2>Chủ đề cần ôn lại</h2>
        {data.weakTopics.length === 0 ? (
          <p className="muted">
            Chưa phát hiện chủ đề nào yếu. Hệ thống chỉ đánh dấu khi đã có đủ câu trả lời để kết luận.
          </p>
        ) : (
          <ul className="weak-list">
            {data.weakTopics.map((topic) => (
              <WeakTopicRow key={topic.courseTopicId} planId={planId} topic={topic} />
            ))}
          </ul>
        )}
      </section>

      {data.coverage && (
        <section>
          <h2>Độ phủ của tài liệu</h2>
          {data.coverage.coverageEvaluated ? (
            <>
              <p>
                Tài liệu phủ khoảng <strong>{data.coverage.weightedCoveragePercent}%</strong> phạm vi
                bài thi <span className="muted">(ước tính)</span>.
              </p>
              {data.coverage.gaps.length > 0 && (
                <details>
                  <summary>{data.coverage.gaps.length} mục chưa được phủ đủ</summary>
                  <ul>
                    {data.coverage.gaps.map((gap) => (
                      <li key={gap.taskStatementId}>
                        <code>{gap.code}</code> {gap.title}
                      </li>
                    ))}
                  </ul>
                </details>
              )}
            </>
          ) : (
            <SyntheticMappingNotice />
          )}
        </section>
      )}

      <section>
        <h2>Bài ôn tổng hợp</h2>
        {data.finalExam?.ready ? (
          <>
            <p className="muted">
              Đề được lắp từ {data.finalExam.bankSize} câu đã qua kiểm tra, chọn theo trọng số domain
              chính thức.
            </p>
            <button type="button" onClick={onStartExam}>
              Bắt đầu bài ôn
            </button>
          </>
        ) : (
          <Banner tone="info">
            Ngân hàng câu hỏi hiện có {data.finalExam?.bankSize ?? 0} câu, cần tối thiểu{' '}
            {data.finalExam?.minimumRequired ?? 10}. Hoàn thành thêm buổi học để mở bài ôn.
          </Banner>
        )}
      </section>

      {data.adjustments.length > 0 && (
        <section>
          <h2>Lịch sử điều chỉnh lịch</h2>
          <ul className="adjustments">
            {data.adjustments.map((adj, i) => (
              <li key={i}>
                <strong>{ADJUSTMENT_LABEL[adj.reason_code] ?? adj.reason_code}</strong>
                <span className="muted">{new Date(adj.created_at).toLocaleString('vi-VN')}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}

/**
 * A weak topic, with the explanation fetched only when the learner asks.
 *
 * The detection above it is arithmetic and always available; the explanation
 * costs a model call, so it is generated on demand rather than for every topic
 * on every page load. When it is unavailable the row stays exactly as useful as
 * it was - which is why the fetch has no error state of its own.
 */
function WeakTopicRow({ planId, topic }: { planId: string; topic: WeakTopic }) {
  const [open, setOpen] = useState(false)

  const explanation = useQuery({
    queryKey: ['weakness', planId, topic.courseTopicId],
    enabled: open,
    staleTime: Infinity,
    retry: false,
    queryFn: () =>
      api.get<WeaknessExplanation | undefined>(
        `/api/v1/plans/${planId}/weak-topics/${topic.courseTopicId}/explanation`,
      ),
  })

  return (
    <li>
      <div className="weak-head">
        <div>
          <strong>{topic.title}</strong>
          <span className="muted">
            {Math.round(topic.accuracy * 100)}% đúng trên {topic.answeredCount} câu · domain{' '}
            {topic.domainWeightPercent}%
          </span>
        </div>
        <button type="button" className="ghost small" onClick={() => setOpen(!open)}>
          {open ? 'Thu gọn' : 'Vì sao?'}
        </button>
      </div>

      {open && (
        <div className="weak-body">
          {explanation.isFetching && <Loading label="Đang phân tích lỗi sai của bạn…" />}
          {!explanation.isFetching && !explanation.data && (
            <p className="muted">
              Chưa có phần giải thích cho chủ đề này. Tiến độ và điểm số không bị ảnh hưởng.
            </p>
          )}
          {explanation.data && (
            <>
              <p>{explanation.data.summary}</p>
              {explanation.data.keyReminders.length > 0 && (
                <ul>
                  {explanation.data.keyReminders.map((reminder, i) => (
                    <li key={i}>{reminder}</li>
                  ))}
                </ul>
              )}
            </>
          )}
        </div>
      )}
    </li>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="stat">
      <strong>{value}</strong>
      <span>{label}</span>
    </div>
  )
}

const STATUS_LABEL: Record<string, string> = {
  PLANNED: 'Chưa học',
  IN_PROGRESS: 'Đang học',
  COMPLETED: 'Đã xong',
  SKIPPED: 'Đã bỏ lỡ',
  RESCHEDULED: 'Đã dời',
}

const ADJUSTMENT_LABEL: Record<string, string> = {
  SCHEDULE_REBUILT: 'Xếp lại lịch',
  COMPRESSION_L1: 'Bỏ nội dung ngoài phạm vi thi',
  COMPRESSION_L2: 'Bỏ nội dung ít liên quan',
  COMPRESSION_L3: 'Rút gọn bài giảng mức trung bình',
  UNITS_DROPPED_FOR_CAPACITY: 'Bỏ bớt nội dung do thiếu thời gian',
  LOW_MAPPING_RATE: 'Tài liệu có thể không khớp chứng chỉ đã chọn',
}
