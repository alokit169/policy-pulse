import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { errorMessage } from '../lib/api'
import { cancelFollowUp, completeFollowUp, listFollowUps } from '../lib/engagement'
import type { FollowUp, FollowUpStatus } from '../lib/engagement'

const PAGE_SIZE = 20

const STATUS_STYLES: Record<FollowUpStatus, string> = {
  OPEN: 'bg-blue-50 text-blue-700',
  DUE: 'bg-amber-50 text-amber-700',
  COMPLETED: 'bg-emerald-50 text-emerald-700',
  CANCELLED: 'bg-slate-100 text-slate-500',
}

const OUTSTANDING: FollowUpStatus[] = ['OPEN', 'DUE']

function label(value: string): string {
  return value.replace(/_/g, ' ').toLowerCase()
}

function when(iso: string): string {
  try {
    return new Date(iso).toLocaleString()
  } catch {
    return iso
  }
}

/** Past its moment and still open, which is what makes it worth chasing. */
function isOverdue(followUp: FollowUp): boolean {
  return OUTSTANDING.includes(followUp.status) && new Date(followUp.dueAt).getTime() < Date.now()
}

export default function FollowUps() {
  const [status, setStatus] = useState<FollowUpStatus | ''>('')
  const [rows, setRows] = useState<FollowUp[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    listFollowUps({ status, size: PAGE_SIZE })
      .then((page) => {
        if (cancelled) return
        setRows(page.items)
        setTotal(page.total)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, 'Could not load follow-ups'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [status, reloadToken])

  async function settle(id: string, action: 'complete' | 'cancel') {
    setError(null)
    setBusy(true)
    try {
      if (action === 'complete') await completeFollowUp(id)
      else await cancelFollowUp(id)
      setReloadToken((t) => t + 1)
    } catch (err) {
      setError(errorMessage(err, 'Could not update the follow-up'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Follow-ups</h1>
          <p className="mt-1 text-sm text-slate-500">
            {loading ? 'Loading…' : `${total} ${total === 1 ? 'follow-up' : 'follow-ups'}`}
          </p>
        </div>
        <select
          value={status}
          onChange={(e) => setStatus(e.target.value as FollowUpStatus | '')}
          aria-label="Filter by status"
          className="rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500"
        >
          <option value="">All statuses</option>
          <option value="OPEN">Open</option>
          <option value="DUE">Due</option>
          <option value="COMPLETED">Completed</option>
          <option value="CANCELLED">Cancelled</option>
        </select>
      </div>

      {error && (
        <p role="alert" className="mt-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      {!loading && rows.length === 0 ? (
        <p className="mt-4 rounded-lg border border-slate-200 bg-white px-4 py-10 text-center text-sm text-slate-500">
          Nothing to follow up. Commitments made during a call appear here.
        </p>
      ) : (
        <div className="mt-4 overflow-x-auto rounded-lg border border-slate-200 bg-white">
          <table className="w-full min-w-[40rem] text-left text-sm">
            <thead className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-4 py-3 font-medium">Reason</th>
                <th className="px-4 py-3 font-medium">Customer</th>
                <th className="px-4 py-3 font-medium">Due</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              {rows.map((f) => (
                <tr
                  key={f.id}
                  className={`border-b border-slate-100 last:border-0 ${isOverdue(f) ? 'bg-red-50/40' : ''}`}
                >
                  <td className="px-4 py-3">
                    <span className="font-medium text-slate-900">{label(f.reason)}</span>
                    {f.commitmentDate && (
                      <span className="block text-xs text-slate-500">promised {f.commitmentDate}</span>
                    )}
                    {f.notes && <span className="block text-xs text-slate-500">{f.notes}</span>}
                  </td>
                  <td className="px-4 py-3">
                    <Link to={`/customers/${f.customerId}`} className="text-slate-700 hover:underline">
                      View customer
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-slate-700">
                    {when(f.dueAt)}
                    {isOverdue(f) && (
                      <span className="ml-2 rounded-full bg-red-50 px-2 py-0.5 text-xs font-medium text-red-700">
                        overdue
                      </span>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[f.status]}`}>
                      {label(f.status)}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    {OUTSTANDING.includes(f.status) && (
                      <span className="flex justify-end gap-2">
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() => void settle(f.id, 'complete')}
                          className="rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-700 hover:bg-slate-100 disabled:opacity-60"
                        >
                          Done
                        </button>
                        <button
                          type="button"
                          disabled={busy}
                          onClick={() => void settle(f.id, 'cancel')}
                          className="rounded-md px-2 py-1 text-xs text-slate-500 hover:text-slate-900 disabled:opacity-60"
                        >
                          Cancel
                        </button>
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
