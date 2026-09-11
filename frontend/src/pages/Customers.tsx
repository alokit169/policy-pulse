import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { errorMessage } from '../lib/api'
import { listCustomers } from '../lib/customers'
import type { Customer, EntityStatus } from '../lib/customers'

const PAGE_SIZE = 20

const STATUS_STYLES: Record<EntityStatus, string> = {
  ACTIVE: 'bg-emerald-50 text-emerald-700',
  INACTIVE: 'bg-slate-100 text-slate-600',
  SUSPENDED: 'bg-amber-50 text-amber-700',
}

export default function Customers() {
  const [term, setTerm] = useState('')
  const [status, setStatus] = useState<EntityStatus | ''>('')
  const [page, setPage] = useState(0)

  const [rows, setRows] = useState<Customer[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Debounced so typing does not fire a request per keystroke.
  const [query, setQuery] = useState('')
  useEffect(() => {
    const timer = setTimeout(() => {
      setQuery(term)
      setPage(0)
    }, 250)
    return () => clearTimeout(timer)
  }, [term])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    listCustomers({ q: query, status, page, size: PAGE_SIZE })
      .then((data) => {
        if (cancelled) return
        setRows(data.items)
        setTotal(data.total)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, 'Could not load customers'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [query, status, page])

  const lastPage = Math.max(0, Math.ceil(total / PAGE_SIZE) - 1)

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Customers</h1>
          <p className="mt-1 text-sm text-slate-500">
            {loading ? 'Loading…' : `${total} ${total === 1 ? 'customer' : 'customers'}`}
          </p>
        </div>
        <Link
          to="/customers/new"
          className="rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white hover:bg-slate-800"
        >
          New customer
        </Link>
      </div>

      <div className="mt-6 flex flex-wrap gap-3">
        <input
          type="search"
          value={term}
          onChange={(e) => setTerm(e.target.value)}
          placeholder="Search name, phone, email or number"
          aria-label="Search customers"
          className="min-w-0 flex-1 rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500"
        />
        <select
          value={status}
          onChange={(e) => {
            setStatus(e.target.value as EntityStatus | '')
            setPage(0)
          }}
          aria-label="Filter by status"
          className="rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500"
        >
          <option value="">All statuses</option>
          <option value="ACTIVE">Active</option>
          <option value="INACTIVE">Archived</option>
          <option value="SUSPENDED">Suspended</option>
        </select>
      </div>

      {error && (
        <p role="alert" className="mt-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      <div className="mt-4 overflow-x-auto rounded-lg border border-slate-200 bg-white">
        <table className="w-full min-w-[40rem] text-left text-sm">
          <thead className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-4 py-3 font-medium">Name</th>
              <th className="px-4 py-3 font-medium">Number</th>
              <th className="px-4 py-3 font-medium">Phone</th>
              <th className="px-4 py-3 font-medium">Email</th>
              <th className="px-4 py-3 font-medium">Status</th>
            </tr>
          </thead>
          <tbody>
            {!loading && rows.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-10 text-center text-slate-500">
                  {query || status ? 'No customers match that search.' : 'No customers yet.'}
                </td>
              </tr>
            )}
            {rows.map((c) => (
              <tr key={c.id} className="border-b border-slate-100 last:border-0 hover:bg-slate-50">
                <td className="px-4 py-3">
                  <Link to={`/customers/${c.id}`} className="font-medium text-slate-900 hover:underline">
                    {c.fullName}
                  </Link>
                </td>
                <td className="px-4 py-3 font-mono text-xs text-slate-600">{c.customerNumber}</td>
                <td className="px-4 py-3 text-slate-700">{c.phone}</td>
                <td className="px-4 py-3 text-slate-700">{c.email ?? '—'}</td>
                <td className="px-4 py-3">
                  <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[c.status]}`}>
                    {c.status === 'INACTIVE' ? 'Archived' : c.status.toLowerCase()}
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
