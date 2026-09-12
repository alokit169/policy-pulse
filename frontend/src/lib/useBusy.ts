import { useCallback, useRef, useState } from 'react'

/**
 * Runs an action, and refuses to run it again while it is still running.
 *
 * <p>Disabling the button on a piece of state does not do this. State takes
 * effect on the next render, and two clicks land before that — measured, a
 * double-click on "Create customer" sent two creates. What comes back is either
 * two of something, or a complaint about a duplicate the person did not
 * knowingly make, which is worse for being confusing.
 *
 * <p>The ref is what actually holds the door: it changes the moment it is set,
 * where the state is only for showing the button as busy.
 *
 * <p>A failure in the action is passed on rather than swallowed, so a caller
 * that fires and forgets has to say so. Every handler here catches its own
 * errors inside the action, because each one has its own thing to say.
 *
 * @returns busy, for the UI, and run, which the handler wraps its work in
 */
export function useBusy(): {
  busy: boolean
  run: (action: () => Promise<void>) => Promise<void>
} {
  const [busy, setBusy] = useState(false)
  const inFlight = useRef(false)

  const run = useCallback(async (action: () => Promise<void>) => {
    if (inFlight.current) return
    inFlight.current = true
    setBusy(true)
    try {
      await action()
    } finally {
      inFlight.current = false
      setBusy(false)
    }
  }, [])

  return { busy, run }
}
