import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, ApiError } from '../api/client'
import type { AiUsage, InfeasibleSchedule, Plan } from '../api/types'
import { Banner, EmptyState, InlineError, Loading } from '../components/states'
import { AiStatusChip, modelHint, useMeta } from '../components/AiStatus'
import { useAuth } from '../auth/AuthContext'

const PROVENANCE: Record<string, string> = {
  MODEL: 'Mô hình thật',
  SYNTHETIC: 'Adapter mô phỏng',
  NONE: 'Chưa sinh nội dung',
}

const DAYS = [
  ['MONDAY', 'Thứ 2'],
  ['TUESDAY', 'Thứ 3'],
  ['WEDNESDAY', 'Thứ 4'],
  ['THURSDAY', 'Thứ 5'],
  ['FRIDAY', 'Thứ 6'],
  ['SATURDAY', 'Thứ 7'],
  ['SUNDAY', 'Chủ nhật'],
] as const

/**
 * Changing the exam date or weekly hours replans the remaining days immediately,
 * and the result is shown - including a refusal with options when the new
 * constraints make the plan impossible.
 */
export function SettingsPage() {
  const { user } = useAuth()
  const queryClient = useQueryClient()
  const meta = useMeta()
  const [examDate, setExamDate] = useState('')
  const [minutes, setMinutes] = useState<Record<string, number>>({})
  const [infeasible, setInfeasible] = useState<InfeasibleSchedule | null>(null)
  const [saved, setSaved] = useState<string | null>(null)

  const plan = useQuery({
    queryKey: ['plan-current'],
    queryFn: async () => {
      const result = await api.get<Plan | undefined>('/api/v1/plans/current')
      return result ?? null
    },
  })

  // Where *this plan's* content came from, which is not the same question as
  // what the deployment is configured with today.
  const usage = useQuery({
    queryKey: ['ai-usage', plan.data?.id],
    enabled: Boolean(plan.data?.id),
    queryFn: () => api.get<AiUsage>(`/api/v1/plans/${plan.data?.id}/ai-usage`),
  })

  useEffect(() => {
    if (plan.data) {
      setExamDate(plan.data.examDate)
      setMinutes(plan.data.weeklyCapacity)
    }
  }, [plan.data])

  const save = useMutation({
    mutationFn: () =>
      api.patch<{ dayCount: number; droppedUnits: unknown[] }>(
        `/api/v1/plans/${plan.data?.id}/constraints`,
        { examDate, weeklyCapacity: minutes },
      ),
    onSuccess: (result) => {
      setInfeasible(null)
      setSaved(`Đã cập nhật. Lịch còn lại: ${result.dayCount} ngày.`)
      queryClient.invalidateQueries({ queryKey: ['today'] })
      queryClient.invalidateQueries({ queryKey: ['plan-days'] })
      queryClient.invalidateQueries({ queryKey: ['plan-current'] })
    },
    onError: (error) => {
      setSaved(null)
      if (error instanceof ApiError && error.code === 'SCHEDULE_INFEASIBLE') {
        setInfeasible(error.body as InfeasibleSchedule)
      }
    },
  })

  if (plan.isPending) return <Loading />

  // Account and AI mode are properties of the deployment, not of a plan, so they
  // render whether or not the learner has one. Sending someone with no plan to
  // an empty state would hide the one screen that says what this build's AI is.
  const header = (
    <>
      <header className="page-head">
        <div>
          <h1>Cài đặt</h1>
          <p className="muted">Tài khoản, chế độ AI, và các ràng buộc của kế hoạch hiện tại.</p>
        </div>
      </header>

      <section className="card">
        <h2>Tài khoản</h2>
        <dl className="settings-facts">
          <div>
            <dt>Tên hiển thị</dt>
            <dd>{user?.displayName}</dd>
          </div>
          <div>
            <dt>Email</dt>
            <dd>{user?.email}</dd>
          </div>
        </dl>
      </section>

      <section className="card">
        <h2>Chế độ AI</h2>
        <div className="ai-setting">
          <AiStatusChip meta={meta.data} />
          <p className="muted">{modelHint(meta.data)}</p>
        </div>
        {meta.data?.aiSynthetic ? (
          <Banner tone="warn">
            <strong>Chế độ phát triển.</strong>
            <p>
              Bài giảng và câu hỏi do adapter mô phỏng sinh ra để chạy thử toàn bộ luồng. Nội dung
              không phản ánh chất lượng thật và không dùng để đánh giá.
            </p>
          </Banner>
        ) : meta.data?.aiConfigured ? (
          <Banner tone="good">
            <strong>Đang dùng mô hình thật.</strong>
            <p>Bài giảng và câu hỏi được sinh bởi {meta.data.aiProviderLabel} từ tài liệu của bạn.</p>
          </Banner>
        ) : (
          <Banner tone="danger">
            <strong>Chưa cấu hình thông tin xác thực.</strong>
            <p>
              Máy chủ đang đặt nhà cung cấp là {meta.data?.aiProviderLabel} nhưng chưa có khoá.
              Việc này được cấu hình bằng biến môi trường phía máy chủ.
            </p>
          </Banner>
        )}
        {usage.data && usage.data.providerCalls + usage.data.cacheHits > 0 && (
          <>
            <h3>Kế hoạch hiện tại đã dùng</h3>
            <dl className="settings-facts">
              <div>
                <dt>Nguồn nội dung</dt>
                <dd>{PROVENANCE[usage.data.provenance]}</dd>
              </div>
              <div>
                <dt>Mô hình đã dùng</dt>
                <dd>{usage.data.models.length ? usage.data.models.join(', ') : '—'}</dd>
              </div>
              <div>
                <dt>Token vào / ra</dt>
                <dd>
                  {usage.data.tokensIn.toLocaleString('vi-VN')} /{' '}
                  {usage.data.tokensOut.toLocaleString('vi-VN')}
                </dd>
              </div>
              <div>
                <dt>Gọi mô hình</dt>
                <dd>
                  {usage.data.providerCalls} · {usage.data.cacheHits} lần dùng lại
                </dd>
              </div>
              <div>
                <dt>Chi phí</dt>
                <dd>
                  {usage.data.pricingConfigured
                    ? `${(usage.data.costCents / 100).toFixed(2)} USD`
                    : 'chưa cấu hình đơn giá'}
                </dd>
              </div>
            </dl>
            {!usage.data.pricingConfigured && (
              <p className="muted footnote">
                Lượng token là số đo thật. Đơn giá của mô hình chưa được cấu hình, nên hệ thống
                không hiển thị một con số chi phí mà nó không đứng sau được.
              </p>
            )}
          </>
        )}
        <p className="muted footnote">
          Khoá API chỉ tồn tại trong biến môi trường của máy chủ. Ứng dụng không nhận, không hiển
          thị và không lưu khoá ở bất kỳ đâu.
        </p>
      </section>
    </>
  )

  if (!plan.data) {
    return (
      <div className="page">
        {header}
        <section className="card">
          <EmptyState
            icon="📅"
            title="Chưa có kế hoạch đang học"
            hint="Ngày thi và thời gian học được chỉnh ở đây khi bạn có một kế hoạch đang chạy."
          />
        </section>
      </div>
    )
  }

  return (
    <div className="page">
      {header}

      <section className="card">
        <h2>Chứng chỉ</h2>
        <p>
          <strong>{plan.data.certification.name}</strong>
          <span className="muted">
            {' '}
            {plan.data.certification.provider} · {plan.data.certification.exam_code} ·{' '}
            phiên bản {plan.data.certification.version_label}
          </span>
        </p>
        <p className="muted">
          Kế hoạch được gắn cố định vào phiên bản kỳ thi này; nếu nhà cung cấp cập nhật, hệ thống sẽ
          báo trước khi thay đổi bất cứ điều gì.
        </p>
      </section>

      <section className="card">
        <h2>Ngày thi và thời gian học</h2>
        <label htmlFor="examDate">Ngày thi</label>
        <input
          id="examDate"
          type="date"
          value={examDate}
          onChange={(e) => setExamDate(e.target.value)}
        />

        <label>Số phút học mỗi ngày</label>
        <div className="capacity-list">
          {DAYS.map(([key, label]) => (
            <div key={key} className="capacity-row">
              <span>{label}</span>
              <input
                type="number"
                min={0}
                max={720}
                step={15}
                value={minutes[key] ?? 0}
                onChange={(e) =>
                  setMinutes({ ...minutes, [key]: Math.max(0, Number(e.target.value) || 0) })
                }
              />
              <span className="muted">phút</span>
            </div>
          ))}
        </div>

        {saved && <Banner tone="good">{saved}</Banner>}
        {infeasible && (
          <div className="banner danger">
            <strong>
              Với thiết lập này lịch không còn khả thi (thiếu khoảng{' '}
              {Math.round(infeasible.deficitMinutes / 60)} giờ).
            </strong>
            <ul>
              {infeasible.suggestions.map((s) => (
                <li key={s.code}>{s.detail}</li>
              ))}
            </ul>
            <p className="muted">Lịch hiện tại được giữ nguyên, không có gì bị ghi đè.</p>
          </div>
        )}
        {save.error && !infeasible && <InlineError error={save.error} />}

        <button type="button" disabled={save.isPending} onClick={() => save.mutate()}>
          {save.isPending ? 'Đang xếp lại lịch…' : 'Lưu và xếp lại lịch'}
        </button>
      </section>

      <section className="card">
        <h2>Dữ liệu của bạn</h2>
        <p className="muted">
          Tài liệu bạn tải lên là riêng tư, không được chia sẻ, không dùng để huấn luyện mô hình, và
          chỉ phục vụ việc tạo lịch và bài giảng cho chính bạn.
        </p>
      </section>
    </div>
  )
}
