import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { api, tokenStore } from '../api/client'
import type { Tokens, User } from '../api/types'

type AuthState = {
  user: User | null
  loading: boolean
  login: (email: string, password: string) => Promise<void>
  register: (email: string, password: string, displayName: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)

  // A stored token may be expired or revoked, so it is verified against the
  // server rather than trusted on sight.
  useEffect(() => {
    if (!tokenStore.access()) {
      setLoading(false)
      return
    }
    api
      .get<User>('/api/v1/auth/me')
      .then(setUser)
      .catch(() => tokenStore.clear())
      .finally(() => setLoading(false))
  }, [])

  const applyTokens = useCallback(async (tokens: Tokens) => {
    tokenStore.set(tokens.accessToken, tokens.refreshToken)
    setUser(await api.get<User>('/api/v1/auth/me'))
  }, [])

  const login = useCallback(
    async (email: string, password: string) => {
      applyTokens(await api.post<Tokens>('/api/v1/auth/login', { email, password }))
    },
    [applyTokens],
  )

  const register = useCallback(
    async (email: string, password: string, displayName: string) => {
      applyTokens(
        await api.post<Tokens>('/api/v1/auth/register', { email, password, displayName }),
      )
    },
    [applyTokens],
  )

  const logout = useCallback(async () => {
    const refreshToken = tokenStore.refresh()
    if (refreshToken) {
      // Revoke server side too; a stateless logout leaves the session usable.
      await api.post('/api/v1/auth/logout', { refreshToken }).catch(() => undefined)
    }
    tokenStore.clear()
    setUser(null)
  }, [])

  const value = useMemo(
    () => ({ user, loading, login, register, logout }),
    [user, loading, login, register, logout],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}
