import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { errorMessage } from '../lib/api'
import { formatMoney, listPolicies } from '../lib/policies'
import type { Policy, PolicyStatus } from '../lib/policies'

const PAGE_SIZE = 20

const STATUS_STYLES: Record<PolicyStatus, string> = {
  ACTIVE: 'bg-emerald-50 text-emerald-700',
  LAPSED: 'bg-red-50 text-red-700',
  MATURED: 'bg-blue-50 text-blue-700',
  SURRENDERED: 'bg-slate-100 text-slate-600',
  PAID_UP: 'bg-indigo-50 text-indigo-700',
  CANCELLED: 'bg-slate-100 text-slate-600',
}

const STATUSES: PolicyStatus[] = ['ACTIVE', 'LAPSED', 'MATURED', 'PAID_UP', 'SURRENDERED', 'CANCELLED']

function label(value: string): string {
  return value.replace(/_/g, ' ').toLowerCase()
}

export default function Policies() {
  const [status, setStatus] = useState<PolicyStatus | ''>('')
  const [page, setPage] = useState(0)
  const [rows, setRows] = useState<Policy[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    listPolicies({ status, page, size: PAGE_SIZE })
      .then((data) => {
        if (cancelled) return
        setRows(data.items)
        setTotal(data.total)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, 'Could not load policies'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [status, page])

  const lastPage = Math.max(0, Math.ceil(total / PAGE_SIZE) - 1)

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Policies</h1>
          <p className="mt-1 text-sm text-slate-500">
            {loading ? 'Loading…' : `${total} ${total === 1 ? 'policy' : 'policies'}`}
          </p>
        </div>
        <Link
          to="/policies/new"
          className="rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white hover:bg-slate-800"
        >
          New policy
        </Link>
      </div>

      <div className="mt-6">
        <select
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as PolicyStatus | '')
            setPage(0)
          }}
          aria-label="Filter by status"
          className="rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500"
        >
          <option value="">All statuses</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {label(s)}
            </option>
          ))}
        </select>
      </div>

      {error && (
        <p role="alert" className="mt-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      <div className="mt-4 overflow-x-auto rounded-lg border border-slate-200 bg-white">
        <table className="w-full min-w-[44rem] text-left text-sm">
          <thead className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-4 py-3 font-medium">Number</th>
              <th className="px-4 py-3 font-medium">Provider</th>
              <th className="px-4 py-3 font-medium">Premium</th>
              <th className="px-4 py-3 font-medium">Next due</th>
              <th className="px-4 py-3 font-medium">Status</th>
            </tr>
          </thead>
          <tbody>
            {!loading && rows.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-10 text-center text-slate-500">
                  {status ? 'No policies with that status.' : 'No policies yet.'}
                </td>
              </tr>
            )}
            {rows.map((p) => (
              <tr key={p.id} className="border-b border-slate-100 last:border-0 hover:bg-slate-50">
                <td className="px-4 py-3">
                  <Link to={`/policies/${p.id}`} className="font-mono text-xs font-medium text-slate-900 hover:underline">
                    {p.policyNumber}
                  </Link>
                </td>
                <td className="px-4 py-3 text-slate-700">
                  {p.insuranceProvider}
                  <span className="block text-xs text-slate-500">{label(p.policyType)}</span>
                </td>
                <td className="px-4 py-3 text-slate-700">
                  {formatMoney(p.premiumAmount, p.currencyCode)}
                  <span className="block text-xs text-slate-500">{label(p.premiumFrequency)}</span>
                </td>
                <td className="px-4 py-3 text-slate-700">{p.nextPremiumDueDate ?? '—'}</td>
                <td className="px-4 py-3">
                  <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[p.status]}`}>
                    {label(p.status)}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {total > PAGE_SIZE && (
        <div className="mt-4 flex items-center justify-between text-sm">
          <button
            type="button"
            disabled={page === 0}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
          >
            Previous
          </button>
          <span className="text-slate-500">
            Page {page + 1} of {lastPage + 1}
          </span>
          <button
            type="button"
            disabled={page >= lastPage}
            onClick={() => setPage((p) => Math.min(lastPage, p + 1))}
            className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-50"
          >
            Next
          </button>
        </div>
      )}
    </div>
  )
}
