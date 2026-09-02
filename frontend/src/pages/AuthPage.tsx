import { useState } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { ErrorState, Loading } from '../components/states'

export function AuthPage() {
  const { user, loading, login, register } = useAuth()
  const [mode, setMode] = useState<'login' | 'register'>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)

  if (loading) return <Loading />
  // "/" resolves to Today, Plans or onboarding depending on what this account
  // actually has. Hard-coding /today here sent every new learner to an empty
  // screen and left them to find the wizard themselves.
  if (user) return <Navigate to="/" replace />

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (mode === 'login') await login(email, password)
      else await register(email, password, displayName)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-page">
      <form className="card auth-card" onSubmit={submit}>
        <p className="eyebrow">Cert Study Copilot</p>
        <h1>{mode === 'login' ? 'Đăng nhập' : 'Tạo tài khoản'}</h1>
        <p className="muted">
          Biến chứng chỉ, ngày thi và tài liệu bạn đang có thành chương trình học theo ngày.
        </p>

        {mode === 'register' && (
          <>
            <label htmlFor="displayName">Tên hiển thị</label>
            <input
              id="displayName"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
              autoComplete="name"
            />
          </>
        )}

        <label htmlFor="email">Email</label>
        <input
          id="email"
          type="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          autoComplete="email"
        />

        <label htmlFor="password">Mật khẩu</label>
        <input
          id="password"
          type="password"
          required
          minLength={8}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
        />
        {mode === 'register' && <p className="muted">Tối thiểu 8 ký tự.</p>}

        {error != null && <ErrorState error={error} />}

        <button type="submit" disabled={busy}>
          {busy ? 'Đang xử lý…' : mode === 'login' ? 'Đăng nhập' : 'Đăng ký'}
        </button>
        <button
          type="button"
          className="ghost"
          onClick={() => {
            setMode(mode === 'login' ? 'register' : 'login')
            setError(null)
          }}
        >
          {mode === 'login' ? 'Chưa có tài khoản? Đăng ký' : 'Đã có tài khoản? Đăng nhập'}
        </button>
      </form>
    </div>
  )
}
