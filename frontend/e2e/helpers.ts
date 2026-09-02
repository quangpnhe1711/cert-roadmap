import { expect, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

/**
 * Steps every browser test repeats.
 *
 * Kept here rather than duplicated per spec so that a wording change in the UI
 * breaks one place. These deliberately drive the product the way a learner does
 * - clicking what is on screen - rather than reaching into the API, because a
 * test that skips the UI cannot tell you the UI works.
 */

// The project is ESM, so __dirname has to be derived.
const here = dirname(fileURLToPath(import.meta.url))

/**
 * How much longer to wait when a real provider is answering.
 *
 * A live quality-tier call takes tens of seconds and can pause on a rate limit;
 * the deterministic adapter answers in microseconds. One set of timeouts cannot
 * serve both without either making the offline suite slow to fail or making the
 * live suite flaky, so the multiplier is explicit rather than a compromise
 * number that is wrong for both.
 */
const LIVE = process.env.GEMINI_LIVE === '1'
const SLOW = LIVE ? 8 : 1

/** Waits scaled to whichever adapter is answering. */
export function waitFor(baseMs: number): number {
  return baseMs * SLOW
}

/** The same file the quick start tells a developer to upload. */
export const DECK = resolve(here, '../../local-data/sample-aif-c01.pdf')

export async function register(page: Page, prefix: string): Promise<string> {
  const email = `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1000)}@example.com`
  await page.goto('/auth')
  await page.getByRole('button', { name: /Chưa có tài khoản/ }).click()
  await page.getByLabel('Tên hiển thị').fill('E2E Learner')
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Mật khẩu').fill('correct-horse-battery')
  await page.getByRole('button', { name: 'Đăng ký', exact: true }).click()
  // Wait for the redirect, not just the click: the token lands in localStorage
  // as part of signing in, and anything reading the API before then gets a 401
  // that looks like a product bug.
  await page.waitForURL((url) => !url.pathname.startsWith('/auth'), { timeout: 30_000 })
  return email
}

/** Walks the wizard from the certification step to an activated plan. */
export async function createPlanThroughWizard(page: Page, daysUntilExam: number) {
  await expect(page.getByRole('heading', { name: 'Bạn đang ôn chứng chỉ nào?' })).toBeVisible()
  await expect(page.getByText('AWS Certified AI Practitioner')).toBeVisible()
  await page.getByRole('button', { name: 'Tiếp tục' }).click()

  await expect(
    page.getByRole('heading', { name: 'Bao giờ bạn thi, và học được bao nhiêu?' }),
  ).toBeVisible()
  const examDate = new Date()
  examDate.setDate(examDate.getDate() + daysUntilExam)
  await page.getByLabel('Ngày thi').fill(examDate.toISOString().slice(0, 10))

  // The reality check is deterministic and arrives before any upload.
  await expect(page.locator('.capacity-card')).toBeVisible()
  await expect(page.locator('.capacity-card')).toContainText('ngày học')

  await page.getByRole('button', { name: 'Tiếp tục' }).click()

  await expect(page.getByRole('heading', { name: 'Tải tài liệu bạn đang học' })).toBeVisible()
  await uploadDeck(page)

  // Named stages, not a spinner with an invented percentage.
  await expect(page.getByRole('heading', { name: 'Đang dựng kế hoạch của bạn' })).toBeVisible()
  await expect(page.locator('.pipeline-step').first()).toBeVisible()

  await expect(page.getByRole('heading', { name: 'Xem lại trước khi bắt đầu' })).toBeVisible({
    timeout: waitFor(120_000),
  })
  await expect(page.locator('.unit-list li').first()).toBeVisible()

  await page.getByRole('button', { name: 'Tạo lịch học của tôi' }).click()
  await expect(page.getByRole('heading', { name: 'Hôm nay', exact: true })).toBeVisible({
    timeout: waitFor(90_000),
  })
}

export async function uploadDeck(page: Page) {
  await page.setInputFiles('input[type="file"]', {
    name: 'aif-c01-course.pdf',
    mimeType: 'application/pdf',
    buffer: readFileSync(DECK),
  })
}

/**
 * Answers the quiz one question at a time, the way the screen is built.
 *
 * Picks a valid number of options per question - one, or exactly two for a
 * multiple-response - because the submit button stays disabled until every
 * question has a submittable answer, which is itself part of what this checks.
 */
export async function answerWholeQuiz(page: Page) {
  await expect(page.getByRole('heading', { name: /^Câu 1 \// })).toBeVisible({
    timeout: waitFor(90_000),
  })
  const total = await page.locator('.quiz-pip').count()
  expect(total).toBeGreaterThan(0)

  for (let i = 0; i < total; i++) {
    const multi = await page.locator('.answer-rule.multi').isVisible().catch(() => false)
    const options = page.locator('.options label')
    await options.nth(0).click()
    if (multi) {
      await options.nth(1).click()
    }
    if (i < total - 1) {
      await page.getByRole('button', { name: /Câu tiếp/ }).click()
    }
  }

  const submit = page.getByRole('button', { name: 'Nộp bài' })
  await expect(submit).toBeEnabled()
  await submit.click()
  await expect(page.getByRole('heading', { name: 'Kết quả', exact: true })).toBeVisible({
    timeout: waitFor(90_000),
  })
}

/**
 * Clicks through to the end of the plan. Each completion runs a real replan, so
 * this also exercises the path where the schedule is rebuilt repeatedly.
 */
export async function finishEveryRemainingDay(page: Page) {
  for (let day = 0; day < 30; day++) {
    const finished = page.getByText('Bạn đã hoàn thành toàn bộ')
    if (await finished.isVisible().catch(() => false)) {
      return
    }
    const completeDay = page.getByRole('button', { name: 'Hoàn thành ngày' })
    if (!(await completeDay.isVisible().catch(() => false))) {
      return
    }
    await completeDay.click()
    await expect(page.getByText('Đã hoàn thành ngày học.').or(finished)).toBeVisible({
      timeout: 90_000,
    })

    const dismiss = page.getByRole('button', { name: 'Đã hiểu' })
    if (await dismiss.isVisible().catch(() => false)) {
      await dismiss.click()
    }
  }
  throw new Error('the plan never finished after 30 day completions')
}

/** What the server says this deployment's AI is. Never guessed from the bundle. */
export async function aiProvider(page: Page): Promise<{
  aiProvider: string
  aiSynthetic: boolean
  fastModel: string
  qualityModel: string
}> {
  return page.evaluate(() =>
    fetch('/api/v1/meta', {
      headers: { Authorization: `Bearer ${localStorage.getItem('certcopilot.accessToken')}` },
    }).then((r) => r.json()),
  )
}
