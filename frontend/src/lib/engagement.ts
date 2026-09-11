import { api } from './api'
import type { Page } from './customers'

export type Channel = 'IN_APP' | 'EMAIL' | 'SMS' | 'WHATSAPP' | 'VOICE'
export type ConversationDirection = 'OUTBOUND' | 'INBOUND'
export type ConversationStatus = 'STARTED' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | 'ESCALATED'
export type MessageSender = 'AGENT' | 'CUSTOMER' | 'SYSTEM' | 'ASSISTANT'

export type Message = {
  id: string
  sender: MessageSender
  message: string
  timestamp: string
  transcriptReference: string | null
}

export type Conversation = {
  id: string
  customerId: string
  policyId: string | null
  agentId: string | null
  reminderId: string | null
  channel: Channel
  direction: ConversationDirection
  status: ConversationStatus
  startedAt: string
  endedAt: string | null
  summary: string | null
  sentiment: string | null
  outcome: string | null
  durationSeconds: number | null
  createdAt: string
  /** Only present when one conversation is fetched; listings omit it. */
  messages: Message[] | null
}

export type FollowUpStatus = 'OPEN' | 'DUE' | 'COMPLETED' | 'CANCELLED'
export type FollowUpReason =
  | 'PAYMENT_COMMITMENT'
  | 'CALLBACK_REQUESTED'
  | 'DOCUMENT_PROMISED'
  | 'COMPLAINT'
  | 'OTHER'

export type FollowUp = {
  id: string
  customerId: string
  policyId: string | null
  conversationId: string | null
  assignedAgentId: string | null
  reason: string
  commitmentDate: string | null
  dueAt: string
  status: FollowUpStatus
  notes: string | null
  /** Set once a broken promise has been handed to a person. */
  escalatedAt: string | null
  createdAt: string
  updatedAt: string
}

export async function runFollowUpEngine(): Promise<{
  broughtDue: number
  settled: number
  escalated: number
}> {
  const res = await api.post<{ broughtDue: number; settled: number; escalated: number }>(
    '/follow-ups/run',
  )
  return res.data
}

export async function listConversations(params: { page?: number; size?: number }) {
  const res = await api.get<Page<Conversation>>('/conversations', { params })
  return res.data
}

export async function getConversation(id: string): Promise<Conversation> {
  const res = await api.get<Conversation>(`/conversations/${id}`)
  return res.data
}

export async function startConversation(body: {
  customerId: string
  policyId?: string | null
  channel: Channel
  direction: ConversationDirection
  summary?: string | null
}): Promise<Conversation> {
  const res = await api.post<Conversation>('/conversations', body)
  return res.data
}

export async function addMessage(
  conversationId: string,
  body: { sender: MessageSender; message: string },
): Promise<Message> {
  const res = await api.post<Message>(`/conversations/${conversationId}/messages`, body)
  return res.data
}

export async function closeConversation(
  id: string,
  body: { outcome?: string; sentiment?: string; summary?: string },
): Promise<Conversation> {
  const res = await api.post<Conversation>(`/conversations/${id}/close`, body)
  return res.data
}

export async function listCustomerConversations(customerId: string): Promise<Conversation[]> {
  const res = await api.get<Conversation[]>(`/customers/${customerId}/conversations`)
  return res.data
}

export async function listFollowUps(params: { status?: FollowUpStatus | ''; page?: number; size?: number }) {
  const query: Record<string, string | number> = {}
  if (params.status) query.status = params.status
  if (params.page !== undefined) query.page = params.page
  if (params.size !== undefined) query.size = params.size

  const res = await api.get<Page<FollowUp>>('/follow-ups', { params: query })
  return res.data
}

export async function createFollowUp(body: {
  customerId: string
  policyId?: string | null
  conversationId?: string | null
  reason: FollowUpReason
  commitmentDate?: string | null
  notes?: string | null
}): Promise<FollowUp> {
  const res = await api.post<FollowUp>('/follow-ups', body)
  return res.data
}

export async function completeFollowUp(id: string, notes?: string): Promise<FollowUp> {
  const res = await api.post<FollowUp>(`/follow-ups/${id}/complete`, notes ? { notes } : {})
  return res.data
}

export async function cancelFollowUp(id: string, notes?: string): Promise<FollowUp> {
  const res = await api.post<FollowUp>(`/follow-ups/${id}/cancel`, notes ? { notes } : {})
  return res.data
}

export async function listCustomerFollowUps(customerId: string): Promise<FollowUp[]> {
  const res = await api.get<FollowUp[]>(`/customers/${customerId}/follow-ups`)
  return res.data
}
