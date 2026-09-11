import { useEffect, useState } from 'react'
import { fetchHealth } from '../lib/health'
import { useAuth } from '../lib/auth'

type Status = { state: 'loading' } | { state: 'up'; detail: string } | { state: 'down'; detail: string }

// Phase 2 placeholder: real aggregates arrive in the dashboard phase.
export default function Dashboard() {
  const { user } = useAuth()
  const [status, setStatus] = useState<Status>({ state: 'loading' })

  useEffect(() => {
    let cancelled = false
    fetchHealth()
      .then((h) => {
        if (!cancelled) setStatus({ state: 'up', detail: h.status })
      })
      .catch((err: unknown) => {
        if (!cancelled) setStatus({ state: 'down', detail: err instanceof Error ? err.message : 'Unreachable' })
      })
    return () => {
      cancelled = true
    }
  }, [])

  const dotClass =
    status.state === 'up'
      ? 'bg-emerald-500'
      : status.state === 'down'
        ? 'bg-red-500'
        : 'bg-slate-300'

  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">Dashboard</h1>
      <p className="mt-1 text-sm text-slate-500">
        Signed in as {user?.email}. Customers, policies, premiums and reminders land in later phases.
      </p>

      <div className="mt-6 grid gap-4 sm:grid-cols-2">
        <section className="rounded-lg border border-slate-200 bg-white p-4">
          <h2 className="text-sm font-medium text-slate-700">Backend connectivity</h2>
          <div className="mt-2 flex items-center gap-2">
            <span className={`h-2.5 w-2.5 rounded-full ${dotClass}`} />
            <span className="text-sm text-slate-600">
              {status.state === 'loading' ? 'Checking…' : `API ${status.detail}`}
            </span>
          </div>
        </section>

        <section className="rounded-lg border border-slate-200 bg-white p-4">
          <h2 className="text-sm font-medium text-slate-700">Your account</h2>
          <dl className="mt-2 space-y-1 text-sm">
            <div className="flex justify-between gap-4">
              <dt className="text-slate-500">Name</dt>
              <dd className="text-slate-800">{user?.name}</dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt className="text-slate-500">Role</dt>
              <dd className="text-slate-800">{user?.role.replace(/_/g, ' ').toLowerCase()}</dd>
            </div>
            <div className="flex justify-between gap-4">
              <dt className="text-slate-500">Organization</dt>
              <dd className="truncate font-mono text-xs text-slate-600">{user?.organizationId}</dd>
            </div>
          </dl>
        </section>
      </div>
    </div>
  )
}
