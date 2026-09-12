import { useEffect, useState } from 'react'
import { errorMessage } from '../lib/api'
import { useBusy } from '../lib/useBusy'
import {
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
} from '../lib/notifications'
import type { Notification } from '../lib/notifications'

const PAGE_SIZE = 20

/** Local time, since a notification is about the reader's own day. */
function when(iso: string): string {
  try {
    return new Date(iso).toLocaleString()
  } catch {
    return iso
  }
}

export default function Notifications() {
  const [unreadOnly, setUnreadOnly] = useState(false)
  const [rows, setRows] = useState<Notification[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(true)
  const { busy, run } = useBusy()
  const [error, setError] = useState<string | null>(null)

  async function load(showSpinner = true) {
    if (showSpinner) setLoading(true)
    setError(null)
    try {
      const page = await listNotifications({ unreadOnly, size: PAGE_SIZE })
      setRows(page.items)
      setTotal(page.total)
    } catch (err) {
      setError(errorMessage(err, 'Could not load notifications'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    listNotifications({ unreadOnly, size: PAGE_SIZE })
      .then((page) => {
        if (cancelled) return
        setRows(page.items)
        setTotal(page.total)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, 'Could not load notifications'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [unreadOnly])

  async function onMarkRead(id: string) {
    await run(async () => {
      try {
        await markNotificationRead(id)
        await load(false)
      } catch (err) {
        setError(errorMessage(err, 'Could not mark it read'))
      }
    })
  }

  async function onMarkAllRead() {
    await run(async () => {
      try {
        await markAllNotificationsRead()
        await load(false)
      } catch (err) {
        setError(errorMessage(err, 'Could not mark them read'))
      }
    })
  }

  return (
    <div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Notifications</h1>
          <p className="mt-1 text-sm text-slate-500">
            {loading ? 'Loading…' : `${total} ${total === 1 ? 'notification' : 'notifications'}`}
          </p>
        </div>
        <div className="flex items-center gap-3">
          <label className="flex items-center gap-2 text-sm text-slate-700">
            <input
              type="checkbox"
              checked={unreadOnly}
              onChange={(e) => setUnreadOnly(e.target.checked)}
              className="h-4 w-4 rounded border-slate-300"
            />
            Unread only
          </label>
          <button
            type="button"
            onClick={() => void onMarkAllRead()}
            disabled={busy}
            className="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-100 disabled:opacity-60"
          >
            Mark all read
          </button>
        </div>
      </div>

      {error && (
        <p role="alert" className="mt-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      {!loading && rows.length === 0 ? (
        <p className="mt-4 rounded-lg border border-slate-200 bg-white px-4 py-10 text-center text-sm text-slate-500">
          {unreadOnly ? 'Nothing unread.' : 'No notifications yet.'}
        </p>
      ) : (
        <ul className="mt-4 divide-y divide-slate-100 rounded-lg border border-slate-200 bg-white">
          {rows.map((n) => (
            <li key={n.id} className={`px-4 py-3 ${n.read ? '' : 'bg-blue-50/40'}`}>
              <div className="flex flex-wrap items-start justify-between gap-2">
                <div className="min-w-0">
                  <p className="text-sm font-medium text-slate-900">
                    {!n.read && <span className="mr-2 inline-block h-2 w-2 rounded-full bg-blue-500 align-middle" />}
                    {n.title}
                  </p>
                  <p className="mt-0.5 text-sm text-slate-600">{n.body}</p>
                  <p className="mt-1 text-xs text-slate-400">{when(n.createdAt)}</p>
                </div>
                {!n.read && (
                  <button
                    type="button"
                    onClick={() => void onMarkRead(n.id)}
                    disabled={busy}
                    className="shrink-0 rounded-md px-2 py-1 text-xs text-slate-500 hover:text-slate-900 disabled:opacity-60"
                  >
                    Mark read
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
