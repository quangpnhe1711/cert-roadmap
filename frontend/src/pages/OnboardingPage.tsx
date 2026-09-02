import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type {
  AnalysisStage,
  AnalysisState,
  CapacityResult,
  Certification,
  CoverageReport,
  InfeasibleSchedule,
  PlanUnit,
} from '../api/types'
import { SyntheticBanner, useMeta } from '../components/AiStatus'
import { Banner, ErrorState, InlineError, Loading } from '../components/states'
import { SyntheticMappingNotice } from '../components/SyntheticMappingNotice'

const DAYS = [
  ['MONDAY', 'T2'],
  ['TUESDAY', 'T3'],
  ['WEDNESDAY', 'T4'],
  ['THURSDAY', 'T5'],
  ['FRIDAY', 'T6'],
  ['SATURDAY', 'T7'],
  ['SUNDAY', 'CN'],
] as const

const WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY']
const WEEKEND = ['SATURDAY', 'SUNDAY']

type Step = 'certification' | 'schedule' | 'material' | 'analyzing' | 'review'

const STEPS: Step[] = ['certification', 'schedule', 'material', 'analyzing', 'review']

const STEP_LABEL: Record<Step, string> = {
  certification: 'Chứng chỉ',
  schedule: 'Thời gian',
  material: 'Tài liệu',
  analyzing: 'Phân tích',
  review: 'Xác nhận',
}

/**
 * Onboarding as one flow rather than a set of screens.
 *
 * Two things drive the order. The capacity reality check runs before any upload,
 * so the learner gets a true answer in seconds instead of watching a spinner and
 * hoping. And the questions are asked in plain language - "bạn học được bao
 * nhiêu mỗi ngày", not "weekly capacity vector" - with the seven-day grid folded
 * away behind an advanced toggle, because almost nobody needs it and everybody
 * was being shown it.
 */
export function OnboardingPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  // This screen lives outside the app shell, so it has to carry the synthetic
  // warning itself. It is also the first place fixture output is shown - the
  // discovered sections and the coverage summary - which makes it the last
  // place the warning may be missing.
  const meta = useMeta()

  const [step, setStep] = useState<Step>('certification')
  const [certificationVersionId, setCertificationVersionId] = useState<string>('')
  const [examDate, setExamDate] = useState<string>(defaultExamDate())
  const [weekdayMinutes, setWeekdayMinutes] = useState(90)
  const [weekendMinutes, setWeekendMinutes] = useState(180)
  const [advanced, setAdvanced] = useState(false)
  const [perDay, setPerDay] = useState<Record<string, number>>(() => simpleCapacity(90, 180))
  const [planId, setPlanId] = useState<string | null>(null)
  const [infeasible, setInfeasible] = useState<InfeasibleSchedule | null>(null)

  // The advanced grid is seeded from the simple answer, so switching to it never
  // silently discards what the learner already said.
  const minutes = advanced ? perDay : simpleCapacity(weekdayMinutes, weekendMinutes)

  const certifications = useQuery({
    queryKey: ['certifications'],
    queryFn: () => api.get<Certification[]>('/api/v1/certifications'),
  })

  useEffect(() => {
    if (!certificationVersionId && certifications.data?.length) {
      setCertificationVersionId(certifications.data[0].certificationVersionId)
    }
  }, [certifications.data, certificationVersionId])

  // Instant, deterministic, and honest: no model call, no upload required.
  const capacity = useQuery({
    queryKey: ['capacity-preview', certificationVersionId, examDate, minutes],
    enabled: step === 'schedule' && Boolean(certificationVersionId),
    queryFn: () =>
      api.post<CapacityResult>('/api/v1/plans/capacity-preview', {
        certificationVersionId,
        examDate,
        weeklyCapacity: minutes,
      }),
  })

  const createPlan = useMutation({
    mutationFn: () =>
      api.post<{ planId: string }>('/api/v1/plans', {
        certificationVersionId,
        examDate,
        weeklyCapacity: minutes,
      }),
    onSuccess: (data) => {
      setPlanId(data.planId)
      setStep('material')
    },
  })

  const upload = useMutation({
    mutationFn: (file: File) => api.upload(`/api/v1/plans/${planId}/materials`, file),
    onSuccess: () => setStep('analyzing'),
  })

  // Polling with backoff is the right shape here: three waiting moments in the
  // whole product do not justify a websocket.
  const analysis = useQuery({
    queryKey: ['analysis', planId],
    enabled: Boolean(planId) && (step === 'analyzing' || step === 'review'),
    queryFn: () => api.get<AnalysisState>(`/api/v1/plans/${planId}/analysis`),
    refetchInterval: (query) => {
      const state = query.state.data
      if (!state) return 1200
      if (state.materialStatus === 'FAILED') return false
      return state.planStatus === 'READY_FOR_REVIEW' ? false : 1200
    },
  })

  useEffect(() => {
    if (step === 'analyzing' && analysis.data?.planStatus === 'READY_FOR_REVIEW') {
      setStep('review')
    }
  }, [analysis.data, step])

  const units = useQuery({
    queryKey: ['plan-units', planId],
    enabled: step === 'review' && Boolean(planId),
    queryFn: () => api.get<PlanUnit[]>(`/api/v1/plans/${planId}/units`),
  })

  const coverage = useQuery({
    queryKey: ['coverage', planId],
    enabled: step === 'review' && Boolean(planId),
    queryFn: () => api.get<CoverageReport>(`/api/v1/plans/${planId}/coverage`),
  })

  const markKnown = useMutation({
    mutationFn: ({ unitId, known }: { unitId: string; known: boolean }) =>
      api.patch(`/api/v1/plans/${planId}/known-units`, { unitIds: [unitId], known }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['plan-units', planId] }),
  })

  const activate = useMutation({
    mutationFn: () => api.post(`/api/v1/plans/${planId}/activate`),
    onSuccess: async () => {
      // Today and the plan list were both fetched while there was no active
      // plan. Without dropping them the learner lands on an empty screen.
      await queryClient.invalidateQueries({ queryKey: ['today'] })
      await queryClient.invalidateQueries({ queryKey: ['plans'] })
      await queryClient.invalidateQueries({ queryKey: ['plan-current'] })
      navigate('/today')
    },
    onError: (error) => {
      // An impossible plan is refused with options rather than faked.
      if (error instanceof ApiError && error.code === 'SCHEDULE_INFEASIBLE') {
        setInfeasible(error.body as InfeasibleSchedule)
      }
    },
  })

  const totalHours = useMemo(
    () => Object.values(minutes).reduce((sum, m) => sum + m, 0) / 60,
    [minutes],
  )

  const activeExists =
    createPlan.error instanceof ApiError && createPlan.error.code === 'ACTIVE_PLAN_EXISTS'

  return (
    <div className="page onboarding">
      <SyntheticBanner meta={meta.data} />
      <header className="onboarding-head">
        <h1 className="wordmark">Cert Study Copilot</h1>
        <p className="muted">Từ tài liệu khoá học của bạn tới lịch ôn từng ngày.</p>
      </header>

      <ol className="steps" aria-label="Các bước thiết lập">
        {STEPS.map((s, i) => (
          <li key={s} className={stepState(step, s)}>
            <span className="steps-dot">{i + 1}</span>
            <span className="steps-label">{STEP_LABEL[s]}</span>
          </li>
        ))}
      </ol>

      {step === 'certification' && (
        <section className="card">
          <h2>Bạn đang ôn chứng chỉ nào?</h2>
          {certifications.isPending && <Loading />}
          {certifications.error && <ErrorState error={certifications.error} />}
          <div className="cert-list">
            {certifications.data?.map((cert) => (
              <label
                key={cert.certificationVersionId}
                className={`cert-option ${
                  certificationVersionId === cert.certificationVersionId ? 'on' : ''
                }`}
              >
                <input
                  type="radio"
                  name="certification"
                  checked={certificationVersionId === cert.certificationVersionId}
                  onChange={() => setCertificationVersionId(cert.certificationVersionId)}
                />
                <span>
                  <strong>{cert.name}</strong>
                  <span className="muted">
                    {cert.provider} · {cert.examCode} · {cert.questionCount} câu ·{' '}
                    {cert.durationMinutes} phút
                    {cert.passingScore ? ` · đạt ${cert.passingScore}` : ' · không công bố điểm đạt'}
                  </span>
                </span>
              </label>
            ))}
          </div>
          <div className="card-actions">
            <button
              type="button"
              disabled={!certificationVersionId}
              onClick={() => setStep('schedule')}
            >
              Tiếp tục
            </button>
          </div>
        </section>
      )}

      {step === 'schedule' && (
        <section className="card">
          <h2>Bao giờ bạn thi, và học được bao nhiêu?</h2>

          <div className="field">
            <label htmlFor="examDate">Ngày thi</label>
            <input
              id="examDate"
              type="date"
              value={examDate}
              onChange={(e) => setExamDate(e.target.value)}
            />
          </div>

          {!advanced ? (
            <div className="simple-capacity">
              <MinutesField
                id="weekday"
                label="Ngày thường (T2–T6)"
                value={weekdayMinutes}
                onChange={setWeekdayMinutes}
              />
              <MinutesField
                id="weekend"
                label="Cuối tuần (T7, CN)"
                value={weekendMinutes}
                onChange={setWeekendMinutes}
              />
            </div>
          ) : (
            <div className="field">
              <label>Số phút mỗi ngày</label>
              <div className="capacity-grid">
                {DAYS.map(([key, label]) => (
                  <div key={key}>
                    <span>{label}</span>
                    <input
                      type="number"
                      min={0}
                      max={720}
                      step={15}
                      value={perDay[key]}
                      onChange={(e) =>
                        setPerDay({ ...perDay, [key]: Math.max(0, Number(e.target.value) || 0) })
                      }
                    />
                  </div>
                ))}
              </div>
            </div>
          )}

          <button
            type="button"
            className="linky"
            onClick={() => {
              if (!advanced) setPerDay(simpleCapacity(weekdayMinutes, weekendMinutes))
              setAdvanced(!advanced)
            }}
          >
            {advanced ? 'Quay lại cách nhập nhanh' : 'Đặt riêng từng ngày trong tuần'}
          </button>

          <p className="muted">Tổng {totalHours.toFixed(1)} giờ mỗi tuần.</p>

          {capacity.isFetching && <Loading label="Đang tính…" />}
          {capacity.data && <CapacityCard result={capacity.data} />}

          {activeExists && (
            <Banner tone="warn">
              <strong>Bạn đang có một kế hoạch đang học.</strong>
              <p>
                Mỗi lúc chỉ chạy được một kế hoạch. Hãy tiếp tục kế hoạch hiện tại, hoặc lưu trữ nó
                ở màn hình Kế hoạch rồi quay lại đây.
              </p>
              <div className="row">
                <button type="button" onClick={() => navigate('/today')}>
                  Tiếp tục kế hoạch hiện tại
                </button>
                <button type="button" className="ghost" onClick={() => navigate('/plans')}>
                  Xem tất cả kế hoạch
                </button>
              </div>
            </Banner>
          )}

          <div className="card-actions">
            <button type="button" className="ghost" onClick={() => setStep('certification')}>
              Quay lại
            </button>
            <button
              type="button"
              disabled={createPlan.isPending || capacity.data?.verdict === 'NO_CAPACITY'}
              onClick={() => createPlan.mutate()}
            >
              {createPlan.isPending ? 'Đang tạo…' : 'Tiếp tục'}
            </button>
          </div>
          {createPlan.error && !activeExists && <InlineError error={createPlan.error} />}
        </section>
      )}

      {step === 'material' && (
        <section className="card">
          <h2>Tải tài liệu bạn đang học</h2>
          <p className="muted">
            Hệ thống đọc chính tài liệu của bạn để chia lịch theo khối lượng thật, không theo mẫu
            có sẵn. Tài liệu là riêng tư và không chia sẻ với ai.
          </p>

          <label className="dropzone">
            <input
              type="file"
              accept=".pdf,.pptx,application/pdf"
              onChange={(e) => {
                const file = e.target.files?.[0]
                if (file) upload.mutate(file)
              }}
              disabled={upload.isPending}
            />
            <strong>Chọn tệp PDF</strong>
            <span className="muted">
              PDF được hỗ trợ đầy đủ. PPTX ở mức thử nghiệm — đọc được nội dung nhưng chưa mở được
              slide gốc cạnh bài giảng.
            </span>
          </label>

          {upload.isPending && <Loading label="Đang tải lên…" />}
          {upload.error && <InlineError error={upload.error} />}
        </section>
      )}

      {step === 'analyzing' && (
        <section className="card">
          <h2>Đang dựng kế hoạch của bạn</h2>
          {analysis.data?.materialStatus === 'FAILED' ? (
            <>
              <PipelineStages stages={analysis.data.stages} />
              <Banner tone="danger">
                <strong>Không xử lý được tài liệu.</strong>
                <p>{analysis.data.failureReason || 'Tệp không đọc được.'}</p>
              </Banner>
              <div className="card-actions">
                <button type="button" onClick={() => setStep('material')}>
                  Tải tệp khác
                </button>
              </div>
            </>
          ) : (
            <>
              <PipelineStages stages={analysis.data?.stages} />
              <QualityFlags flags={analysis.data?.qualityFlags} />
              {analysis.data?.sections && analysis.data.sections.length > 0 && (
                <>
                  <h3>Các phần đã nhận ra</h3>
                  <ul className="section-list">
                    {analysis.data.sections.map((s, i) => (
                      <li key={i}>
                        <strong>{s.title}</strong>
                        <span className="muted">
                          trang {s.page_start}–{s.page_end}
                        </span>
                      </li>
                    ))}
                  </ul>
                </>
              )}
            </>
          )}
        </section>
      )}

      {step === 'review' && (
        <section className="card">
          <h2>Xem lại trước khi bắt đầu</h2>

          {coverage.data && <CoverageSummary report={coverage.data} />}

          <h3>Phần nào bạn đã biết?</h3>
          <p className="muted">
            Đánh dấu những phần bạn đã nắm để hệ thống dành thời gian cho phần còn lại.
          </p>
          {units.isPending && <Loading />}
          <ul className="unit-list">
            {units.data?.map((unit) => (
              <li key={unit.id}>
                <label>
                  <input
                    type="checkbox"
                    checked={unit.marked_known}
                    onChange={(e) => markKnown.mutate({ unitId: unit.id, known: e.target.checked })}
                  />
                  <span>
                    <strong>{unit.title}</strong>
                    <span className="muted">
                      trang {unit.page_start}–{unit.page_end} · {unit.effective_effort_minutes} phút
                    </span>
                  </span>
                  <span className={`chip relevance-${unit.resolved_relevance.toLowerCase()}`}>
                    {RELEVANCE_LABEL[unit.resolved_relevance] ?? unit.resolved_relevance}
                  </span>
                </label>
              </li>
            ))}
          </ul>

          {infeasible && (
            <InfeasiblePanel infeasible={infeasible} onBack={() => setStep('schedule')} />
          )}

          <div className="card-actions">
            <button type="button" disabled={activate.isPending} onClick={() => activate.mutate()}>
              {activate.isPending ? 'Đang xếp lịch…' : 'Tạo lịch học của tôi'}
            </button>
          </div>
          {activate.error && !infeasible && <InlineError error={activate.error} />}
        </section>
      )}
    </div>
  )
}

function MinutesField({
  id,
  label,
  value,
  onChange,
}: {
  id: string
  label: string
  value: number
  onChange: (value: number) => void
}) {
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <div className="minutes-input">
        <input
          id={id}
          type="number"
          min={0}
          max={720}
          step={15}
          value={value}
          onChange={(e) => onChange(Math.max(0, Math.min(720, Number(e.target.value) || 0)))}
        />
        <span className="muted">phút/ngày · {(value / 60).toFixed(1)} giờ</span>
      </div>
    </div>
  )
}

/**
 * The pipeline, named.
 *
 * No percentage: nothing here knows how long the remaining stages take, so a bar
 * would be a number invented to look reassuring. Naming the stage that is
 * running is both more honest and more useful, because when it fails the learner
 * can see where.
 */
function PipelineStages({ stages }: { stages?: AnalysisStage[] }) {
  if (!stages?.length) return <Loading label="Đang bắt đầu…" />
  return (
    <ol className="pipeline">
      {stages.map((stage) => (
        <li key={stage.key} className={`pipeline-step ${stage.state.toLowerCase()}`}>
          <span className="pipeline-mark" aria-hidden="true">
            {STAGE_MARK[stage.state] ?? '•'}
          </span>
          <span className="pipeline-body">
            <strong>{STAGE_LABEL[stage.key]}</strong>
            <span className="muted">{stage.detail ?? STAGE_STATE_LABEL[stage.state]}</span>
          </span>
        </li>
      ))}
    </ol>
  )
}

const STAGE_LABEL: Record<AnalysisStage['key'], string> = {
  UPLOAD: 'Đã nhận tài liệu',
  EXTRACT: 'Đọc từng trang',
  STRUCTURE: 'Hiểu cấu trúc khoá học',
  MAPPING: 'Đối chiếu phạm vi bài thi',
  SCHEDULE: 'Dựng lịch học',
}

const STAGE_STATE_LABEL: Record<AnalysisStage['state'], string> = {
  DONE: 'Xong',
  IN_PROGRESS: 'Đang chạy…',
  WAITING: 'Chờ bước trước',
  SKIPPED: 'Chưa chạy',
  FAILED: 'Thất bại',
}

const STAGE_MARK: Record<AnalysisStage['state'], string> = {
  DONE: '✓',
  IN_PROGRESS: '◐',
  WAITING: '○',
  SKIPPED: '–',
  FAILED: '✕',
}

const RELEVANCE_LABEL: Record<string, string> = {
  CRITICAL: 'Bắt buộc',
  HIGH: 'Quan trọng',
  MEDIUM: 'Nên biết',
  LOW: 'Ít gặp',
  OPTIONAL: 'Ngoài phạm vi',
}

function CapacityCard({ result }: { result: CapacityResult }) {
  const tone =
    result.verdict === 'COMFORTABLE' ? 'good' : result.verdict === 'TIGHT' ? 'warn' : 'danger'
  return (
    <div className={`capacity-card ${tone}`}>
      <div className="capacity-headline">
        <div>
          <strong>{result.studyDays}</strong>
          <span>ngày học</span>
        </div>
        <div>
          <strong>{Math.round(result.totalCapacityMinutes / 60)}</strong>
          <span>giờ khả dụng</span>
        </div>
        <div>
          <strong>{VERDICT_LABEL[result.verdict] ?? result.verdict}</strong>
          <span>mức độ căng</span>
        </div>
      </div>
      <ul>
        {result.advice.map((line, i) => (
          <li key={i}>{line}</li>
        ))}
      </ul>
    </div>
  )
}

const VERDICT_LABEL: Record<string, string> = {
  COMFORTABLE: 'Thoải mái',
  TIGHT: 'Khá căng',
  INSUFFICIENT: 'Không đủ',
  NO_CAPACITY: 'Không có giờ học',
  EXAM_DATE_PASSED: 'Ngày thi đã qua',
}

function CoverageSummary({ report }: { report: CoverageReport }) {
  if (!report.coverageEvaluated) {
    return (
      <div className="coverage">
        <SyntheticMappingNotice />
      </div>
    )
  }
  return (
    <div className="coverage">
      <p>
        Tài liệu của bạn phủ khoảng <strong>{report.weightedCoveragePercent}%</strong> phạm vi bài
        thi <span className="muted">(ước tính)</span>.
      </p>
      <div className="coverage-bars">
        {report.domains.map((d) => (
          <div key={d.code} className="coverage-row">
            <span className="coverage-label">
              {d.code} · {d.title} <span className="muted">{d.weightPercent}%</span>
            </span>
            <span className="bar">
              <span style={{ width: `${Math.round(d.coveredRatio * 100)}%` }} />
            </span>
            <span className="muted">{Math.round(d.coveredRatio * 100)}%</span>
          </div>
        ))}
      </div>
      {report.gaps.length > 0 && (
        <details>
          <summary>{report.gaps.length} mục chưa được tài liệu phủ đủ</summary>
          <ul>
            {report.gaps.slice(0, 12).map((gap) => (
              <li key={gap.taskStatementId}>
                <code>{gap.code}</code> {gap.title}{' '}
                <span className="muted">
                  {gap.status === 'NOT_COVERED' ? 'chưa có' : 'còn mỏng'}
                </span>
              </li>
            ))}
          </ul>
        </details>
      )}
    </div>
  )
}

function InfeasiblePanel({
  infeasible,
  onBack,
}: {
  infeasible: InfeasibleSchedule
  onBack: () => void
}) {
  return (
    <div className="banner danger">
      <strong>
        Không đủ thời gian cho toàn bộ nội dung (thiếu khoảng{' '}
        {Math.round(infeasible.deficitMinutes / 60)} giờ).
      </strong>
      <ul>
        {infeasible.suggestions.map((s) => (
          <li key={s.code}>{s.detail}</li>
        ))}
      </ul>
      <button type="button" className="ghost" onClick={onBack}>
        Chỉnh lại thời gian
      </button>
    </div>
  )
}

function QualityFlags({ flags }: { flags?: Record<string, unknown> }) {
  if (!flags) return null
  const imageDominant = Number(flags.imageDominantPages ?? 0)
  const notes = Number(flags.slidesWithSpeakerNotes ?? 0)
  const lowPrecision = Boolean(flags.lowPrecisionStructure)
  const noOriginalView = Boolean(flags.originalViewUnavailable)

  if (!imageDominant && !notes && !lowPrecision && !noOriginalView) return null
  return (
    <Banner tone="warn">
      {imageDominant > 0 && (
        <p>
          {imageDominant} trang chủ yếu là hình hoặc sơ đồ. Bài giảng cho những phần đó sẽ mỏng hơn
          và sẽ nhắc bạn mở slide gốc.
        </p>
      )}
      {notes > 0 && <p>{notes} slide có ghi chú giảng viên — hệ thống sẽ dùng chúng.</p>}
      {lowPrecision && (
        <p>Không nhận diện được cấu trúc rõ ràng; các phần được chia theo khoảng trang.</p>
      )}
      {noOriginalView && (
        <p>
          Định dạng này chưa mở được slide gốc trong ứng dụng. Bài giảng vẫn trích dẫn số trang,
          nhưng bạn sẽ phải mở tệp bằng phần mềm của mình. Tải bản PDF nếu bạn có.
        </p>
      )}
    </Banner>
  )
}

function simpleCapacity(weekday: number, weekend: number): Record<string, number> {
  const out: Record<string, number> = {}
  WEEKDAYS.forEach((day) => (out[day] = weekday))
  WEEKEND.forEach((day) => (out[day] = weekend))
  return out
}

function stepState(current: Step, step: Step): string {
  const a = STEPS.indexOf(current)
  const b = STEPS.indexOf(step)
  if (a === b) return 'active'
  return b < a ? 'done' : ''
}

function defaultExamDate(): string {
  const date = new Date()
  date.setDate(date.getDate() + 42)
  return date.toISOString().slice(0, 10)
}
