import axios from 'axios'

// Same-origin by default: Vite proxies /api in dev, nginx proxies it in Docker.
export const api = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
})

const TOKEN_KEY = 'policy-pulse.token'

/** Raised when a request we authenticated comes back 401, so the session is gone. */
export const SESSION_EXPIRED_EVENT = 'policy-pulse:session-expired'

export function getToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY)
  } catch {
    // Private browsing or blocked storage.
    return null
  }
}

export function setToken(token: string | null): void {
  try {
    if (token === null) localStorage.removeItem(TOKEN_KEY)
    else localStorage.setItem(TOKEN_KEY, token)
  } catch {
    // Nothing useful to do; the in-memory session still works for this tab.
  }
}

api.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

api.interceptors.response.use(
  (response) => response,
  (error) => {
    // The API returns 401 for missing or invalid credentials and 403 when the
    // caller is authenticated but not allowed, so only 401 ends the session.
    // Guarded on there having been a token: a failed login is also a 401 and
    // must not be treated as an expiry.
    if (error?.response?.status === 401 && getToken()) {
      setToken(null)
      window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))
    }
    return Promise.reject(error)
  },
)

/** Pulls the API's error message out of a failed request. */
export function errorMessage(error: unknown, fallback: string): string {
  if (axios.isAxiosError(error)) {
    const apiError = error.response?.data?.error
    if (typeof apiError === 'string' && apiError.length > 0) return apiError
    if (!error.response) return 'Cannot reach the server'
  }
  return fallback
}
