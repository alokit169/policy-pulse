import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { errorMessage } from '../lib/api'
import { listCustomers } from '../lib/customers'
import type { Customer } from '../lib/customers'
import { listConversations, startConversation } from '../lib/engagement'
import type { Channel, Conversation, ConversationDirection, ConversationStatus } from '../lib/engagement'

const PAGE_SIZE = 20

const CHANNELS: Channel[] = ['VOICE', 'IN_APP', 'EMAIL', 'SMS', 'WHATSAPP']
const DIRECTIONS: ConversationDirection[] = ['OUTBOUND', 'INBOUND']

const STATUS_STYLES: Record<ConversationStatus, string> = {
  STARTED: 'bg-slate-100 text-slate-600',
  IN_PROGRESS: 'bg-blue-50 text-blue-700',
  COMPLETED: 'bg-emerald-50 text-emerald-700',
  FAILED: 'bg-red-50 text-red-700',
  ESCALATED: 'bg-amber-50 text-amber-700',
}

const inputClass =
  'mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500'

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

export default function Conversations() {
  const navigate = useNavigate()

  const [rows, setRows] = useState<Conversation[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const [customers, setCustomers] = useState<Customer[]>([])
  const [logging, setLogging] = useState(false)
  const [saving, setSaving] = useState(false)
  const [customerId, setCustomerId] = useState('')
  const [channel, setChannel] = useState<Channel>('VOICE')
  const [direction, setDirection] = useState<ConversationDirection>('OUTBOUND')

  useEffect(() => {
    let cancelled = false
    listConversations({ size: PAGE_SIZE })
      .then((page) => {
        if (cancelled) return
        setRows(page.items)
        setTotal(page.total)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, 'Could not load conversations'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    if (!logging) return
    listCustomers({ status: 'ACTIVE', size: 200 })
      .then((page) => setCustomers(page.items))
      .catch(() => setCustomers([]))
  }, [logging])

  async function onLog(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSaving(true)
    try {
      const created = await startConversation({ customerId, channel, direction })
      navigate(`/conversations/${created.id}`)
    } catch (err) {
      setError(errorMessage(err, 'Could not log the conversation'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Conversations</h1>
          <p className="mt-1 text-sm text-slate-500">
            {loading ? 'Loading…' : `${total} ${total === 1 ? 'conversation' : 'conversations'}`}
          </p>
        </div>
        <button
          type="button"
          onClick={() => setLogging((open) => !open)}
          className="rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white hover:bg-slate-800"
        >
          {logging ? 'Close' : 'Log a conversation'}
        </button>
      </div>

      {error && (
        <p role="alert" className="mt-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      {logging && (
        <form onSubmit={onLog} className="mt-4 rounded-lg border border-slate-200 bg-white p-6">
          <div className="grid gap-4 sm:grid-cols-3">
            <div>
              <label htmlFor="customerId" className="block text-sm font-medium text-slate-700">
                Customer
              </label>
              <select
                id="customerId"
                required
                value={customerId}
                onChange={(e) => setCustomerId(e.target.value)}
                className={inputClass}
              >
                <option value="">Select a customer…</option>
                {customers.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.fullName} · {c.customerNumber}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="channel" className="block text-sm font-medium text-slate-700">
                Channel
              </label>
              <select
                id="channel"
                value={channel}
                onChange={(e) => setChannel(e.target.value as Channel)}
                className={inputClass}
              >
                {CHANNELS.map((c) => (
                  <option key={c} value={c}>
                    {label(c)}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label htmlFor="direction" className="block text-sm font-medium text-slate-700">
                Direction
              </label>
              <select
                id="direction"
                value={direction}
                onChange={(e) => setDirection(e.target.value as ConversationDirection)}
                className={inputClass}
              >
                {DIRECTIONS.map((d) => (
                  <option key={d} value={d}>
                    {label(d)}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <button
            type="submit"
            disabled={saving || !customerId}
            className="mt-6 rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-60"
          >
            {saving ? 'Starting…' : 'Start'}
          </button>
        </form>
      )}

      {!loading && rows.length === 0 ? (
        <p className="mt-4 rounded-lg border border-slate-200 bg-white px-4 py-10 text-center text-sm text-slate-500">
          No conversations logged yet.
        </p>
      ) : (
        <div className="mt-4 overflow-x-auto rounded-lg border border-slate-200 bg-white">
          <table className="w-full min-w-[38rem] text-left text-sm">
            <thead className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-4 py-3 font-medium">Started</th>
                <th className="px-4 py-3 font-medium">Channel</th>
                <th className="px-4 py-3 font-medium">Outcome</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id} className="border-b border-slate-100 last:border-0 hover:bg-slate-50">
                  <td className="px-4 py-3 text-slate-700">{when(c.startedAt)}</td>
                  <td className="px-4 py-3 text-slate-600">
                    {label(c.channel)}
                    <span className="block text-xs text-slate-500">{label(c.direction)}</span>
                  </td>
                  <td className="px-4 py-3 text-slate-700">{c.outcome ? label(c.outcome) : '—'}</td>
                  <td className="px-4 py-3">
                    <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[c.status]}`}>
                      {label(c.status)}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-right">
                    <Link to={`/conversations/${c.id}`} className="text-sm text-blue-600 hover:underline">
                      Open
                    </Link>
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
