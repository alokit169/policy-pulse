import { api } from './api'
import type { Page } from './customers'

export type TaskStatus = 'OPEN' | 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED'
export type TaskPriority = 'LOW' | 'MEDIUM' | 'HIGH' | 'URGENT'

export type HumanTask = {
  id: string
  customerId: string
  policyId: string | null
  conversationId: string | null
  assignedAgentId: string
  priority: TaskPriority
  reason: string
  status: TaskStatus
  dueAt: string | null
  createdAt: string
  updatedAt: string
}

/** What the assistant made of a call, and what it was allowed to cause. */
export type Analysis = {
  provider: string
  intent: string
  confidence: number
  committedDate: string | null
  summary: string | null
  sentiment: string | null
  acted: boolean
  decision: string
  actions: string[]
}

export async function listTasks(params: { status?: TaskStatus | ''; page?: number; size?: number }) {
  const query: Record<string, string | number> = {}
  if (params.status) query.status = params.status
  if (params.page !== undefined) query.page = params.page
  if (params.size !== undefined) query.size = params.size

  const res = await api.get<Page<HumanTask>>('/tasks', { params: query })
  return res.data
}

export async function completeTask(id: string): Promise<HumanTask> {
  const res = await api.post<HumanTask>(`/tasks/${id}/complete`)
  return res.data
}

export async function cancelTask(id: string): Promise<HumanTask> {
  const res = await api.post<HumanTask>(`/tasks/${id}/cancel`)
  return res.data
}

export async function analyseConversation(conversationId: string): Promise<Analysis> {
  const res = await api.post<Analysis>(`/conversations/${conversationId}/analyse`)
  return res.data
}

export async function dismissClaim(policyId: string, premiumId: string): Promise<void> {
  await api.post(`/policies/${policyId}/premiums/${premiumId}/dismiss-claim`)
}
