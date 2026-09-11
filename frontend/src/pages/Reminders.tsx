import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { errorMessage } from '../lib/api'
import { useAuth } from '../lib/auth'
import {
  detectRemindersNow,
  getReminderConfiguration,
  listReminders,
  updateReminderConfiguration,
} from '../lib/reminders'
import type { Channel, Reminder, ReminderConfiguration, ReminderStatus } from '../lib/reminders'

const PAGE_SIZE = 20

const STATUS_STYLES: Record<ReminderStatus, string> = {
  PENDING: 'bg-slate-100 text-slate-600',
  SCHEDULED: 'bg-blue-50 text-blue-700',
  IN_PROGRESS: 'bg-amber-50 text-amber-700',
  SENT: 'bg-emerald-50 text-emerald-700',
  FAILED: 'bg-red-50 text-red-700',
  CANCELLED: 'bg-slate-100 text-slate-500',
  COMPLETED: 'bg-emerald-50 text-emerald-700',
}

const CHANNELS: Channel[] = ['IN_APP', 'EMAIL', 'SMS', 'WHATSAPP', 'VOICE']

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

export default function Reminders() {
  const { user } = useAuth()
  const canManage = user?.role !== 'AGENT'

  const [rows, setRows] = useState<Reminder[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const [config, setConfig] = useState<ReminderConfiguration | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  useEffect(() => {
    let cancelled = false
    setLoading(true)

    Promise.all([listReminders({ size: PAGE_SIZE }), getReminderConfiguration()])
      .then(([page, configuration]) => {
        if (cancelled) return
        setRows(page.items)
        setTotal(page.total)
        setConfig(configuration)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, 'Could not load reminders'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [reloadToken])

  async function onSaveConfig(event: FormEvent) {
    event.preventDefault()
    if (!config) return
    setError(null)
    setNotice(null)
    setSaving(true)
    try {
      const { timezone, ...editable } = config
      void timezone
      setConfig(await updateReminderConfiguration(editable))
      setNotice('Settings saved.')
    } catch (err) {
      setError(errorMessage(err, 'Could not save the settings'))
    } finally {
      setSaving(false)
    }
  }

  async function onDetectNow() {
    setError(null)
    setNotice(null)
    setSaving(true)
    try {
      const result = await detectRemindersNow()
      setNotice(
        result.created === 0
          ? 'Nothing new to raise. Detection is safe to run again at any time.'
          : `Raised ${result.created} reminder${result.created === 1 ? '' : 's'}.`,
      )
      setReloadToken((t) => t + 1)
    } catch (err) {
      setError(errorMessage(err, 'Could not run detection'))
    } finally {
      setSaving(false)
    }
  }

  function field(key: keyof Omit<ReminderConfiguration, 'timezone'>, value: string | number) {
    setConfig((prev) => (prev ? { ...prev, [key]: value } : prev))
  }

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Reminders</h1>
          <p className="mt-1 text-sm text-slate-500">
            {loading ? 'Loading…' : `${total} raised`}
            {config && ` · organization timezone ${config.timezone}`}
          </p>
        </div>
        {canManage && (
          <button
            type="button"
            onClick={() => void onDetectNow()}
            disabled={saving}
            className="rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-60"
          >
            Run detection now
          </button>
        )}
      </div>

      {error && (
        <p role="alert" className="mt-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}
      {notice && (
        <p className="mt-4 rounded-md bg-emerald-50 px-3 py-2 text-sm text-emerald-800">{notice}</p>
      )}

      <div className="mt-4 overflow-x-auto rounded-lg border border-slate-200 bg-white">
        <table className="w-full min-w-[38rem] text-left text-sm">
          <thead className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-4 py-3 font-medium">Type</th>
              <th className="px-4 py-3 font-medium">Scheduled</th>
              <th className="px-4 py-3 font-medium">Channel</th>
              <th className="px-4 py-3 font-medium">Attempts</th>
              <th className="px-4 py-3 font-medium">Status</th>
            </tr>
          </thead>
          <tbody>
            {!loading && rows.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-10 text-center text-slate-500">
                  No reminders yet. They are raised automatically as premiums approach their due date.
                </td>
              </tr>
            )}
            {rows.map((r) => (
              <tr key={r.id} className="border-b border-slate-100 last:border-0">
                <td className="px-4 py-3 text-slate-700">{label(r.reminderType)}</td>
                <td className="px-4 py-3 text-slate-700">{when(r.scheduledAt)}</td>
                <td className="px-4 py-3 text-slate-600">{label(r.channel)}</td>
                <td className="px-4 py-3 text-slate-600">{r.attemptCount}</td>
                <td className="px-4 py-3">
                  <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[r.status]}`}>
                    {label(r.status)}
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {config && (
        <section className="mt-8">
          <h2 className="text-lg font-semibold tracking-tight">Settings</h2>
          <p className="mt-1 text-sm text-slate-500">
            Due dates are read in {config.timezone}, and reminders are queued for the start of the
            calling window.
            {!canManage && ' Only managers can change these.'}
          </p>

          <form onSubmit={onSaveConfig} className="mt-4 rounded-lg border border-slate-200 bg-white p-6">
            <fieldset disabled={!canManage || saving} className="grid gap-4 sm:grid-cols-2">
              <div>
                <label htmlFor="daysBeforeDue" className="block text-sm font-medium text-slate-700">
                  Days before due
                </label>
                <input
                  id="daysBeforeDue"
                  value={config.daysBeforeDue}
                  onChange={(e) => field('daysBeforeDue', e.target.value)}
                  placeholder="10,5,1,0"
                  className={inputClass}
                />
              </div>
              <div>
                <label htmlFor="daysAfterDue" className="block text-sm font-medium text-slate-700">
                  Days after due
                </label>
                <input
                  id="daysAfterDue"
                  value={config.daysAfterDue}
                  onChange={(e) => field('daysAfterDue', e.target.value)}
                  placeholder="2"
                  className={inputClass}
                />
              </div>
              <div>
                <label htmlFor="allowedCallingStart" className="block text-sm font-medium text-slate-700">
                  Calling window opens
                </label>
                <input
                  id="allowedCallingStart"
                  type="time"
                  value={config.allowedCallingStart.slice(0, 5)}
                  onChange={(e) => field('allowedCallingStart', `${e.target.value}:00`)}
                  className={inputClass}
                />
              </div>
              <div>
                <label htmlFor="allowedCallingEnd" className="block text-sm font-medium text-slate-700">
                  Calling window closes
                </label>
                <input
                  id="allowedCallingEnd"
                  type="time"
                  value={config.allowedCallingEnd.slice(0, 5)}
                  onChange={(e) => field('allowedCallingEnd', `${e.target.value}:00`)}
                  className={inputClass}
                />
              </div>
              <div>
                <label htmlFor="maxCallAttempts" className="block text-sm font-medium text-slate-700">
                  Maximum attempts
                </label>
                <input
                  id="maxCallAttempts"
                  type="number"
                  min={1}
                  max={10}
                  value={config.maxCallAttempts}
                  onChange={(e) => field('maxCallAttempts', Number(e.target.value))}
                  className={inputClass}
                />
              </div>
              <div>
                <label htmlFor="retryDelayMinutes" className="block text-sm font-medium text-slate-700">
                  Retry delay (minutes)
                </label>
                <input
                  id="retryDelayMinutes"
                  type="number"
                  min={1}
                  max={1440}
                  value={config.retryDelayMinutes}
                  onChange={(e) => field('retryDelayMinutes', Number(e.target.value))}
                  className={inputClass}
                />
              </div>
              <div>
                <label htmlFor="preferredChannel" className="block text-sm font-medium text-slate-700">
                  Preferred channel
                </label>
                <select
                  id="preferredChannel"
                  value={config.preferredChannel}
                  onChange={(e) => field('preferredChannel', e.target.value)}
                  className={inputClass}
                >
                  {CHANNELS.map((c) => (
                    <option key={c} value={c}>
                      {label(c)}
                    </option>
                  ))}
                </select>
              </div>
            </fieldset>

            {canManage && (
              <button
                type="submit"
                disabled={saving}
                className="mt-6 rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-60"
              >
                {saving ? 'Saving…' : 'Save settings'}
              </button>
            )}
          </form>
        </section>
      )}
    </div>
  )
}
