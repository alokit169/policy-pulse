import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import Login from './Login'
import { AuthProvider } from '../lib/auth'
import { api } from '../lib/api'

/** Shows where the router ended up, so a redirect is something a test can read. */
function Landed() {
  const location = useLocation()
  return <div data-testid="landed">{location.pathname}</div>
}

function signIn(from?: string) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/login', state: from ? { from } : null }]}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="*" element={<Landed />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  )
}

function acceptTheLogin() {
  return vi.spyOn(api, 'post').mockResolvedValue({
    data: {
      token: 'a-token',
      expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
      user: {
        id: 'u1',
        organizationId: 'o1',
        name: 'Demo Agent',
        email: 'agent@demo.local',
        role: 'AGENT',
      },
    },
  } as never)
}

async function submit() {
  await userEvent.type(screen.getByLabelText('Email'), 'agent@demo.local')
  await userEvent.type(screen.getByLabelText('Password'), 'Password123!')
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }))
}

describe('signing in', () => {
  it('sends the user on to the page they asked for', async () => {
    acceptTheLogin()
    signIn('/policies')

    await submit()

    await waitFor(() => expect(screen.getByTestId('landed')).toHaveTextContent('/policies'))
  })

  it('lands on the dashboard when they came straight to the login page', async () => {
    acceptTheLogin()
    signIn()

    await submit()

    await waitFor(() => expect(screen.getByTestId('landed')).toHaveTextContent('/'))
  })

  it('says what went wrong rather than silently failing', async () => {
    vi.spyOn(api, 'post').mockRejectedValue({
      isAxiosError: true,
      response: { status: 401, data: { error: 'Invalid email or password' } },
    })
    signIn()

    await submit()

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password')
  })

  /**
   * Where the user is sent after signing in comes from the URL they were
   * stopped at, which anybody can choose by sending them a link. A target that
   * leaves this site is an open redirect: the victim signs in to the real site
   * and is handed to somebody else's, still believing they are here.
   *
   * React Router resolves a leading backslash the way a browser resolves a
   * leading slash, which is why stripping "//" alone is not enough.
   */
  describe('the page it sends them to', () => {
    const elsewhere = [
      '//evil.example',
      String.raw`/\evil.example`,
      String.raw`\\evil.example`,
      'https://evil.example/phish',
      'javascript:alert(1)',
    ]

    it.each(elsewhere)('refuses to leave the site for %s', async (target) => {
      acceptTheLogin()
      signIn(target)

      await submit()

      await waitFor(() => expect(screen.getByTestId('landed')).toBeInTheDocument())
      const landed = screen.getByTestId('landed').textContent ?? ''

      expect(landed, `sent to ${JSON.stringify(target)}`).toBe('/')
    })
  })
})
