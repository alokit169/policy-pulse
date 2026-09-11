import axios from 'axios'

export type Health = { status: string }

// Actuator lives outside /api, so it needs its own client.
export async function fetchHealth(): Promise<Health> {
  const res = await axios.get<Health>('/actuator/health')
  return res.data
}
