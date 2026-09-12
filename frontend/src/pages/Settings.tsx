import { useState } from 'react'
import { api, errorMessage } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useBusy } from '../lib/useBusy'

const MINIMUM = 12

/**
 * Changing your own password.
 *
 * <p>It signs every device out, including this one, so the form says so before
 * anybody presses it rather than dropping them at the sign-in page and leaving
 * them to guess whether it worked.
 */
export default function Settings() {
  const { user, logout } = useAuth()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const { busy, run } = useBusy()
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  // Checked here only to say so early; the server decides.
  const tooShort = next.length > 0 && next.length < MINIMUM
  const mismatched = confirm.length > 0 && confirm !== next
  const ready = current.length > 0 && next.length >= MINIMUM && confirm === next

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault()
    setError(null)
    await run(async () => {
      try {
        await api.post('/auth/change-password', { currentPassword: current, newPassword: next })
        setDone(true)
        // The token that made this request is already dead. Clearing it here is
        // what turns that into a sign-in page rather than a wall of 401s.
        window.setTimeout(logout, 2500)
      } catch (err) {
        setError(errorMessage(err, 'Could not change your password'))
      }
    })
  }

  if (done) {
    return (
      <div className="mx-auto max-w-md">
        <h1 className="text-2xl font-semibold tracking-tight">Password changed</h1>
        <p className="mt-2 rounded-md bg-emerald-50 px-3 py-2 text-sm text-emerald-800">
          Every device has been signed out, including this one. Taking you to the sign-in page.
        </p>
      </div>
    )
  }

  return (
    <div className="mx-auto max-w-md">
      <h1 className="text-2xl font-semibold tracking-tight">Settings</h1>
      <p className="mt-1 text-sm text-slate-500">
        Signed in as {user?.email}
      </p>

      <form onSubmit={onSubmit} className="mt-6 rounded-lg border border-slate-200 bg-white p-4">
        <h2 className="text-base font-medium">Change your password</h2>
        <p className="mt-1 text-sm text-slate-500">
          This signs you out everywhere, including here.
        </p>

        {error && (
          <p role="alert" className="mt-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
            {error}
          </p>
        )}

        <label className="mt-4 block text-sm font-medium" htmlFor="current">
          Current password
        </label>
        <input
          id="current"
          type="password"
          autoComplete="current-password"
          value={current}
          onChange={(e) => setCurrent(e.target.value)}
          className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500"
        />

        <label className="mt-4 block text-sm font-medium" htmlFor="next">
          New password
        </label>
        <input
          id="next"
          type="password"
          autoComplete="new-password"
          value={next}
          onChange={(e) => setNext(e.target.value)}
          aria-describedby="next-hint"
          className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500"
        />
        <p id="next-hint" className={`mt-1 text-xs ${tooShort ? 'text-red-700' : 'text-slate-500'}`}>
          At least {MINIMUM} characters. A phrase you can remember beats a short word with
          symbols in it.
        </p>

        <label className="mt-4 block text-sm font-medium" htmlFor="confirm">
          New password again
        </label>
        <input
          id="confirm"
          type="password"
          autoComplete="new-password"
          value={confirm}
          onChange={(e) => setConfirm(e.target.value)}
          className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500"
        />
        {mismatched && <p className="mt-1 text-xs text-red-700">These do not match.</p>}

        <button
          type="submit"
          disabled={busy || !ready}
          className="mt-5 rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-60"
        >
          {busy ? 'Changing…' : 'Change password'}
        </button>
      </form>
    </div>
  )
}
