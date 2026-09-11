import { useEffect, useState } from 'react'
import { fetchHealth } from '../lib/health'

type Status = { state: 'loading' } | { state: 'up'; detail: string } | { state: 'down'; detail: string }

// Phase 1 placeholder: real aggregates arrive in the dashboard phase. For now
// this proves the browser -> proxy -> backend path is wired correctly.
export default function Dashboard() {
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

  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">Dashboard</h1>
      <p className="mt-1 text-sm text-slate-500">
        Project scaffold. Customers, policies, premiums and reminders land in later phases.
      </p>

      <section className="mt-6 rounded-lg border border-slate-200 bg-white p-4">
        <h2 className="text-sm font-medium text-slate-700">Backend connectivity</h2>
        <div className="mt-2 flex items-center gap-2">
          <span
            className={
              status.state === 'up'
                ? 'h-2.5 w-2.5 rounded-full bg-emerald-500'
                : status.state === 'down'
                  ? 'h-2.5 w-2.5 rounded-full bg-red-500'
                  : 'h-2.5 w-2.5 rounded-full bg-slate-300'
            }
          />
          <span className="text-sm text-slate-600">
            {status.state === 'loading' ? 'Checking…' : `API ${status.detail}`}
          </span>
        </div>
      </section>
    </div>
  )
}
