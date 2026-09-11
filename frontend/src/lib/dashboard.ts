import { api } from './api'

export type DashboardScope = 'OWN_BOOK' | 'ORGANIZATION'

/** Safe to total because every policy is in rupees, enforced by the database. */
export type Money = {
  count: number
  amount: number
}

export type ActionItem = {
  premiumId: string
  policyId: string
  customerId: string
  customerName: string
  policyNumber: string
  currencyCode: string
  amount: number
  dueDate: string
  daysOverdue: number
}

export type Dashboard = {
  scope: DashboardScope
  /** The organization's own date, which decides what counts as overdue. */
  asOf: string
  timezone: string
  activeCustomers: number
  activePolicies: number
  pendingReminders: number
  /** Commitments past their moment that nobody has closed. */
  followUpsDue: number
  overdue: Money
  dueNextSevenDays: Money
  collectedThisMonth: Money
  actionRequired: ActionItem[]
}

export async function getDashboard(): Promise<Dashboard> {
  const res = await api.get<Dashboard>('/dashboard')
  return res.data
}
