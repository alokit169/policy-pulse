import { useEffect, useState } from 'react'
import type { ChangeEvent, FormEvent, ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { errorMessage } from '../lib/api'
import { useBusy } from '../lib/useBusy'
import {
  archiveCustomer,
  createCustomer,
  getCustomer,
  restoreCustomer,
  updateCustomer,
} from '../lib/customers'
import type { Customer, CustomerInput } from '../lib/customers'
import { formatMoney, listCustomerPolicies } from '../lib/policies'
import type { Policy } from '../lib/policies'

const EMPTY: CustomerInput = {
  firstName: '',
  lastName: '',
  phone: '',
  alternatePhone: '',
  email: '',
  dateOfBirth: '',
  address: '',
  preferredLanguage: '',
  preferredContactTime: '',
  communicationConsent: true,
  notes: '',
}

const inputClass =
  'mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-500'

function Field({ label, htmlFor, children }: { label: string; htmlFor: string; children: ReactNode }) {
  return (
    <div>
      <label htmlFor={htmlFor} className="block text-sm font-medium text-slate-700">
        {label}
      </label>
      {children}
    </div>
  )
}

/** Blank optional fields are sent as null so the API clears them. */
function toPayload(form: CustomerInput): CustomerInput {
  const clean = (value: string | null | undefined) => {
    const trimmed = (value ?? '').trim()
    return trimmed === '' ? null : trimmed
  }
  return {
    firstName: form.firstName.trim(),
    lastName: form.lastName.trim(),
    phone: form.phone.trim(),
    alternatePhone: clean(form.alternatePhone),
    email: clean(form.email),
    dateOfBirth: clean(form.dateOfBirth),
    address: clean(form.address),
    preferredLanguage: clean(form.preferredLanguage),
    preferredContactTime: clean(form.preferredContactTime),
    communicationConsent: form.communicationConsent ?? true,
    notes: clean(form.notes),
  }
}

export default function CustomerForm() {
  const { id } = useParams<{ id: string }>()
  const isEdit = Boolean(id)
  const navigate = useNavigate()

  const [form, setForm] = useState<CustomerInput>(EMPTY)
  const [existing, setExisting] = useState<Customer | null>(null)
  const [policies, setPolicies] = useState<Policy[]>([])
  const [loading, setLoading] = useState(isEdit)
  const { busy: saving, run } = useBusy()
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    let cancelled = false

    listCustomerPolicies(id)
      .then((held) => {
        if (!cancelled) setPolicies(held)
      })
      .catch(() => {
        // The customer itself failing is what gets reported; an empty list here
        // is not worth a second error banner.
        if (!cancelled) setPolicies([])
      })

    getCustomer(id)
      .then((customer) => {
        if (cancelled) return
        setExisting(customer)
        setForm({
          firstName: customer.firstName,
          lastName: customer.lastName,
          phone: customer.phone,
          alternatePhone: customer.alternatePhone ?? '',
          email: customer.email ?? '',
          dateOfBirth: customer.dateOfBirth ?? '',
          address: customer.address ?? '',
          preferredLanguage: customer.preferredLanguage ?? '',
          preferredContactTime: customer.preferredContactTime ?? '',
          communicationConsent: customer.communicationConsent,
          notes: customer.notes ?? '',
        })
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err, 'Could not load this customer'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [id])

  function change(event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) {
    const { name, value, type, checked } = event.target as HTMLInputElement
    setForm((prev) => ({ ...prev, [name]: type === 'checkbox' ? checked : value }))
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    await run(async () => {
      try {
        const payload = toPayload(form)
        const saved = id ? await updateCustomer(id, payload) : await createCustomer(payload)
        navigate(`/customers/${saved.id}`, { replace: true })
      } catch (err) {
        setError(errorMessage(err, 'Could not save this customer'))
      }
    })
  }

  async function onArchiveToggle() {
    if (!id || !existing) return
    setError(null)
    await run(async () => {
      try {
        if (existing.status === 'INACTIVE') {
          setExisting(await restoreCustomer(id))
        } else {
          await archiveCustomer(id)
          setExisting(await getCustomer(id))
        }
      } catch (err) {
        setError(errorMessage(err, 'Could not change the status'))
      }
    })
  }

  if (loading) return <p className="text-sm text-slate-500">Loading…</p>

  if (isEdit && !existing && error) {
    return (
      <div>
        <p role="alert" className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
          {error}
        </p>
        <Link to="/customers" className="mt-4 inline-block text-sm text-blue-600 hover:underline">
          Back to customers
        </Link>
      </div>
    )
  }

  return (
    <div>
      <Link to="/customers" className="text-sm text-slate-500 hover:text-slate-900">
        ← Customers
      </Link>

      <div className="mt-2 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">
            {isEdit ? existing?.fullName : 'New customer'}
          </h1>
          {existing && (
            <p className="mt-1 font-mono text-xs text-slate-500">
              {existing.customerNumber}
              {existing.status === 'INACTIVE' && ' · archived'}
            </p>
          )}
        </div>
        {existing && (
          <button
            type="button"
            onClick={onArchiveToggle}
            disabled={saving}
            className="rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-700 hover:bg-slate-100 disabled:opacity-60"
          >
            {existing.status === 'INACTIVE' ? 'Restore' : 'Archive'}
          </button>
        )}
      </div>

      <form onSubmit={onSubmit} className="mt-6 rounded-lg border border-slate-200 bg-white p-6">
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="First name" htmlFor="firstName">
            <input id="firstName" name="firstName" required value={form.firstName} onChange={change} className={inputClass} />
          </Field>
          <Field label="Last name" htmlFor="lastName">
            <input id="lastName" name="lastName" required value={form.lastName} onChange={change} className={inputClass} />
          </Field>
          <Field label="Phone" htmlFor="phone">
            <input id="phone" name="phone" required value={form.phone} onChange={change} className={inputClass} />
          </Field>
          <Field label="Alternate phone" htmlFor="alternatePhone">
            <input id="alternatePhone" name="alternatePhone" value={form.alternatePhone ?? ''} onChange={change} className={inputClass} />
          </Field>
          <Field label="Email" htmlFor="email">
            <input id="email" name="email" type="email" value={form.email ?? ''} onChange={change} className={inputClass} />
          </Field>
          <Field label="Date of birth" htmlFor="dateOfBirth">
            <input id="dateOfBirth" name="dateOfBirth" type="date" value={form.dateOfBirth ?? ''} onChange={change} className={inputClass} />
          </Field>
          <Field label="Preferred language" htmlFor="preferredLanguage">
            <input id="preferredLanguage" name="preferredLanguage" placeholder="en" value={form.preferredLanguage ?? ''} onChange={change} className={inputClass} />
          </Field>
          <Field label="Preferred contact time" htmlFor="preferredContactTime">
            <input id="preferredContactTime" name="preferredContactTime" placeholder="Weekday evenings" value={form.preferredContactTime ?? ''} onChange={change} className={inputClass} />
          </Field>
        </div>

        <div className="mt-4">
          <Field label="Address" htmlFor="address">
            <input id="address" name="address" value={form.address ?? ''} onChange={change} className={inputClass} />
          </Field>
        </div>

        <div className="mt-4">
          <Field label="Notes" htmlFor="notes">
            <textarea id="notes" name="notes" rows={3} value={form.notes ?? ''} onChange={change} className={inputClass} />
          </Field>
        </div>

        <label className="mt-4 flex items-center gap-2 text-sm text-slate-700">
          <input
            type="checkbox"
            name="communicationConsent"
            checked={form.communicationConsent ?? true}
            onChange={change}
            className="h-4 w-4 rounded border-slate-300"
          />
          Consents to being contacted
        </label>

        {error && (
          <p role="alert" className="mt-4 rounded-md bg-red-50 px-3 py-2 text-sm text-red-700">
            {error}
          </p>
        )}

        <div className="mt-6 flex gap-3">
          <button
            type="submit"
            disabled={saving}
            className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-60"
          >
            {saving ? 'Saving…' : isEdit ? 'Save changes' : 'Create customer'}
          </button>
          <Link
            to="/customers"
            className="rounded-md border border-slate-300 px-4 py-2 text-sm text-slate-700 hover:bg-slate-100"
          >
            Cancel
          </Link>
        </div>
      </form>

      {existing && (
        <section className="mt-8">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h2 className="text-lg font-semibold tracking-tight">Policies</h2>
            <Link
              to={`/policies/new?customerId=${existing.id}`}
              className="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-100"
            >
              New policy
            </Link>
          </div>

          {policies.length === 0 ? (
            <p className="mt-3 rounded-lg border border-slate-200 bg-white px-4 py-6 text-center text-sm text-slate-500">
              No policies for this customer yet.
            </p>
          ) : (
            <ul className="mt-3 divide-y divide-slate-100 rounded-lg border border-slate-200 bg-white">
              {policies.map((p) => (
                <li key={p.id} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-sm">
                  <Link to={`/policies/${p.id}`} className="font-mono text-xs font-medium text-slate-900 hover:underline">
                    {p.policyNumber}
                  </Link>
                  <span className="text-slate-600">{p.insuranceProvider}</span>
                  <span className="text-slate-700">{formatMoney(p.premiumAmount, p.currencyCode)}</span>
                  <span className="text-slate-500">next due {p.nextPremiumDueDate ?? '—'}</span>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
    </div>
  )
}
