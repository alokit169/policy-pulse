import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach } from 'vitest'

beforeEach(() => {
  // The token lives in localStorage and the API client reads it on every
  // request, so a token left behind by one test would authenticate the next.
  localStorage.clear()
})

afterEach(() => {
  cleanup()
})
