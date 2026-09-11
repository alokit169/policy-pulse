import { api } from './api'
import type { Page } from './customers'

export type ReminderType =
  | 'PREMIUM_DUE'
  | 'PREMIUM_OVERDUE'
  | 'POLICY_MATURITY'
  | 'BONUS_AVAILABLE'
  | 'DOCUMENT_REQUIRED'
  | 'CUSTOM'
  | 'FOLLOW_UP'

export type ReminderStatus =
  | 'PENDING'
  | 'SCHEDULED'
  | 'IN_PROGRESS'
  | 'SENT'
  | 'FAILED'
  | 'CANCELLED'
  | 'COMPLETED'

export type Channel = 'IN_APP' | 'EMAIL' | 'SMS' | 'WHATSAPP' | 'VOICE'

export type Reminder = {
  id: string
  customerId: string
  policyId: string | null
  reminderType: ReminderType
  scheduledAt: string
  channel: Channel
  status: ReminderStatus
  attemptCount: number
  lastAttemptAt: string | null
  nextAttemptAt: string | null
  createdAt: string
}

export type ReminderConfiguration = {
  daysBeforeDue: string
  daysAfterDue: string
  maxCallAttempts: number
  retryDelayMinutes: number
  allowedCallingStart: string
  allowedCallingEnd: string
  preferredChannel: Channel
  /** Read only: decides what "today" means for this organization's due dates. */
  timezone: string
}

export async function listReminders(params: { status?: ReminderStatus | ''; page?: number; size?: number }) {
  const query: Record<string, string | number> = {}
  if (params.status) query.status = params.status
  if (params.page !== undefined) query.page = params.page
  if (params.size !== undefined) query.size = params.size

  const res = await api.get<Page<Reminder>>('/reminders', { params: query })
  return res.data
}

export async function getReminderConfiguration(): Promise<ReminderConfiguration> {
  const res = await api.get<ReminderConfiguration>('/reminders/configuration')
  return res.data
}

export async function updateReminderConfiguration(
  config: Omit<ReminderConfiguration, 'timezone'>,
): Promise<ReminderConfiguration> {
  const res = await api.put<ReminderConfiguration>('/reminders/configuration', config)
  return res.data
}

export async function detectRemindersNow(): Promise<{ created: number; skipped: number }> {
  const res = await api.post<{ created: number; skipped: number }>('/reminders/detect')
  return res.data
}
