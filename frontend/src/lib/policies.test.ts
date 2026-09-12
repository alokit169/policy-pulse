import { describe, expect, it } from 'vitest'
import { formatMoney } from './policies'

describe('showing an amount of money', () => {
  it('shows rupees', () => {
    // Grouping and the symbol's placement follow the reader's own locale, so
    // this checks what must be there rather than exactly how it is arranged.
    const shown = formatMoney(4500, 'INR')
    expect(shown).toContain('4,500.00')
    expect(shown).toMatch(/₹|INR/)
  })

  it('keeps the paise', () => {
    expect(formatMoney(1234.56, 'INR')).toContain('1,234.56')
  })

  /** Zero is an amount. Treating it as missing would hide a settled balance. */
  it('shows zero rather than a dash', () => {
    expect(formatMoney(0, 'INR')).toContain('0.00')
    expect(formatMoney(0, 'INR')).not.toBe('—')
  })

  it('shows a dash when there is no amount at all', () => {
    expect(formatMoney(null)).toBe('—')
    expect(formatMoney(undefined)).toBe('—')
  })

  it('defaults to rupees, which is the only currency the API stores', () => {
    expect(formatMoney(100)).toMatch(/₹|INR/)
  })

  /** Display must not throw over a currency code the browser has never heard of. */
  it('falls back to a plain number for a currency it cannot format', () => {
    expect(formatMoney(4500, 'NOT-A-CURRENCY')).toBe('4500.00')
  })
})
