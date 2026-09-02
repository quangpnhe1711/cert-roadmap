import { useQuery } from '@tanstack/react-query'
import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { PlanSummary } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { AiStatusChip, SyntheticBanner, useMeta } from './AiStatus'

const NAV = [
  { to: '/today', label: 'Hôm nay' },
  { to: '/plans', label: 'Kế hoạch' },
  { to: '/plan', label: 'Lịch & tiến độ' },
  { to: '/materials', label: 'Tài liệu' },
  { to: '/settings', label: 'Cài đặt' },
]

/**
 * Shell and navigation.
 *
 * Five destinations, flat, no submenus. The current certification is shown next
 * to the brand rather than in the page body: it is context the learner needs to
 * be sure they are looking at the right plan, and nothing more than that.
 */
export function Layout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const meta = useMeta()

  const plans = useQuery({
    queryKey: ['plans'],
    queryFn: () => api.get<PlanSummary[]>('/api/v1/plans'),
    staleTime: 30_000,
  })
  const current = plans.data?.find((plan) =>
    ['ACTIVE', 'READY_FOR_REVIEW', 'ANALYZING', 'DRAFT', 'COMPLETED'].includes(plan.status),
  )

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <span className="mark" aria-hidden="true">
            CC
          </span>
          <span className="brand-name">Cert Study Copilot</span>
          {current && <span className="brand-context">{current.examCode}</span>}
        </div>

        <nav aria-label="Điều hướng chính">
          {NAV.map((item) => (
            <NavLink key={item.to} to={item.to}>
              {item.label}
            </NavLink>
          ))}
        </nav>

        <div className="account">
          <AiStatusChip meta={meta.data} />
          <span className="account-name">{user?.displayName ?? user?.email}</span>
          <button
            type="button"
            className="ghost small"
            onClick={async () => {
              await logout()
              navigate('/auth')
            }}
          >
            Đăng xuất
          </button>
        </div>
      </header>

      <SyntheticBanner meta={meta.data} />

      <main>
        <Outlet />
      </main>
    </div>
  )
}
