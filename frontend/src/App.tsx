import { NavLink, Outlet, Route, Routes } from 'react-router-dom'
import RequireAuth from './components/RequireAuth'
import { useAuth } from './lib/auth'
import CustomerForm from './pages/CustomerForm'
import Customers from './pages/Customers'
import Dashboard from './pages/Dashboard'
import Login from './pages/Login'
import NotFound from './pages/NotFound'

// Later phases add routes for policies, premiums, reminders and conversations
// inside the authenticated layout.
const NAV = [
  { to: '/', label: 'Dashboard' },
  { to: '/customers', label: 'Customers' },
]

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
          <Route path="*" element={<NotFound />} />
        </Route>
      </Route>
    </Routes>
  )
}
