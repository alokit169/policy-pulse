import { api } from './api'

export type EntityStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED'

export type Customer = {
  id: string
  assignedAgentId: string
  customerNumber: string
  firstName: string
  lastName: string
  fullName: string
  phone: string
  alternatePhone: string | null
  email: string | null
  dateOfBirth: string | null
  address: string | null
  preferredLanguage: string | null
  preferredContactTime: string | null
  communicationConsent: boolean
  optedOut: boolean
  status: EntityStatus
  notes: string | null
  createdAt: string
  updatedAt: string
}

export type Page<T> = {
  items: T[]
  total: number
  page: number
  size: number
}

/** Fields the API accepts on create and update. */
export type CustomerInput = {
  firstName: string
  lastName: string
  phone: string
  alternatePhone?: string | null
  email?: string | null
  dateOfBirth?: string | null
  address?: string | null
  preferredLanguage?: string | null
  preferredContactTime?: string | null
  communicationConsent?: boolean
  notes?: string | null
}

export type CustomerQuery = {
  q?: string
  status?: EntityStatus | ''
  page?: number
  size?: number
}

export async function listCustomers(query: CustomerQuery): Promise<Page<Customer>> {
  const params: Record<string, string | number> = {}
  if (query.q) params.q = query.q
  if (query.status) params.status = query.status
  if (query.page !== undefined) params.page = query.page
  if (query.size !== undefined) params.size = query.size

  const res = await api.get<Page<Customer>>('/customers', { params })
  return res.data
}

export async function getCustomer(id: string): Promise<Customer> {
  const res = await api.get<Customer>(`/customers/${id}`)
  return res.data
}

export async function createCustomer(input: CustomerInput): Promise<Customer> {
  const res = await api.post<Customer>('/customers', input)
  return res.data
}

export async function updateCustomer(id: string, input: CustomerInput): Promise<Customer> {
  const res = await api.put<Customer>(`/customers/${id}`, input)
  return res.data
}

export async function archiveCustomer(id: string): Promise<void> {
  await api.delete(`/customers/${id}`)
}

export async function restoreCustomer(id: string): Promise<Customer> {
  const res = await api.post<Customer>(`/customers/${id}/restore`)
  return res.data
}
