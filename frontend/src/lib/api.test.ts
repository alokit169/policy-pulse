import type { AxiosAdapter } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, errorMessage, getToken, setToken, SESSION_EXPIRED_EVENT } from './api'

/** Answers every request with one canned outcome, so the interceptors can be exercised. */
function answerWith(status: number, data: unknown = {}): void {
  const adapter: AxiosAdapter = async (config) => {
    const response = { data, status, statusText: '', headers: {}, config }
    if (status >= 400) {
      return Promise.reject(
        Object.assign(new Error(`Request failed with status code ${status}`), {
          isAxiosError: true,
          config,
          response,
          toJSON: () => ({}),
        }),
      )
    }
    return response
  }
  api.defaults.adapter = adapter
}

describe('the API client', () => {
  beforeEach(() => {
    api.defaults.adapter = undefined
  })

  it('puts the stored token on every request', async () => {
    setToken('a-token')
    let seen: string | undefined
    api.defaults.adapter = async (config) => {
      seen = config.headers?.Authorization as string | undefined
      return { data: {}, status: 200, statusText: '', headers: {}, config }
    }

    await api.get('/anything')

    expect(seen).toBe('Bearer a-token')
  })

  it('sends nothing when there is no token', async () => {
    let seen: unknown
    api.defaults.adapter = async (config) => {
      seen = config.headers?.Authorization
      return { data: {}, status: 200, statusText: '', headers: {}, config }
    }

    await api.get('/anything')

    expect(seen).toBeUndefined()
  })

  describe('when a request comes back 401', () => {
    it('ends the session if we had been authenticated', async () => {
      setToken('a-token')
      const expired = vi.fn()
      window.addEventListener(SESSION_EXPIRED_EVENT, expired)
      answerWith(401)

      await expect(api.get('/customers')).rejects.toBeTruthy()

      expect(getToken()).toBeNull()
      expect(expired).toHaveBeenCalledOnce()
      window.removeEventListener(SESSION_EXPIRED_EVENT, expired)
    })

    /**
     * A rejected sign-in is also a 401. Treating it as an expiry would fire the
     * session-gone path at somebody who has no session to lose, on the one page
     * where that is guaranteed to happen.
     */
    it('leaves a failed sign-in alone', async () => {
      const expired = vi.fn()
      window.addEventListener(SESSION_EXPIRED_EVENT, expired)
      answerWith(401)

      await expect(api.post('/auth/login', {})).rejects.toBeTruthy()

      expect(expired).not.toHaveBeenCalled()
      window.removeEventListener(SESSION_EXPIRED_EVENT, expired)
    })
  })

  /**
   * 403 is a caller who is signed in and not allowed. Signing them out for
   * asking would be the wrong lesson, and would lose their work.
   */
  it('keeps the session when a request is merely forbidden', async () => {
    setToken('a-token')
    answerWith(403)

    await expect(api.get('/reminders/detect')).rejects.toBeTruthy()

    expect(getToken()).toBe('a-token')
  })

  it('survives storage it is not allowed to touch', () => {
    const getItem = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })

    expect(getToken()).toBeNull()
    expect(() => setToken('a-token')).not.toThrow()

    getItem.mockRestore()
    setItem.mockRestore()
  })
})

describe('the message shown when a request fails', () => {
  it('prefers what the API said', () => {
    const error = {
      isAxiosError: true,
      response: { status: 409, data: { error: 'Phone number is already in use' } },
    }
    expect(errorMessage(error, 'fallback')).toBe('Phone number is already in use')
  })

  it('says the server is unreachable when there was no answer at all', () => {
    expect(errorMessage({ isAxiosError: true, response: undefined }, 'fallback'))
      .toBe('Cannot reach the server')
  })

  it('falls back when the answer carried no message', () => {
    expect(errorMessage({ isAxiosError: true, response: { status: 500, data: {} } }, 'fallback'))
      .toBe('fallback')
    expect(errorMessage(new Error('something else'), 'fallback')).toBe('fallback')
    expect(errorMessage(undefined, 'fallback')).toBe('fallback')
  })
})
