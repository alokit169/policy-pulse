import axios from 'axios'

// Same-origin by default: Vite proxies /api in dev, nginx proxies it in Docker.
export const api = axios.create({
  baseURL: '/api',
  headers: { 'Content-Type': 'application/json' },
})

const TOKEN_KEY = 'policy-pulse.token'

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string | null): void {
  if (token === null) localStorage.removeItem(TOKEN_KEY)
  else localStorage.setItem(TOKEN_KEY, token)
}

// Phase 2 wires real login; the interceptor is here so every later phase
// inherits auth headers without touching call sites.
api.interceptors.request.use((config) => {
  const token = getToken()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
