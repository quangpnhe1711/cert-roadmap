import { useQuery } from '@tanstack/react-query'
import { Navigate, Route, Routes } from 'react-router-dom'
import { api } from './api/client'
import type { PlanSummary } from './api/types'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { Layout } from './components/Layout'
import { ErrorState, Loading } from './components/states'
import { AuthPage } from './pages/AuthPage'
import { MaterialsPage } from './pages/MaterialsPage'
import { OnboardingPage } from './pages/OnboardingPage'
import { PlanPage } from './pages/PlanPage'
import { PlansPage } from './pages/PlansPage'
import { QuizPage } from './pages/QuizPage'
import { SettingsPage } from './pages/SettingsPage'
import { TodayPage } from './pages/TodayPage'
import type { ReactNode } from 'react'

export default function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/auth" element={<AuthPage />} />
        <Route
          path="/onboarding"
          element={
            <RequireAuth>
              <OnboardingPage />
            </RequireAuth>
          }
        />
        <Route
          element={
            <RequireAuth>
              <Layout />
            </RequireAuth>
          }
        >
          <Route path="/today" element={<TodayPage />} />
          <Route path="/quiz/:attemptId" element={<QuizPage />} />
          <Route path="/plans" element={<PlansPage />} />
          <Route path="/plan" element={<PlanPage />} />
          <Route path="/materials" element={<MaterialsPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Route>
        <Route
          path="/"
          element={
            <RequireAuth>
              <LandingRoute />
            </RequireAuth>
          }
        />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </AuthProvider>
  )
}

function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) return <Loading />
  if (!user) return <Navigate to="/auth" replace />
  return <>{children}</>
}

/**
 * Where someone lands after signing in.
 *
 * Decided from what they actually have, not from a fixed home route: a learner
 * mid-plan wants today's work, someone between plans wants their list, and
 * someone brand new wants the wizard. Sending all three to the same screen means
 * two of them arrive at an empty state and have to work out where to go.
 */
function LandingRoute() {
  const plans = useQuery({
    queryKey: ['plans'],
    queryFn: () => api.get<PlanSummary[]>('/api/v1/plans'),
  })

  if (plans.isPending) return <Loading label="Đang mở kế hoạch của bạn…" />
  if (plans.error) return <ErrorState error={plans.error} onRetry={() => plans.refetch()} />

  const all = plans.data ?? []
  if (all.length === 0) return <Navigate to="/onboarding" replace />

  const inFlight = all.find((plan) =>
    ['DRAFT', 'ANALYZING', 'READY_FOR_REVIEW', 'ACTIVE'].includes(plan.status),
  )
  if (inFlight?.status === 'ACTIVE') return <Navigate to="/today" replace />
  if (inFlight) return <Navigate to="/onboarding" replace />

  // Only finished or archived plans left: the list is where the next decision is.
  return <Navigate to="/plans" replace />
}
