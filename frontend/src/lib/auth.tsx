import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { api, getToken, setToken, SESSION_EXPIRED_EVENT } from './api'

export type Role = 'SUPER_ADMIN' | 'ORGANIZATION_ADMIN' | 'MANAGER' | 'AGENT'

export type User = {
  id: string
  organizationId: string
  name: string
  email: string
  role: Role
}

type LoginResponse = {
  token: string
  expiresAt: string
  user: User
}

type AuthContextValue = {
  user: User | null
  /** True until the stored token, if any, has been checked against the API. */
  initialising: boolean
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [initialising, setInitialising] = useState(true)

  // A stored token may have expired or belong to an account that has since been
  // deactivated, so it is only trusted once the API confirms it.
  useEffect(() => {
    let cancelled = false

    if (!getToken()) {
      setInitialising(false)
      return
    }

    api
      .get<User>('/auth/me')
      .then((res) => {
        if (!cancelled) setUser(res.data)
      })
      .catch(() => {
        if (!cancelled) {
          setToken(null)
          setUser(null)
        }
      })
      .finally(() => {
        if (!cancelled) setInitialising(false)
      })

    return () => {
      cancelled = true
    }
  }, [])

  // The response interceptor clears the token; this clears the user with it so
  // an expiry mid-session sends the user back to the login screen.
  useEffect(() => {
    const onExpired = () => setUser(null)
    window.addEventListener(SESSION_EXPIRED_EVENT, onExpired)
    return () => window.removeEventListener(SESSION_EXPIRED_EVENT, onExpired)
  }, [])

  const login = useCallback(async (email: string, password: string) => {
    const res = await api.post<LoginResponse>('/auth/login', { email, password })
    setToken(res.data.token)
    setUser(res.data.user)
  }, [])

  const logout = useCallback(() => {
    setToken(null)
    setUser(null)
  }, [])

  const value = useMemo(
    () => ({ user, initialising, login, logout }),
    [user, initialising, login, logout],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside an AuthProvider')
  return context
}
