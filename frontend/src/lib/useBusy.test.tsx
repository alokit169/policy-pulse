import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useBusy } from './useBusy'

function Probe({ action }: { action: () => Promise<void> }) {
  const { busy, run } = useBusy()
  return (
    <button type="button" disabled={busy} onClick={() => void run(action)}>
      {busy ? 'working' : 'go'}
    </button>
  )
}

const button = () => screen.getByRole('button')

describe('running an action once', () => {
  it('runs it', async () => {
    const action = vi.fn().mockResolvedValue(undefined)
    render(<Probe action={action} />)

    button().click()

    await waitFor(() => expect(action).toHaveBeenCalledOnce())
  })

  /**
   * The point of the hook. Disabling on state is not enough, because the state
   * only takes effect on the next render and two clicks land before that.
   */
  it('ignores the clicks that land while it is still running', async () => {
    const action = vi.fn().mockImplementation(
      () => new Promise<void>((resolve) => setTimeout(resolve, 30)),
    )
    render(<Probe action={action} />)

    button().click()
    button().click()
    button().click()

    await waitFor(() => expect(button()).toBeEnabled())
    expect(action).toHaveBeenCalledOnce()
  })

  it('can be run again once the first one has finished', async () => {
    const action = vi.fn().mockResolvedValue(undefined)
    render(<Probe action={action} />)

    button().click()
    await waitFor(() => expect(action).toHaveBeenCalledOnce())
    button().click()

    await waitFor(() => expect(action).toHaveBeenCalledTimes(2))
  })

  /** A failure must let go of the door, or the button never works again. */
  it('lets the next one through after a failure', async () => {
    const action = vi.fn()
      .mockRejectedValueOnce(new Error('no'))
      .mockResolvedValue(undefined)
    render(<Probe action={action} />)

    button().click()
    await waitFor(() => expect(button()).toBeEnabled())
    button().click()

    await waitFor(() => expect(action).toHaveBeenCalledTimes(2))
  })

  it('shows the action as running while it runs', async () => {
    let finish: () => void = () => {}
    const action = () => new Promise<void>((resolve) => {
      finish = resolve
    })
    render(<Probe action={action} />)

    button().click()
    await waitFor(() => expect(button()).toHaveTextContent('working'))

    finish()
    await waitFor(() => expect(button()).toHaveTextContent('go'))
  })
})
