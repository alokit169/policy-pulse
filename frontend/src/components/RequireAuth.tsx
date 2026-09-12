import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../lib/auth'

/**
 * Gate for authenticated routes. Renders nothing decisive until the stored
 * token has been checked, otherwise a reload would bounce a signed-in user to
 * the login screen before /auth/me answers.
 */
export default function RequireAuth() {
  const { user, initialising } = useAuth()
  const location = useLocation()

  if (initialising) {
    return (
      <div className="flex min-h-screen items-center justify-center text-sm text-slate-500">
        Loading…
      </div>
    )
  }

  if (!user) {
    // Search included, or a deep link with filters on it comes back bare.
    // Login checks this before going anywhere near it.
    return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  }

  return <Outlet />
}
