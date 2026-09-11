import { api } from './api'
import type { Page } from './customers'

export type Notification = {
  id: string
  title: string
  body: string
  read: boolean
  createdAt: string
}

export async function listNotifications(params: { unreadOnly?: boolean; page?: number; size?: number }) {
  const res = await api.get<Page<Notification>>('/notifications', { params })
  return res.data
}

export async function unreadCount(): Promise<number> {
  const res = await api.get<{ unread: number }>('/notifications/unread-count')
  return res.data.unread
}

export async function markNotificationRead(id: string): Promise<Notification> {
  const res = await api.post<Notification>(`/notifications/${id}/read`)
  return res.data
}

export async function markAllNotificationsRead(): Promise<number> {
  const res = await api.post<{ updated: number }>('/notifications/read-all')
  return res.data.updated
}
