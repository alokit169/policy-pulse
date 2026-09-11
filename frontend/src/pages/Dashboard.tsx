import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { errorMessage } from '../lib/api'
import { useAuth } from '../lib/auth'
import { getDashboard } from '../lib/dashboard'
import type { Dashboard as DashboardData, Money } from '../lib/dashboard'
import { formatMoney } from '../lib/policies'

/**
 * The data's job here is magnitude and headline figures, so these are stat tiles
 * rather than charts. One hero figure leads the view; the rest are equal-weight
 * tiles.
 */
function StatTile({
  label,
  children,
  footnote,
}: {
  label: string
  children: ReactNode
  footnote?: string
}) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <p className="text-sm text-slate-500">{label}</p>
      <div className="mt-1 text-2xl font-semibold text-slate-900">{children}</div>
      {footnote && <p className="mt-1 text-xs text-slate-500">{footnote}</p>}
    </div>
  )
}

function countLabel(money: Money, noun: string): string {
  return `${money.count} ${money.count === 1 ? noun : `${noun}s`}`
}

export default function Dashboard() {
  const { user } = useAuth()
  const [data, setData] = useState<DashboardData | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    getDashboard()
      .then((d) => {
        if (!cancelled) setData(d)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, 'Could not load the dashboard'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  if (loading) return <p className="text-sm text-slate-500">Loading…</p>

  if (error || !data) {
    return (
      <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
        {error ?? 'Could not load the dashboard'}
      </p>
    )
  }

  const owning = data.scope === 'OWN_BOOK'
  const nothingOverdue = data.overdue.count === 0

  return (
    <div>
      <h1 className="text-2xl font-semibold tracking-tight">Dashboard</h1>
      <p className="mt-1 text-sm text-slate-500">
        {user?.name} · {owning ? 'your customers and policies' : 'the whole organization'} · as of{' '}
        {data.asOf} ({data.timezone})
      </p>

      {/* The one figure the page leads with: what is owed and not yet collected. */}
      <section
        className={`mt-6 rounded-lg border p-6 ${
          nothingOverdue ? 'border-slate-200 bg-white' : 'border-red-200 bg-red-50'
        }`}
      >
        <div className="flex flex-wrap items-baseline justify-between gap-3">
          <div>
            {/* Status is never colour alone: the word "overdue" carries it too. */}
            <p className={`text-sm font-medium ${nothingOverdue ? 'text-slate-500' : 'text-red-700'}`}>
              {nothingOverdue ? 'Nothing overdue' : 'Overdue premiums'}
            </p>
            <p
              className={`mt-1 text-5xl font-semibold tracking-tight ${
                nothingOverdue ? 'text-slate-400' : 'text-red-800'
              }`}
            >
              {nothingOverdue ? 'All clear' : formatMoney(data.overdue.amount)}
            </p>
            <p className="mt-2 text-sm text-slate-600">
              {nothingOverdue
                ? 'Every premium due so far has been settled.'
                : `${countLabel(data.overdue, 'instalment')} past the due date.`}
            </p>
          </div>
          {!nothingOverdue && (
            <Link
              to="/policies"
              className="rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white hover:bg-slate-800"
            >
              Open policies
            </Link>
          )}
        </div>
      </section>

      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatTile label="Due in the next 7 days" footnote={countLabel(data.dueNextSevenDays, 'instalment')}>
          {formatMoney(data.dueNextSevenDays.amount)}
        </StatTile>
        <StatTile label="Collected this month" footnote={countLabel(data.collectedThisMonth, 'payment')}>
          {formatMoney(data.collectedThisMonth.amount)}
        </StatTile>
        <StatTile label="Active customers">{data.activeCustomers}</StatTile>
        <StatTile label="Active policies">{data.activePolicies}</StatTile>
      </div>

      <section className="mt-8">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-semibold tracking-tight">Action required</h2>
          <div className="flex flex-wrap gap-4 text-sm">
            {data.followUpsDue > 0 && (
              <Link to="/follow-ups" className="text-blue-600 hover:underline">
                {data.followUpsDue} follow-up{data.followUpsDue === 1 ? '' : 's'} past due
              </Link>
            )}
            {data.pendingReminders > 0 && (
              <Link to="/reminders" className="text-blue-600 hover:underline">
                {data.pendingReminders} reminder{data.pendingReminders === 1 ? '' : 's'} waiting to go out
              </Link>
            )}
          </div>
        </div>

        {data.actionRequired.length === 0 ? (
          <p className="mt-3 rounded-lg border border-slate-200 bg-white px-4 py-10 text-center text-sm text-slate-500">
            Nothing needs chasing right now.
          </p>
        ) : (
          <div className="mt-3 overflow-x-auto rounded-lg border border-slate-200 bg-white">
            <table className="w-full min-w-[38rem] text-left text-sm">
              <thead className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
                <tr>
                  <th className="px-4 py-3 font-medium">Customer</th>
                  <th className="px-4 py-3 font-medium">Policy</th>
                  <th className="px-4 py-3 font-medium">Due</th>
                  <th className="px-4 py-3 font-medium">Overdue by</th>
                  <th className="px-4 py-3 text-right font-medium">Amount</th>
                </tr>
              </thead>
              <tbody>
                {data.actionRequired.map((item) => (
                  <tr key={item.premiumId} className="border-b border-slate-100 last:border-0 hover:bg-slate-50">
                    <td className="px-4 py-3">
                      <Link to={`/customers/${item.customerId}`} className="font-medium text-slate-900 hover:underline">
                        {item.customerName}
                      </Link>
                    </td>
                    <td className="px-4 py-3">
                      <Link
                        to={`/policies/${item.policyId}`}
                        className="font-mono text-xs text-slate-600 hover:underline"
                      >
                        {item.policyNumber}
                      </Link>
                    </td>
                    <td className="px-4 py-3 text-slate-700">{item.dueDate}</td>
                    <td className="px-4 py-3">
                      <span className="rounded-full bg-red-50 px-2 py-0.5 text-xs font-medium text-red-700">
                        {item.daysOverdue} day{item.daysOverdue === 1 ? '' : 's'}
                      </span>
                    </td>
                    {/* Aligned digits, which is what tabular figures are for. */}
                    <td className="px-4 py-3 text-right tabular-nums text-slate-800">
                      {formatMoney(item.amount, item.currencyCode)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  )
}
