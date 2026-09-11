import { useEffect, useState } from 'react'
import type { ChangeEvent, FormEvent, ReactNode } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { errorMessage } from '../lib/api'
import { listCustomers } from '../lib/customers'
import type { Customer } from '../lib/customers'
import {
  createPolicy,
  formatMoney,
  getPolicy,
  listPremiums,
  payPremium,
  updatePolicy,
  waivePremium,
} from '../lib/policies'
import type { Policy, PolicyInput, Premium, PremiumFrequency, PremiumStatus } from '../lib/policies'

const FREQUENCIES: PremiumFrequency[] = ['MONTHLY', 'QUARTERLY', 'HALF_YEARLY', 'YEARLY']

const PREMIUM_STYLES: Record<PremiumStatus, string> = {
  UPCOMING: 'bg-slate-100 text-slate-600',
  DUE: 'bg-amber-50 text-amber-700',
  OVERDUE: 'bg-red-50 text-red-700',
  PAID: 'bg-emerald-50 text-emerald-700',
  FAILED: 'bg-red-50 text-red-700',
  WAIVED: 'bg-slate-100 text-slate-500',
  PENDING_VERIFICATION: 'bg-blue-50 text-blue-700',
}

const SETTLED: PremiumStatus[] = ['PAID', 'WAIVED']

const EMPTY: PolicyInput = {
  customerId: '',
  insuranceProvider: '',
  policyType: '',
  planName: '',
  currencyCode: 'INR',
  sumAssured: '',
  premiumAmount: '',
  premiumFrequency: 'YEARLY',
  policyStartDate: '',
  policyEndDate: '',
  maturityDate: '',
  nomineeName: '',
  bonusAmount: '',
  maturityAmount: '',
}

const inputClass =
  'mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500'

function label(value: string): string {
  return value.replace(/_/g, ' ').toLowerCase()
}

function Field({ label: text, htmlFor, children }: { label: string; htmlFor: string; children: ReactNode }) {
  return (
    <div>
      <label htmlFor={htmlFor} className="block text-sm font-medium text-slate-700">
        {text}
      </label>
      {children}
    </div>
  )
}

/** Blank optional fields become null so the API clears rather than rejects them. */
function toPayload(form: PolicyInput): PolicyInput {
  const clean = (v: string | null | undefined) => {
    const trimmed = (v ?? '').trim()
    return trimmed === '' ? null : trimmed
  }
  return {
    customerId: form.customerId,
    insuranceProvider: form.insuranceProvider.trim(),
    policyType: form.policyType.trim(),
    planName: clean(form.planName),
    currencyCode: clean(form.currencyCode) ?? 'INR',
    sumAssured: clean(form.sumAssured),
    premiumAmount: form.premiumAmount.trim(),
    premiumFrequency: form.premiumFrequency,
    policyStartDate: clean(form.policyStartDate),
    policyEndDate: clean(form.policyEndDate),
    maturityDate: clean(form.maturityDate),
    nomineeName: clean(form.nomineeName),
    bonusAmount: clean(form.bonusAmount),
    maturityAmount: clean(form.maturityAmount),
  }
}

export default function PolicyForm() {
  const { id } = useParams<{ id: string }>()
  const [searchParams] = useSearchParams()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const [form, setForm] = useState<PolicyInput>({
    ...EMPTY,
    customerId: searchParams.get('customerId') ?? '',
  })
  const [existing, setExisting] = useState<Policy | null>(null)
  const [premiums, setPremiums] = useState<Premium[]>([])
  const [customers, setCustomers] = useState<Customer[]>([])
  const [loading, setLoading] = useState(isEdit)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Only needed when creating: an existing policy shows its customer as a link.
  useEffect(() => {
    if (isEdit) return
    listCustomers({ status: 'ACTIVE', size: 200 })
      .then((page) => setCustomers(page.items))
      .catch(() => setCustomers([]))
  }, [isEdit])

  useEffect(() => {
    if (!id) return
    let cancelled = false

    Promise.all([getPolicy(id), listPremiums(id)])
      .then(([policy, schedule]) => {
        if (cancelled) return
        setExisting(policy)
        setPremiums(schedule)
        setForm({
          customerId: policy.customerId,
          insuranceProvider: policy.insuranceProvider,
          policyType: policy.policyType,
          planName: policy.planName ?? '',
          currencyCode: policy.currencyCode,
          sumAssured: policy.sumAssured?.toString() ?? '',
          premiumAmount: policy.premiumAmount.toString(),
          premiumFrequency: policy.premiumFrequency,
          policyStartDate: policy.policyStartDate ?? '',
          policyEndDate: policy.policyEndDate ?? '',
          maturityDate: policy.maturityDate ?? '',
          nomineeName: policy.nomineeName ?? '',
          bonusAmount: policy.bonusAmount?.toString() ?? '',
          maturityAmount: policy.maturityAmount?.toString() ?? '',
        })
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, 'Could not load this policy'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [id])

  function change(event: ChangeEvent<HTMLInputElement | HTMLSelectElement>) {
    const { name, value } = event.target
    setForm((prev) => ({ ...prev, [name]: value }))
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setSaving(true)
    try {
      const payload = toPayload(form)
      const saved = id ? await updatePolicy(id, payload) : await createPolicy(payload)
      if (id) {
        setExisting(saved)
        setPremiums(await listPremiums(id))
      } else {
        navigate(`/policies/${saved.id}`, { replace: true })
      }
    } catch (err) {
      setError(errorMessage(err, 'Could not save this policy'))
    } finally {
      setSaving(false)
    }
  }

  async function settle(premiumId: string, action: 'pay' | 'waive') {
    if (!id) return
    setError(null)
    setSaving(true)
    try {
      if (action === 'pay') await payPremium(id, premiumId, {})
      else await waivePremium(id, premiumId)

      setPremiums(await listPremiums(id))
      setExisting(await getPolicy(id))
    } catch (err) {
      setError(errorMessage(err, 'Could not update the premium'))
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <p className="text-sm text-slate-500">Loading…</p>

  if (isEdit && !existing && error) {
    return (
      <div>
        <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
        <Link to="/policies" className="mt-4 inline-block text-sm text-blue-600 hover:underline">
          Back to policies
        </Link>
      </div>
    )
  }

  return (
    <div>
      <Link to="/policies" className="text-sm text-slate-500 hover:text-slate-900">
        ← Policies
      </Link>

      <div className="mt-2 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">
            {existing ? existing.policyNumber : 'New policy'}
          </h1>
          {existing && (
            <p className="mt-1 text-sm text-slate-500">
              {existing.insuranceProvider} · {label(existing.status)} ·{' '}
              <Link to={`/customers/${existing.customerId}`} className="text-blue-600 hover:underline">
                customer
              </Link>
            </p>
          )}
        </div>
      </div>

      {error && (
        <p role="alert" className="mt-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
      )}

      <form onSubmit={onSubmit} className="mt-6 rounded-lg border border-slate-200 bg-white p-6">
        <div className="grid gap-4 sm:grid-cols-2">
          {!isEdit && (
            <Field label="Customer" htmlFor="customerId">
              <select id="customerId" name="customerId" required value={form.customerId} onChange={change} className={inputClass}>
                <option value="">Select a customer…</option>
                {customers.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.fullName} · {c.customerNumber}
                  </option>
                ))}
              </select>
            </Field>
          )}
          <Field label="Insurance provider" htmlFor="insuranceProvider">
            <input id="insuranceProvider" name="insuranceProvider" required value={form.insuranceProvider} onChange={change} className={inputClass} />
          </Field>
          <Field label="Policy type" htmlFor="policyType">
            <input id="policyType" name="policyType" required placeholder="ENDOWMENT" value={form.policyType} onChange={change} className={inputClass} />
          </Field>
          <Field label="Plan name" htmlFor="planName">
            <input id="planName" name="planName" value={form.planName ?? ''} onChange={change} className={inputClass} />
          </Field>
          <Field label="Currency" htmlFor="currencyCode">
            <input id="currencyCode" name="currencyCode" maxLength={3} value={form.currencyCode ?? ''} onChange={change} className={inputClass} />
          </Field>
          <Field label="Premium amount" htmlFor="premiumAmount">
            <input id="premiumAmount" name="premiumAmount" required inputMode="decimal" placeholder="12000.00" value={form.premiumAmount} onChange={change} className={inputClass} />
          </Field>
          <Field label="Frequency" htmlFor="premiumFrequency">
            <select id="premiumFrequency" name="premiumFrequency" value={form.premiumFrequency} onChange={change} className={inputClass}>
              {FREQUENCIES.map((f) => (
                <option key={f} value={f}>
                  {label(f)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Sum assured" htmlFor="sumAssured">
            <input id="sumAssured" name="sumAssured" inputMode="decimal" value={form.sumAssured ?? ''} onChange={change} className={inputClass} />
          </Field>
          <Field label="Start date" htmlFor="policyStartDate">
            <input id="policyStartDate" name="policyStartDate" type="date" value={form.policyStartDate ?? ''} onChange={change} className={inputClass} />
          </Field>
          <Field label="End date" htmlFor="policyEndDate">
            <input id="policyEndDate" name="policyEndDate" type="date" value={form.policyEndDate ?? ''} onChange={change} className={inputClass} />
          </Field>
          <Field label="Maturity date" htmlFor="maturityDate">
            <input id="maturityDate" name="maturityDate" type="date" value={form.maturityDate ?? ''} onChange={change} className={inputClass} />
          </Field>
          <Field label="Nominee" htmlFor="nomineeName">
            <input id="nomineeName" name="nomineeName" value={form.nomineeName ?? ''} onChange={change} className={inputClass} />
          </Field>
        </div>

        <p className="mt-4 text-xs text-slate-500">
          The premium schedule is generated from the start date, end date and frequency. Changing them
          rebuilds unsettled instalments; anything already paid or waived is kept.
        </p>

        <div className="mt-6 flex gap-3">
          <button
            type="submit"
            disabled={saving}
            className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-60"
          >
            {saving ? 'Saving…' : isEdit ? 'Save changes' : 'Create policy'}
          </button>
          <Link to="/policies" className="rounded-md border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-100">
            Cancel
          </Link>
        </div>
      </form>

      {existing && (
        <section className="mt-8">
          <h2 className="text-lg font-semibold tracking-tight">Premium schedule</h2>
          <p className="mt-1 text-sm text-slate-500">
            Next due {existing.nextPremiumDueDate ?? '—'} · last paid {existing.lastPremiumPaidDate ?? '—'}
          </p>

          <div className="mt-4 overflow-x-auto rounded-lg border border-slate-200 bg-white">
            <table className="w-full min-w-[36rem] text-left text-sm">
              <thead className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500">
                <tr>
                  <th className="px-4 py-3 font-medium">Due</th>
                  <th className="px-4 py-3 font-medium">Amount</th>
                  <th className="px-4 py-3 font-medium">Status</th>
                  <th className="px-4 py-3 font-medium">Paid</th>
                  <th className="px-4 py-3" />
                </tr>
              </thead>
              <tbody>
                {premiums.length === 0 && (
                  <tr>
                    <td colSpan={5} className="px-4 py-8 text-center text-slate-500">
                      No instalments. Set a start date, end date and frequency to generate the schedule.
                    </td>
                  </tr>
                )}
                {premiums.map((p) => (
                  <tr key={p.id} className="border-b border-slate-100 last:border-0">
                    <td className="px-4 py-3 text-slate-700">{p.dueDate}</td>
                    <td className="px-4 py-3 text-slate-700">{formatMoney(p.amount, existing.currencyCode)}</td>
                    <td className="px-4 py-3">
                      <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${PREMIUM_STYLES[p.status]}`}>
                        {label(p.status)}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-slate-700">{p.paidDate ?? '—'}</td>
                    <td className="px-4 py-3 text-right">
                      {!SETTLED.includes(p.status) && (
                        <span className="flex justify-end gap-2">
                          <button
                            type="button"
                            disabled={saving}
                            onClick={() => void settle(p.id, 'pay')}
                            className="rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-700 hover:bg-slate-100 disabled:opacity-60"
                          >
                            Record payment
                          </button>
                          <button
                            type="button"
                            disabled={saving}
                            onClick={() => void settle(p.id, 'waive')}
                            className="rounded-md px-2 py-1 text-xs text-slate-500 hover:text-slate-900 disabled:opacity-60"
                          >
                            Waive
                          </button>
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </div>
  )
}
