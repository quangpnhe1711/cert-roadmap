/** Shapes returned by the backend. Mirrors the server records one to one. */

/**
 * What this deployment will admit about its own AI.
 *
 * Read from the server, never guessed from the bundle's own environment: the
 * frontend has no way to know which adapter the backend process was started
 * with, and guessing wrong means telling a learner that fixture prose is a
 * lesson.
 */
export type Meta = {
  /** MODEL | SYNTHETIC */
  aiMode: string
  aiModeLabel: string
  /** FAKE | GEMINI | ANTHROPIC */
  aiProvider: string
  aiProviderLabel: string
  aiConfigured: boolean
  aiSynthetic: boolean
  fastModel: string
  qualityModel: string
}

export type Tokens = {
  accessToken: string
  refreshToken: string
  expiresInSeconds: number
  userId: string
}

export type User = {
  id: string
  email: string
  displayName: string
  locale: string
}

export type Certification = {
  certificationVersionId: string
  provider: string
  name: string
  examCode: string
  versionLabel: string
  durationMinutes?: number
  questionCount?: number
  passingScore?: number
}

export type TaskStatement = { id: string; code: string; title: string }
export type ExamDomainView = {
  id: string
  code: string
  title: string
  weightPercent: number
  taskStatements: TaskStatement[]
}
export type SourceRef = {
  field?: string
  url: string
  docTitle?: string
  method: string
  verifiedBy?: string
}
export type Blueprint = {
  certificationVersionId: string
  provider: string
  name: string
  examCode: string
  versionLabel: string
  officialUrl: string
  examGuideUrl?: string
  durationMinutes?: number
  questionCount?: number
  passingScore?: number
  domains: ExamDomainView[]
  sources: SourceRef[]
}

export type CapacityResult = {
  calendarDays: number
  studyDays: number
  blockedDays: number
  totalCapacityMinutes: number
  averageMinutesPerStudyDay: number
  verdict: 'COMFORTABLE' | 'TIGHT' | 'INSUFFICIENT' | 'NO_CAPACITY' | 'EXAM_DATE_PASSED'
  advice: string[]
}

export type Plan = {
  id: string
  status: string
  examDate: string
  startDate: string
  certificationVersionId: string
  certification: { exam_code: string; version_label: string; name: string; provider: string }
  weeklyCapacity: Record<string, number>
  blockedDates: string[]
  compressionMode: string
  hasMaterial: boolean
}

/** One pipeline stage. `detail` is a measured fact or absent - never a percentage. */
export type AnalysisStage = {
  key: 'UPLOAD' | 'EXTRACT' | 'STRUCTURE' | 'MAPPING' | 'SCHEDULE'
  state: 'DONE' | 'IN_PROGRESS' | 'WAITING' | 'SKIPPED' | 'FAILED'
  detail?: string
}

export type AnalysisState = {
  planStatus: string
  materialStatus: string
  qualityFlags?: Record<string, unknown>
  failureReason?: string
  sections: { title: string; page_start: number; page_end: number }[]
  unitCount: number
  stages: AnalysisStage[]
}

/** A row of the plan list. Flat, because it feeds a list screen. */
export type PlanSummary = {
  id: string
  status: string
  examDate: string
  startDate: string
  examCode: string
  versionLabel: string
  certificationName: string
  provider: string
  totalDays: number
  completedDays: number
  progressPercent: number
  daysUntilExam: number
  hasMaterial: boolean
}

/**
 * What a plan's AI generation actually used, measured from the cost ledger.
 *
 * `provenance` says where this plan's content came from - MODEL, SYNTHETIC or
 * NONE - and is read from what generated it, not from what is configured now.
 * `pricingConfigured` is false when `costCents` is a tier estimate rather than a
 * published price, so an estimate is never read as a measurement.
 */
export type AiUsage = {
  provenance: 'MODEL' | 'SYNTHETIC' | 'NONE'
  models: string[]
  tokensIn: number
  tokensOut: number
  providerCalls: number
  cacheHits: number
  rejectedAttempts: number
  costCents: number
  pricingConfigured: boolean
}

/** The AI note attached to a weak topic. Never carries a score - that is arithmetic. */
export type WeaknessExplanation = {
  courseTopicId: string
  topicTitle: string
  summary: string
  keyReminders: string[]
}

export type PlanUnit = {
  id: string
  title: string
  page_start: number
  page_end: number
  effective_effort_minutes: number
  resolved_relevance: string
  marked_known: boolean
  status: string
}

export type CoverageReport = {
  weightedCoveragePercent: number
  /** MODEL | SYNTHETIC | NONE */
  contentProvenance: string
  /** False when the mapping came from the fake adapter; show no percentage at all. */
  coverageEvaluated: boolean
  domains: {
    code: string
    title: string
    weightPercent: number
    taskCount: number
    coveredCount: number
    partialCount: number
    notCoveredCount: number
    coveredRatio: number
  }[]
  gaps: { taskStatementId: string; code: string; title: string; domainCode: string; status: string }[]
}

export type TodayItem = {
  itemId: string
  type: 'LEARNING_UNIT' | 'REVIEW_BLOCK' | 'FINAL_REVIEW_EXAM'
  learningUnitId?: string
  courseTopicId?: string
  title: string
  pageStart?: number
  pageEnd?: number
  materialId?: string
  allottedMinutes: number
  status: string
  packReady: boolean
  hasSignificantVisual: boolean
  /** CRITICAL | HIGH | MEDIUM | LOW | OPTIONAL, resolved for this plan. */
  relevance: string
}

export type FinalExamReadiness = {
  bankSize: number
  minimumRequired: number
  ready: boolean
}

export type TodayView = {
  hasPlan: boolean
  planId?: string
  planStatus?: string
  message?: string
  studyDayId?: string
  date?: string
  dayIndex: number
  totalDays: number
  completedDays: number
  capacityMinutes: number
  kind?: string
  items: TodayItem[]
  warmupAvailable: number
  quizAvailable: boolean
  daysUntilExam: number
  finalExam?: FinalExamReadiness
}

export type ContentBlock = {
  id: string
  orderIndex: number
  type: string
  payload: Record<string, unknown>
  origin: 'FROM_MATERIAL' | 'AI_SUPPLEMENT'
  examRelevance?: 'MUST_KNOW' | 'SHOULD_KNOW' | 'GOOD_TO_KNOW' | 'NOT_REQUIRED'
  sourcePageStart?: number
  sourcePageEnd?: number
  sourceSpan?: string
}

export type PackView = {
  packId: string
  artifactKey: string
  blocks: ContentBlock[]
  unit: {
    learningUnitId: string
    title: string
    pageStart?: number
    pageEnd?: number
    materialId?: string
  }
}

export type QuestionOption = { id: string; text: string }

export type QuestionView = {
  id: string
  type: string
  stem: string
  options: QuestionOption[]
  correctOptionIds?: string[]
  explanation?: string
  distractorRationales?: Record<string, string>
  sourceSpan?: string
  sourcePageStart?: number
  sourcePageEnd?: number
  difficulty: number
  selectedOptionIds: string[]
  isCorrect?: boolean
  taskCode: string
  taskTitle: string
  domainTitle: string
}

export type AttemptView = {
  id: string
  kind: string
  status: string
  scoreRaw?: number
  scoreTotal?: number
  studyDayId?: string
  questions: QuestionView[]
}

export type QuizResponse = { attempt: AttemptView; notice: string }

export type WeakTopic = {
  courseTopicId: string
  title: string
  accuracy: number
  answeredCount: number
  domainWeightPercent: number
  weaknessScore: number
}

export type DomainProgress = {
  code: string
  title: string
  weightPercent: number
  correct: number
  total: number
  masteredTopics: number
  assessedTopics: number
  accuracyPercent: number
}

export type ProgressView = {
  planId: string
  totalDays: number
  completedDays: number
  skippedDays: number
  daysUntilExam: number
  domains: DomainProgress[]
  weakTopics: WeakTopic[]
  adjustments: { trigger: string; reason_code: string; params: unknown; created_at: string }[]
  coverage?: CoverageReport
  finalExam?: FinalExamReadiness
}

export type PlanDay = {
  id: string
  date: string
  dayIndex: number
  capacityMinutes: number
  allocatedMinutes: number
  kind: string
  status: string
  itemTitles: string[]
}

export type CompletionResult = {
  completedDayId: string
  nextDayId?: string
  nextDayDate?: string
  weakTopics: WeakTopic[]
  recentAdjustments: { reason_code: string; params: unknown; created_at: string }[]
  scheduleInfeasible: boolean
  infeasible?: InfeasibleSchedule
}

export type Suggestion = { code: string; detail: string; parameter: number }

export type InfeasibleSchedule = {
  code?: string
  deficitMinutes: number
  availableMinutes?: number
  requiredMinutes?: number
  suggestions: Suggestion[]
}

export type MaterialView = {
  id: string
  fileName: string
  mime: string
  sizeBytes: number
  pageCount?: number
  uploadedAt: string
  plansUsing: number
  revisions: {
    id: string
    revision_no: number
    status: string
    quality_flags: Record<string, unknown>
    failure_reason?: string
    created_at: string
  }[]
}

export type ExamResult = {
  attemptId: string
  correct: number
  total: number
  status: string
  byDomain: { code: string; title: string; weightPercent: number; correct: number; total: number }[]
  focusAreas: string[]
  percent: number
}
