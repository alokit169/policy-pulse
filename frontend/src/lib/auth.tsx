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
  /** Ends this session and every other one, on every device. */
  logoutEverywhere: () => Promise<void>
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
    // Local only: the token stays valid until it expires. Use logoutEverywhere
    // when the token itself may be compromised.
    setToken(null)
    setUser(null)
  }, [])

  const logoutEverywhere = useCallback(async () => {
    try {
      await api.post('/auth/logout-all')
    } catch {
      // Swallowed on purpose. The session is cleared below whatever happened,
      // and there is nothing the caller could usefully do with the failure —
      // every one of them fires this and walks away, so a rejection here is an
      // unhandled one.
    } finally {
      // The token is dead either way once the call has been attempted, and a
      // failure must not strand the user in a signed-in state.
      setToken(null)
      setUser(null)
    }
  }, [])

  const value = useMemo(
    () => ({ user, initialising, login, logout, logoutEverywhere }),
    [user, initialising, login, logout, logoutEverywhere],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside an AuthProvider')
  return context
}
