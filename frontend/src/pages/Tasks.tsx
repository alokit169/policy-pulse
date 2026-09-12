import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { errorMessage } from '../lib/api'
import { useBusy } from '../lib/useBusy'
import { cancelTask, completeTask, listTasks } from '../lib/tasks'
import type { HumanTask, TaskPriority, TaskStatus } from '../lib/tasks'

const PAGE_SIZE = 20

const PRIORITY_STYLES: Record<TaskPriority, string> = {
  LOW: 'bg-slate-100 text-slate-600',
  MEDIUM: 'bg-blue-50 text-blue-700',
  HIGH: 'bg-amber-50 text-amber-700',
  URGENT: 'bg-red-50 text-red-700',
}

const STATUS_STYLES: Record<TaskStatus, string> = {
  OPEN: 'bg-blue-50 text-blue-700',
  IN_PROGRESS: 'bg-amber-50 text-amber-700',
  COMPLETED: 'bg-emerald-50 text-emerald-700',
  CANCELLED: 'bg-slate-100 text-slate-500',
}

const OUTSTANDING: TaskStatus[] = ['OPEN', 'IN_PROGRESS']

/** Why the assistant handed this over, in words rather than a constant. */
const REASON_TEXT: Record<string, string> = {
  VERIFY_CLAIMED_PAYMENT: 'Customer says they have paid. Check the books before recording anything.',
  COMMITMENT_WITHOUT_A_USABLE_DATE: 'A promise to pay was heard but no usable date was given.',
  POSSIBLE_OPT_OUT: 'The customer may have asked not to be contacted. Confirm before acting.',
  REQUEST_HUMAN_AGENT: 'Customer asked to speak to a person.',
  CANNOT_PAY: 'Customer said they cannot pay.',
  PAYMENT_DELAYED: 'Customer asked for more time.',
  NO_LONGER_INTERESTED: 'Customer may want to end the policy.',
  WRONG_NUMBER: 'The number may not belong to this customer.',
}

function label(value: string): string {
  return value.replace(/_/g, ' ').toLowerCase()
}

function describe(reason: string): string {
  if (REASON_TEXT[reason]) return REASON_TEXT[reason]
  if (reason.startsWith('LOW_CONFIDENCE_')) {
    return `The assistant was not confident enough to act on what it heard (${label(
      reason.replace('LOW_CONFIDENCE_', ''),
    )}).`
  }
  return label(reason)
}

function when(iso: string): string {
  try {
    return new Date(iso).toLocaleString()
  } catch {
    return iso
  }
}

export default function Tasks() {
  const [status, setStatus] = useState<TaskStatus | ''>('OPEN')
  const [rows, setRows] = useState<HumanTask[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const { busy, run } = useBusy()
  const [error, setError] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)

    listTasks({ status, size: PAGE_SIZE })
      .then((page) => {
        if (cancelled) return
        setRows(page.items)
        setTotal(page.total)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, 'Could not load tasks'))
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
    await run(async () => {
      try {
        if (action === 'complete') await completeTask(id)
        else await cancelTask(id)
        setReloadToken((t) => t + 1)
      } catch (err) {
        setError(errorMessage(err, 'Could not update the task'))
      }
    })
  }

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Tasks</h1>
          <p className="mt-1 text-sm text-slate-500">
            {loading ? 'Loading…' : `${total} ${total === 1 ? 'task' : 'tasks'}`} · work the assistant
            handed over
          </p>
        </div>
        <select
          value={status}
          onChange={(e) => setStatus(e.target.value as TaskStatus | '')}
          aria-label="Filter by status"
          className="rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500"
        >
          <option value="OPEN">Open</option>
          <option value="">All statuses</option>
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
          Nothing waiting on a person.
        </p>
      ) : (
        <ul className="mt-4 space-y-2">
          {rows.map((t) => (
            <li key={t.id} className="rounded-lg border border-slate-200 bg-white p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <span
                      className={`rounded-full px-2 py-0.5 text-xs font-medium ${PRIORITY_STYLES[t.priority]}`}
                    >
                      {label(t.priority)}
                    </span>
                    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[t.status]}`}>
                      {label(t.status)}
                    </span>
                    <span className="text-xs text-slate-400">{when(t.createdAt)}</span>
                  </div>
                  <p className="mt-2 text-sm text-slate-800">{describe(t.reason)}</p>
                  <p className="mt-1 flex flex-wrap gap-3 text-xs">
                    <Link to={`/customers/${t.customerId}`} className="text-blue-600 hover:underline">
                      Customer
                    </Link>
                    {t.policyId && (
                      <Link to={`/policies/${t.policyId}`} className="text-blue-600 hover:underline">
                        Policy
                      </Link>
                    )}
                    {t.conversationId && (
                      <Link to={`/conversations/${t.conversationId}`} className="text-blue-600 hover:underline">
                        Conversation
                      </Link>
                    )}
                  </p>
                </div>
                {OUTSTANDING.includes(t.status) && (
                  <span className="flex shrink-0 gap-2">
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => void settle(t.id, 'complete')}
                      className="rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-700 hover:bg-slate-100 disabled:opacity-60"
                    >
                      Done
                    </button>
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => void settle(t.id, 'cancel')}
                      className="rounded-md px-2 py-1 text-xs text-slate-500 hover:text-slate-900 disabled:opacity-60"
                    >
                      Dismiss
                    </button>
                  </span>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
