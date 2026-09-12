import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { AuthProvider, useAuth } from './auth'
import { api, getToken, setToken, SESSION_EXPIRED_EVENT } from './api'

const DEMO_USER = {
  id: 'u1',
  organizationId: 'o1',
  name: 'Demo Agent',
  email: 'agent@demo.local',
  role: 'AGENT' as const,
}

/** Reports the context's state, and offers its actions as buttons. */
function Probe() {
  const { user, initialising, logout, logoutEverywhere } = useAuth()
  return (
    <div>
      <span data-testid="state">
        {initialising ? 'initialising' : user ? `signed in as ${user.email}` : 'signed out'}
      </span>
      <button type="button" onClick={logout}>
        sign out
      </button>
      <button type="button" onClick={() => void logoutEverywhere()}>
        sign out everywhere
      </button>
    </div>
  )
}

function mount() {
  return render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  )
}

const state = () => screen.getByTestId('state').textContent

describe('the signed-in session', () => {
  it('does not ask the API anything when there is no stored token', async () => {
    const get = vi.spyOn(api, 'get')
    mount()

    await waitFor(() => expect(state()).toBe('signed out'))
    expect(get).not.toHaveBeenCalled()
  })

  /**
   * A stored token may have expired, or belong to an account that has since
   * been switched off. Trusting it without asking would show a signed-in shell
   * to somebody who is not.
   */
  it('checks a stored token against the API before trusting it', async () => {
    setToken('a-token')
    const get = vi.spyOn(api, 'get').mockResolvedValue({ data: DEMO_USER } as never)

    mount()

    await waitFor(() => expect(state()).toBe('signed in as agent@demo.local'))
    expect(get).toHaveBeenCalledWith('/auth/me')
  })

  it('throws a stored token away when the API rejects it', async () => {
    setToken('a-stale-token')
    vi.spyOn(api, 'get').mockRejectedValue(new Error('401'))

    mount()

    await waitFor(() => expect(state()).toBe('signed out'))
    expect(getToken()).toBeNull()
  })

  /**
   * Nothing is decided while the stored token is still being checked. Showing
   * "signed out" first would bounce somebody to the login page on every reload.
   */
  it('says nothing either way until the check has finished', async () => {
    setToken('a-token')
    let answer: (value: unknown) => void = () => {}
    vi.spyOn(api, 'get').mockReturnValue(new Promise((resolve) => {
      answer = resolve
    }) as never)

    mount()

    expect(state()).toBe('initialising')
    answer({ data: DEMO_USER })
    await waitFor(() => expect(state()).toBe('signed in as agent@demo.local'))
  })

  it('signs the user out when a request reports the session has gone', async () => {
    setToken('a-token')
    vi.spyOn(api, 'get').mockResolvedValue({ data: DEMO_USER } as never)
    mount()
    await waitFor(() => expect(state()).toBe('signed in as agent@demo.local'))

    window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT))

    await waitFor(() => expect(state()).toBe('signed out'))
  })

  it('signing out here forgets the token', async () => {
    setToken('a-token')
    vi.spyOn(api, 'get').mockResolvedValue({ data: DEMO_USER } as never)
    mount()
    await waitFor(() => expect(state()).toBe('signed in as agent@demo.local'))

    await userEvent.click(screen.getByRole('button', { name: 'sign out' }))

    expect(state()).toBe('signed out')
    expect(getToken()).toBeNull()
  })

  /**
   * The token is dead the moment the call has been attempted. A failure that
   * left the user apparently signed in would strand them holding a token they
   * think works.
   */
  it('signing out everywhere ends the session even if the call fails', async () => {
    setToken('a-token')
    vi.spyOn(api, 'get').mockResolvedValue({ data: DEMO_USER } as never)
    vi.spyOn(api, 'post').mockRejectedValue(new Error('network'))
    mount()
    await waitFor(() => expect(state()).toBe('signed in as agent@demo.local'))

    await userEvent.click(screen.getByRole('button', { name: 'sign out everywhere' }))

    await waitFor(() => expect(state()).toBe('signed out'))
    expect(getToken()).toBeNull()
  })
})
