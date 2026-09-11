import { useEffect, useState } from 'react'
import { Link, NavLink, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import RequireAuth from './components/RequireAuth'
import { useAuth } from './lib/auth'
import { unreadCount } from './lib/notifications'
import Notifications from './pages/Notifications'
import Reminders from './pages/Reminders'
import CustomerForm from './pages/CustomerForm'
import Customers from './pages/Customers'
import Dashboard from './pages/Dashboard'
import Policies from './pages/Policies'
import PolicyForm from './pages/PolicyForm'
import Login from './pages/Login'
import NotFound from './pages/NotFound'

// Later phases add routes for policies, premiums, reminders and conversations
// inside the authenticated layout.
const NAV = [
  { to: '/', label: 'Dashboard' },
  { to: '/customers', label: 'Customers' },
  { to: '/policies', label: 'Policies' },
  { to: '/reminders', label: 'Reminders' },
]

/**
 * Unread count in the header. Refreshed on navigation and on a slow timer:
 * reminders are raised by a background job, so the number can change without
 * the user doing anything.
 */
function NotificationBell() {
  const [unread, setUnread] = useState(0)
  const location = useLocation()

  useEffect(() => {
    let cancelled = false

    const refresh = () => {
      unreadCount()
        .then((count) => {
          if (!cancelled) setUnread(count)
        })
        .catch(() => {
          // A failed count is not worth interrupting the page for.
        })
    }

    refresh()
    const timer = setInterval(refresh, 60_000)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [location.pathname])

  return (
    <Link
      to="/notifications"
      className="relative rounded-md border border-slate-300 px-3 py-1 text-slate-700 hover:bg-slate-100"
      aria-label={unread > 0 ? `Notifications, ${unread} unread` : 'Notifications'}
    >
      Alerts
      {unread > 0 && (
        <span className="ml-1 rounded-full bg-blue-600 px-1.5 py-0.5 text-xs font-medium text-white">
          {unread > 99 ? '99+' : unread}
        </span>
      )}
    </Link>
  )
}

function AuthenticatedLayout() {
  const { user, logout, logoutEverywhere } = useAuth()

  return (
    <div className="min-h-screen bg-slate-50 text-slate-900">
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
          <span className="text-lg font-semibold tracking-tight">Policy Pulse</span>
          <nav className="flex gap-4 text-sm">
            {NAV.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                // Without end, to="/" matches every path and the dashboard link
                // would stay highlighted on every page.
                end={item.to === '/'}
                className={({ isActive }) =>
                  isActive ? 'font-medium text-slate-900' : 'text-slate-500 hover:text-slate-900'
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-3 text-sm">
            <NotificationBell />
            <span className="text-slate-500">
              {user?.name} · {user?.role.replace(/_/g, ' ').toLowerCase()}
            </span>
            <button
              type="button"
              onClick={logout}
              className="rounded-md border border-slate-300 px-3 py-1 text-slate-700 hover:bg-slate-100"
            >
              Sign out
            </button>
            <button
              type="button"
              onClick={() => {
                void logoutEverywhere()
              }}
              title="Revoke every token issued to your account, on all devices"
              className="text-slate-500 underline-offset-2 hover:text-slate-900 hover:underline"
            >
              everywhere
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-5xl px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route element={<RequireAuth />}>
        <Route element={<AuthenticatedLayout />}>
          <Route path="/" element={<Dashboard />} />
          <Route path="/customers" element={<Customers />} />
          <Route path="/customers/new" element={<CustomerForm />} />
          <Route path="/customers/:id" element={<CustomerForm />} />
          <Route path="/policies" element={<Policies />} />
          <Route path="/policies/new" element={<PolicyForm />} />
          <Route path="/policies/:id" element={<PolicyForm />} />
          <Route path="/reminders" element={<Reminders />} />
          <Route path="/notifications" element={<Notifications />} />
          <Route path="*" element={<NotFound />} />
        </Route>
      </Route>
    </Routes>
  )
}
