import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { AttemptView, ExamResult, QuestionView, QuizResponse, TodayView } from '../api/types'
import { Banner, ErrorState, InlineError, Loading } from '../components/states'

/**
 * Quiz and result.
 *
 * One question at a time while answering, everything at once while reviewing.
 * The two modes want opposite layouts: answering is a decision that deserves the
 * whole screen, and reviewing is a comparison that only works side by side.
 *
 * Answers are saved per question rather than in one submit, so a dropped
 * connection costs one answer instead of the whole attempt. Grading happens
 * server side and deterministically; this page only displays it.
 *
 * The "AI-generated practice question" notice is permanent and cannot be
 * dismissed. These are not real exam questions and the product never lets that
 * be ambiguous.
 */
export function QuizPage() {
  const { attemptId } = useParams<{ attemptId: string }>()
  const navigate = useNavigate()
  const [selections, setSelections] = useState<Record<string, string[]>>({})
  const [submitted, setSubmitted] = useState(false)
  const [index, setIndex] = useState(0)
  const [showUnanswered, setShowUnanswered] = useState(false)

  const planQuery = useQuery({
    queryKey: ['today'],
    queryFn: () => api.get<TodayView>('/api/v1/today'),
  })
  const planId = planQuery.data?.planId

  const attempt = useQuery({
    queryKey: ['attempt', planId, attemptId, submitted],
    enabled: Boolean(planId && attemptId),
    queryFn: () =>
      api.get<QuizResponse>(
        `/api/v1/plans/${planId}/quizzes/${attemptId}?withAnswers=${submitted}`,
      ),
  })

  useEffect(() => {
    const graded = attempt.data?.attempt.status === 'GRADED'
    if (graded && !submitted) setSubmitted(true)
  }, [attempt.data, submitted])

  const answer = useMutation({
    mutationFn: ({ questionId, optionIds }: { questionId: string; optionIds: string[] }) =>
      api.post(`/api/v1/plans/${planId}/quizzes/${attemptId}/answers`, {
        questionId,
        selectedOptionIds: optionIds,
      }),
  })

  const submit = useMutation({
    mutationFn: () => api.post<QuizResponse>(`/api/v1/plans/${planId}/quizzes/${attemptId}/submit`),
    onSuccess: () => setSubmitted(true),
  })

  const examResult = useQuery({
    queryKey: ['exam-result', planId, attemptId],
    enabled: submitted && attempt.data?.attempt.kind === 'FINAL_REVIEW',
    queryFn: () => api.get<ExamResult>(`/api/v1/plans/${planId}/final-exam/${attemptId}/result`),
  })

  const view: AttemptView | undefined = attempt.data?.attempt

  const chosenFor = (question: QuestionView) =>
    selections[question.id] ?? question.selectedOptionIds

  const unanswered = useMemo(
    () =>
      view
        ? view.questions
            .map((question, i) => ({ question, i }))
            .filter(({ question }) => !isComplete(question, chosenFor(question)))
        : [],
    // `selections` is the whole point of recomputing this.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [view, selections],
  )

  if (planQuery.isPending || attempt.isPending) return <Loading />
  if (attempt.error) return <ErrorState error={attempt.error} onRetry={() => attempt.refetch()} />
  if (!view) return <ErrorState error={new Error('Không tìm thấy bài quiz')} />

  const graded = view.status === 'GRADED'
  const total = view.questions.length

  const toggle = (question: QuestionView, optionId: string) => {
    const multi = question.type === 'MULTIPLE_RESPONSE'
    const current = chosenFor(question)
    const next = multi
      ? current.includes(optionId)
        ? current.filter((id) => id !== optionId)
        : [...current, optionId]
      : [optionId]
    setSelections({ ...selections, [question.id]: next })
    answer.mutate({ questionId: question.id, optionIds: next })
  }

  // ------------------------------------------------------------- review mode

  if (graded) {
    return (
      <div className="page quiz">
        <header className="quiz-head">
          <div>
            <p className="eyebrow">{KIND_LABEL[view.kind] ?? view.kind}</p>
            <h1>Kết quả</h1>
          </div>
          <div className="score">
            <strong>
              {view.scoreRaw}/{view.scoreTotal}
            </strong>
            <span>
              {Math.round(((view.scoreRaw ?? 0) / Math.max(1, view.scoreTotal ?? 1)) * 100)}% đúng
            </span>
          </div>
        </header>

        <p className="ai-notice">{attempt.data?.notice}</p>

        {examResult.data && <ExamBreakdown result={examResult.data} />}

        <ol className="questions">
          {view.questions.map((question, i) => (
            <li
              key={question.id}
              className={`question ${question.isCorrect ? 'right' : 'wrong'}`}
            >
              <div className="question-head">
                <span className="qno">{i + 1}</span>
                <div>
                  <p className="stem">{question.stem}</p>
                  <p className="muted">
                    {question.domainTitle} · {question.taskCode} {question.taskTitle}
                  </p>
                </div>
                <span className={`chip ${question.isCorrect ? 'good' : 'danger'}`}>
                  {question.isCorrect ? 'Đúng' : 'Sai'}
                </span>
              </div>

              <ul className="options">
                {question.options.map((option) => {
                  const isChosen = question.selectedOptionIds.includes(option.id)
                  const isCorrect = question.correctOptionIds?.includes(option.id)
                  return (
                    <li key={option.id}>
                      <div
                        className={[
                          'option-review',
                          isCorrect ? 'correct' : '',
                          isChosen && !isCorrect ? 'incorrect' : '',
                        ].join(' ')}
                      >
                        <span className="option-id">{option.id}</span>
                        <span className="option-text">{option.text}</span>
                        <span className="option-mark">
                          {isCorrect ? 'Đáp án đúng' : isChosen ? 'Bạn chọn' : ''}
                        </span>
                      </div>
                    </li>
                  )
                })}
              </ul>

              <div className="explanation">
                <p>
                  <strong>Vì sao đúng: </strong>
                  {question.explanation}
                </p>
                {question.distractorRationales &&
                  Object.entries(question.distractorRationales).map(([id, reason]) => (
                    <p key={id} className="muted">
                      <strong>{id} sai: </strong>
                      {reason}
                    </p>
                  ))}
                {question.sourceSpan && (
                  <p className="source-span">
                    Căn cứ (trang {question.sourcePageStart}
                    {question.sourcePageEnd && question.sourcePageEnd !== question.sourcePageStart
                      ? `–${question.sourcePageEnd}`
                      : ''}
                    ): “{question.sourceSpan}”
                  </p>
                )}
              </div>
            </li>
          ))}
        </ol>

        <footer className="quiz-actions">
          <button type="button" onClick={() => navigate('/today')}>
            Quay lại Hôm nay
          </button>
        </footer>
      </div>
    )
  }

  // ----------------------------------------------------------- answer mode

  const question = view.questions[Math.min(index, total - 1)]
  const multi = question.type === 'MULTIPLE_RESPONSE'
  const chosen = chosenFor(question)
  const answeredCount = total - unanswered.length

  return (
    <div className="page quiz answering">
      <header className="quiz-head">
        <div>
          <p className="eyebrow">{KIND_LABEL[view.kind] ?? view.kind}</p>
          <h1>
            Câu {index + 1} / {total}
          </h1>
        </div>
        <div className="score">
          <strong>{answeredCount}</strong>
          <span>đã trả lời</span>
        </div>
      </header>

      <div className="quiz-progress" aria-label={`Đã trả lời ${answeredCount} trên ${total}`}>
        {view.questions.map((q, i) => (
          <button
            key={q.id}
            type="button"
            className={[
              'quiz-pip',
              i === index ? 'on' : '',
              isComplete(q, chosenFor(q)) ? 'answered' : '',
            ].join(' ')}
            aria-label={`Câu ${i + 1}`}
            onClick={() => setIndex(i)}
          />
        ))}
      </div>

      <p className="ai-notice">{attempt.data?.notice}</p>

      <section className="question single">
        <p className="stem">{question.stem}</p>
        <p className="muted question-context">
          {question.domainTitle} · {question.taskCode} {question.taskTitle}
        </p>
        <p className={`answer-rule ${multi ? 'multi' : ''}`}>
          {multi ? 'Chọn đúng 2 đáp án' : 'Chọn 1 đáp án'}
        </p>

        <ul className="options">
          {question.options.map((option) => (
            <li key={option.id}>
              <label className={chosen.includes(option.id) ? 'chosen' : ''}>
                <input
                  type={multi ? 'checkbox' : 'radio'}
                  name={question.id}
                  checked={chosen.includes(option.id)}
                  onChange={() => toggle(question, option.id)}
                />
                <span className="option-id">{option.id}</span>
                <span className="option-text">{option.text}</span>
              </label>
            </li>
          ))}
        </ul>

        {multi && chosen.length > 2 && (
          <Banner tone="warn">Câu này cần đúng 2 đáp án. Bạn đang chọn {chosen.length}.</Banner>
        )}
      </section>

      <footer className="quiz-actions">
        <button
          type="button"
          className="ghost"
          disabled={index === 0}
          onClick={() => setIndex(index - 1)}
        >
          ← Câu trước
        </button>

        {index < total - 1 ? (
          <button type="button" onClick={() => setIndex(index + 1)}>
            Câu tiếp →
          </button>
        ) : (
          <button
            type="button"
            disabled={submit.isPending || unanswered.length > 0}
            onClick={() => submit.mutate()}
          >
            {submit.isPending ? 'Đang chấm…' : 'Nộp bài'}
          </button>
        )}
      </footer>

      {unanswered.length > 0 && index === total - 1 && (
        <Banner tone="info">
          <strong>Còn {unanswered.length} câu chưa trả lời đủ.</strong>
          <p>
            {showUnanswered ? (
              <>
                Câu{' '}
                {unanswered.map(({ i }, k) => (
                  <button
                    key={i}
                    type="button"
                    className="linky"
                    onClick={() => setIndex(i)}
                  >
                    {i + 1}
                    {k < unanswered.length - 1 ? ', ' : ''}
                  </button>
                ))}
              </>
            ) : (
              <button type="button" className="linky" onClick={() => setShowUnanswered(true)}>
                Xem những câu còn thiếu
              </button>
            )}
          </p>
        </Banner>
      )}

      {submit.error && <InlineError error={submit.error} />}
    </div>
  )
}

/**
 * Whether this question has a submittable answer.
 *
 * A multiple-response question with one box ticked is not a partial answer, it
 * is an answer that will be marked wrong; letting it through and grading it as a
 * miss teaches the learner nothing about the topic.
 */
function isComplete(question: QuestionView, chosen: string[]): boolean {
  return question.type === 'MULTIPLE_RESPONSE' ? chosen.length === 2 : chosen.length === 1
}

function ExamBreakdown({ result }: { result: ExamResult }) {
  return (
    <section className="exam-result">
      <h2>Kết quả theo domain</h2>
      <div className="coverage-bars">
        {result.byDomain.map((d) => {
          const percent = d.total === 0 ? 0 : Math.round((d.correct / d.total) * 100)
          return (
            <div key={d.code} className="coverage-row">
              <span className="coverage-label">
                {d.code} · {d.title} <span className="muted">{d.weightPercent}%</span>
              </span>
              <span className="bar">
                <span style={{ width: `${percent}%` }} />
              </span>
              <span className="muted">
                {d.correct}/{d.total}
              </span>
            </div>
          )
        })}
      </div>
      {result.focusAreas.length > 0 && (
        <p>
          <strong>Nên tập trung: </strong>
          {result.focusAreas.join(', ')}
        </p>
      )}
    </section>
  )
}

const KIND_LABEL: Record<string, string> = {
  DAILY: 'Quiz hằng ngày',
  WARMUP: 'Ôn nhanh đầu buổi',
  FINAL_REVIEW: 'Bài ôn tổng hợp',
}
