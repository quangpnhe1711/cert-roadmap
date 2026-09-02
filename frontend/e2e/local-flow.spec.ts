import { test, expect } from '@playwright/test'
import {
  answerWholeQuiz,
  createPlanThroughWizard,
  finishEveryRemainingDay,
  register,
} from './helpers'

/**
 * The whole local product, the way a developer actually uses it after running
 * `scripts/dev-up`.
 *
 * `core-loop.spec.ts` proves the loop works; this proves the *local setup*
 * works, and carries on past the first day to the three things that only exist
 * at the end of a plan: replanning across every remaining day, the Final Review
 * Exam assembled from the question bank, and starting the next plan without
 * losing the one just finished.
 *
 * The exam date is deliberately close so the plan is a handful of days and the
 * test can finish them all through the UI rather than reaching into the API.
 */
test('the local stack supports the whole flow, from first plan to second', async ({ page }) => {
  await register(page, 'local')

  // The build must announce that its AI output is synthetic, on every screen.
  await expect(page.locator('.dev-banner')).toContainText('Synthetic AI')

  await createPlanThroughWizard(page, 9)

  // ---- the lesson and the original slide ---------------------------------
  const firstUnit = page.locator('.unit').first()
  await firstUnit.getByRole('button', { name: 'Mở bài giảng' }).click()
  await expect(page.locator('.pack .block').first()).toBeVisible({ timeout: 90_000 })

  await page.locator('.block .source-link').first().click()
  await expect(page.locator('.slide-panel')).toContainText('Tài liệu gốc')
  await page.locator('.slide-panel').getByRole('button', { name: 'Đóng' }).click()

  // ---- complete a unit ----------------------------------------------------
  await firstUnit.getByRole('button', { name: 'Xong' }).click()
  await expect(firstUnit).toHaveClass(/done/)

  // ---- quiz, submit, result ----------------------------------------------
  await page.getByRole('button', { name: 'Làm quiz hôm nay' }).click()
  await answerWholeQuiz(page)
  await expect(page.locator('.score strong')).toBeVisible()
  await page.getByRole('button', { name: 'Quay lại Hôm nay' }).click()

  // ---- a replan on every day boundary ------------------------------------
  await finishEveryRemainingDay(page)

  await page.getByRole('link', { name: 'Lịch & tiến độ' }).click()
  await page.getByRole('button', { name: 'Tiến độ' }).click()
  await expect(page.getByText('Buổi đã hoàn thành')).toBeVisible()
  await expect(page.getByText('Theo domain của kỳ thi')).toBeVisible()

  // ---- the finished plan is still usable ---------------------------------
  await page.getByRole('link', { name: 'Hôm nay' }).click()
  await expect(page.getByText('Bạn đã hoàn thành toàn bộ')).toBeVisible({ timeout: 90_000 })

  await page.getByRole('button', { name: 'Làm bài ôn tổng hợp' }).click()
  await answerWholeQuiz(page)

  // D5's promise: the exam reports where the learner stands per exam domain.
  await expect(page.getByRole('heading', { name: 'Kết quả theo domain' })).toBeVisible()
  await expect(page.locator('.score strong')).toBeVisible()

  // ---- and the next plan starts beside it, not on top of it --------------
  await page.getByRole('button', { name: 'Quay lại Hôm nay' }).click()
  await page.getByRole('link', { name: 'Kế hoạch' }).click()
  await expect(page.getByRole('heading', { name: 'Kế hoạch của bạn' })).toBeVisible()

  const firstCard = page.locator('.plan-card').first()
  await expect(firstCard).toContainText('AIF-C01')

  // One plan runs at a time, so the finished one has to be put away before the
  // next begins - and putting it away must not make it disappear.
  await firstCard.getByRole('button', { name: 'Lưu trữ' }).click()
  await expect(firstCard).toContainText('Đã lưu trữ')

  await page.getByRole('button', { name: '+ Kế hoạch mới' }).click()
  await createPlanThroughWizard(page, 12)

  await page.getByRole('link', { name: 'Kế hoạch' }).click()
  const cards = page.locator('.plan-card')
  await expect(cards).toHaveCount(2)

  // Exactly one live plan, and the history is still there.
  await expect(page.locator('.plan-card', { hasText: 'Đang học' })).toHaveCount(1)
  await expect(page.locator('.plan-card', { hasText: 'Đã lưu trữ' })).toHaveCount(1)
})
