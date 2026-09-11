import { api } from './api'
import type { Page } from './customers'

export type PolicyStatus = 'ACTIVE' | 'LAPSED' | 'MATURED' | 'SURRENDERED' | 'PAID_UP' | 'CANCELLED'
export type PremiumFrequency = 'MONTHLY' | 'QUARTERLY' | 'HALF_YEARLY' | 'YEARLY'
export type PremiumStatus =
  | 'UPCOMING'
  | 'DUE'
  | 'OVERDUE'
  | 'PAID'
  | 'FAILED'
  | 'WAIVED'
  | 'PENDING_VERIFICATION'

/**
 * Amounts arrive as JSON numbers and become JS doubles. That is safe for display
 * at these magnitudes, but no arithmetic on money should happen here: the server
 * holds the authoritative NUMERIC values.
 */
export type Policy = {
  id: string
  customerId: string
  agentId: string
  policyNumber: string
  insuranceProvider: string
  policyType: string
  planName: string | null
  currencyCode: string
  sumAssured: number | null
  premiumAmount: number
  premiumFrequency: PremiumFrequency
  policyStartDate: string | null
  policyEndDate: string | null
  maturityDate: string | null
  nextPremiumDueDate: string | null
  lastPremiumPaidDate: string | null
  status: PolicyStatus
  nomineeName: string | null
  bonusAmount: number | null
  maturityAmount: number | null
  createdAt: string
  updatedAt: string
}

export type Premium = {
  id: string
  policyId: string
  amount: number
  dueDate: string
  paidDate: string | null
  status: PremiumStatus
  paymentReference: string | null
  paymentMethod: string | null
  verificationPending: boolean
  createdAt: string
}

export type PolicyInput = {
  customerId: string
  policyNumber?: string | null
  insuranceProvider: string
  policyType: string
  planName?: string | null
  currencyCode?: string | null
  sumAssured?: string | null
  premiumAmount: string
  premiumFrequency: PremiumFrequency
  policyStartDate?: string | null
  policyEndDate?: string | null
  maturityDate?: string | null
  nomineeName?: string | null
  bonusAmount?: string | null
  maturityAmount?: string | null
}

export async function listPolicies(params: { status?: PolicyStatus | ''; page?: number; size?: number }) {
  const query: Record<string, string | number> = {}
  if (params.status) query.status = params.status
  if (params.page !== undefined) query.page = params.page
  if (params.size !== undefined) query.size = params.size

  const res = await api.get<Page<Policy>>('/policies', { params: query })
  return res.data
}

export async function getPolicy(id: string): Promise<Policy> {
  const res = await api.get<Policy>(`/policies/${id}`)
  return res.data
}

export async function createPolicy(input: PolicyInput): Promise<Policy> {
  const res = await api.post<Policy>('/policies', input)
  return res.data
}

export async function updatePolicy(id: string, input: PolicyInput): Promise<Policy> {
  const res = await api.put<Policy>(`/policies/${id}`, input)
  return res.data
}

export async function changePolicyStatus(id: string, status: PolicyStatus): Promise<Policy> {
  const res = await api.put<Policy>(`/policies/${id}/status`, null, { params: { status } })
  return res.data
}

export async function listPremiums(policyId: string): Promise<Premium[]> {
  const res = await api.get<Premium[]>(`/policies/${policyId}/premiums`)
  return res.data
}

export async function payPremium(
  policyId: string,
  premiumId: string,
  body: { paidDate?: string; paymentReference?: string; paymentMethod?: string },
): Promise<Premium> {
  const res = await api.post<Premium>(`/policies/${policyId}/premiums/${premiumId}/pay`, body)
  return res.data
}

export async function waivePremium(policyId: string, premiumId: string): Promise<Premium> {
  const res = await api.post<Premium>(`/policies/${policyId}/premiums/${premiumId}/waive`)
  return res.data
}

export async function listCustomerPolicies(customerId: string): Promise<Policy[]> {
  const res = await api.get<Policy[]>(`/customers/${customerId}/policies`)
  return res.data
}

/** Display only. Falls back to a plain number if the currency code is unknown. */
export function formatMoney(amount: number | null | undefined, currencyCode = 'INR'): string {
  if (amount === null || amount === undefined) return '—'
  try {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: currencyCode,
      maximumFractionDigits: 2,
    }).format(amount)
  } catch {
    return amount.toFixed(2)
  }
}
