import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { errorMessage } from '../lib/api'
import {
  addMessage,
  closeConversation,
  createFollowUp,
  getConversation,
} from '../lib/engagement'
import type { Conversation, FollowUpReason, MessageSender } from '../lib/engagement'

const SENDERS: MessageSender[] = ['AGENT', 'CUSTOMER', 'SYSTEM']

const REASONS: FollowUpReason[] = [
  'PAYMENT_COMMITMENT',
  'CALLBACK_REQUESTED',
  'DOCUMENT_PROMISED',
  'COMPLAINT',
  'OTHER',
]

const SENDER_STYLES: Record<string, string> = {
  AGENT: 'bg-slate-100 text-slate-700',
  CUSTOMER: 'bg-blue-50 text-blue-800',
  SYSTEM: 'bg-slate-50 text-slate-500',
  ASSISTANT: 'bg-violet-50 text-violet-700',
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

export default function ConversationDetail() {
  const { id } = useParams<{ id: string }>()

  const [conversation, setConversation] = useState<Conversation | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)

  const [sender, setSender] = useState<MessageSender>('AGENT')
  const [text, setText] = useState('')
  const [outcome, setOutcome] = useState('')
  const [summary, setSummary] = useState('')

  const [reason, setReason] = useState<FollowUpReason>('PAYMENT_COMMITMENT')
  const [commitmentDate, setCommitmentDate] = useState('')

  async function reload() {
    if (!id) return
    setConversation(await getConversation(id))
  }

  useEffect(() => {
    if (!id) return
    let cancelled = false
    getConversation(id)
      .then((c) => {
        if (!cancelled) setConversation(c)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, 'Could not load this conversation'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [id])

  async function onAddMessage(event: FormEvent) {
    event.preventDefault()
    if (!id || !text.trim()) return
    setError(null)
    setBusy(true)
    try {
      await addMessage(id, { sender, message: text.trim() })
      setText('')
      await reload()
    } catch (err) {
      setError(errorMessage(err, 'Could not add that line'))
    } finally {
      setBusy(false)
    }
  }

  async function onClose() {
    if (!id) return
    setError(null)
    setBusy(true)
    try {
      setConversation(await closeConversation(id, { outcome: outcome || undefined, summary: summary || undefined }))
      setNotice('Conversation closed.')
    } catch (err) {
      setError(errorMessage(err, 'Could not close the conversation'))
    } finally {
      setBusy(false)
    }
  }

  async function onCreateFollowUp(event: FormEvent) {
    event.preventDefault()
    if (!conversation) return
    setError(null)
    setNotice(null)
    setBusy(true)
    try {
      await createFollowUp({
        customerId: conversation.customerId,
        conversationId: conversation.id,
        reason,
        commitmentDate: commitmentDate || null,
      })
      setNotice('Follow-up recorded.')
      setCommitmentDate('')
    } catch (err) {
      setError(errorMessage(err, 'Could not record the follow-up'))
    } finally {
      setBusy(false)
    }
  }

  if (loading) return <p className="text-sm text-slate-500">Loading…</p>

  if (!conversation) {
    return (
      <div>
        <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {error ?? 'Conversation not found'}
        </p>
        <Link to="/conversations" className="mt-4 inline-block text-sm text-blue-600 hover:underline">
          Back to conversations
        </Link>
      </div>
    )
  }

  const open = conversation.status !== 'COMPLETED'

  return (
    <div>
      <Link to="/conversations" className="text-sm text-slate-500 hover:text-slate-900">
        ← Conversations
      </Link>

      <h1 className="mt-2 text-2xl font-semibold tracking-tight">
        {label(conversation.channel)} · {label(conversation.direction)}
      </h1>
      <p className="mt-1 text-sm text-slate-500">
        Started {when(conversation.startedAt)} · {label(conversation.status)}
        {conversation.durationSeconds !== null && ` · ${conversation.durationSeconds}s`} ·{' '}
        <Link to={`/customers/${conversation.customerId}`} className="text-blue-600 hover:underline">
          customer
        </Link>
      </p>

      {error && (
        <p role="alert" className="mt-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}
      {notice && (
        <p className="mt-4 rounded-md bg-emerald-50 px-3 py-2 text-sm text-emerald-800">{notice}</p>
      )}

      <section className="mt-6">
        <h2 className="text-lg font-semibold tracking-tight">Transcript</h2>
        {conversation.messages && conversation.messages.length > 0 ? (
          <ul className="mt-3 space-y-2">
            {conversation.messages.map((m) => (
              <li key={m.id} className="rounded-lg border border-slate-200 bg-white p-3">
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                      SENDER_STYLES[m.sender] ?? 'bg-slate-100 text-slate-600'
                    }`}
                  >
                    {label(m.sender)}
                  </span>
                  <span className="text-xs text-slate-400">{when(m.timestamp)}</span>
                </div>
                <p className="mt-2 text-sm text-slate-800">{m.message}</p>
              </li>
            ))}
          </ul>
        ) : (
          <p className="mt-3 rounded-lg border border-slate-200 bg-white px-4 py-8 text-center text-sm text-slate-500">
            Nothing recorded yet.
          </p>
        )}

        {open && (
          <form onSubmit={onAddMessage} className="mt-4 rounded-lg border border-slate-200 bg-white p-4">
            <div className="flex flex-wrap gap-3">
              <select
                value={sender}
                onChange={(e) => setSender(e.target.value as MessageSender)}
                aria-label="Who said it"
                className="rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500"
              >
                {SENDERS.map((s) => (
                  <option key={s} value={s}>
                    {label(s)}
                  </option>
                ))}
              </select>
              <input
                value={text}
                onChange={(e) => setText(e.target.value)}
                placeholder="What was said"
                aria-label="What was said"
                className="min-w-0 flex-1 rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500"
              />
              <button
                type="submit"
                disabled={busy || !text.trim()}
                className="rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-60"
              >
                Add
              </button>
            </div>
          </form>
        )}
      </section>

      {open && (
        <section className="mt-8 grid gap-4 lg:grid-cols-2">
          <div className="rounded-lg border border-slate-200 bg-white p-6">
            <h2 className="text-lg font-semibold tracking-tight">Close</h2>
            <p className="mt-1 text-sm text-slate-500">
              The duration is measured from when it started, so it is not something to fill in.
            </p>
            <label htmlFor="outcome" className="mt-4 block text-sm font-medium text-slate-700">
              Outcome
            </label>
            <input
              id="outcome"
              value={outcome}
              onChange={(e) => setOutcome(e.target.value)}
              placeholder="PAYMENT_COMMITMENT"
              className={inputClass}
            />
            <label htmlFor="summary" className="mt-4 block text-sm font-medium text-slate-700">
              Summary
            </label>
            <textarea
              id="summary"
              rows={3}
              value={summary}
              onChange={(e) => setSummary(e.target.value)}
              className={inputClass}
            />
            <button
              type="button"
              onClick={() => void onClose()}
              disabled={busy}
              className="mt-4 rounded-md border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-100 disabled:opacity-60"
            >
              Close conversation
            </button>
          </div>

          <form onSubmit={onCreateFollowUp} className="rounded-lg border border-slate-200 bg-white p-6">
            <h2 className="text-lg font-semibold tracking-tight">Record a follow-up</h2>
            <p className="mt-1 text-sm text-slate-500">
              Goes to the agent who owns this customer, due at the start of the day they named.
            </p>
            <label htmlFor="reason" className="mt-4 block text-sm font-medium text-slate-700">
              Reason
            </label>
            <select
              id="reason"
              value={reason}
              onChange={(e) => setReason(e.target.value as FollowUpReason)}
              className={inputClass}
            >
              {REASONS.map((r) => (
                <option key={r} value={r}>
                  {label(r)}
                </option>
              ))}
            </select>
            <label htmlFor="commitmentDate" className="mt-4 block text-sm font-medium text-slate-700">
              Date the customer gave
            </label>
            <input
              id="commitmentDate"
              type="date"
              value={commitmentDate}
              onChange={(e) => setCommitmentDate(e.target.value)}
              required={reason === 'PAYMENT_COMMITMENT'}
              className={inputClass}
            />
            <button
              type="submit"
              disabled={busy}
              className="mt-4 rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-60"
            >
              Record
            </button>
          </form>
        </section>
      )}
    </div>
  )
}
