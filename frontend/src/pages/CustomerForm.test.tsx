import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import CustomerForm from './CustomerForm'
import * as customers from '../lib/customers'

function newCustomerForm() {
  return render(
    <MemoryRouter initialEntries={['/customers/new']}>
      <Routes>
        <Route path="/customers/new" element={<CustomerForm />} />
        <Route path="/customers/:id" element={<div>saved</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

async function fillInTheRequiredFields() {
  await userEvent.type(screen.getByLabelText('First name'), 'Meera')
  await userEvent.type(screen.getByLabelText('Last name'), 'Nair')
  await userEvent.type(screen.getByLabelText('Phone'), '+919876500011')
}

const saveButton = () => screen.getByRole('button', { name: /create customer/i })

describe('creating a customer', () => {
  it('sends what was typed and goes to the customer that was created', async () => {
    const create = vi.spyOn(customers, 'createCustomer')
      .mockResolvedValue({ id: 'c1' } as never)
    newCustomerForm()

    await fillInTheRequiredFields()
    await userEvent.click(saveButton())

    await waitFor(() => expect(screen.getByText('saved')).toBeInTheDocument())
    expect(create).toHaveBeenCalledOnce()
    expect(create.mock.calls[0][0]).toMatchObject({
      firstName: 'Meera',
      lastName: 'Nair',
      phone: '+919876500011',
    })
  })

  /** Blank optional fields are cleared rather than stored as empty strings. */
  it('sends nothing rather than emptiness for the fields left alone', async () => {
    const create = vi.spyOn(customers, 'createCustomer')
      .mockResolvedValue({ id: 'c1' } as never)
    newCustomerForm()

    await fillInTheRequiredFields()
    await userEvent.click(saveButton())

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create.mock.calls[0][0]).toMatchObject({
      email: null,
      address: null,
      dateOfBirth: null,
    })
  })

  it('shows what the API objected to and stays on the form', async () => {
    vi.spyOn(customers, 'createCustomer').mockRejectedValue({
      isAxiosError: true,
      response: { status: 409, data: { error: 'Phone number is already in use' } },
    })
    newCustomerForm()

    await fillInTheRequiredFields()
    await userEvent.click(saveButton())

    expect(await screen.findByRole('alert')).toHaveTextContent('Phone number is already in use')
    expect(saveButton()).toBeEnabled()
  })

  /**
   * The button disables while saving, but that only takes effect on the next
   * render. Two clicks landing before it would be two customers, or — since the
   * phone number is unique per tenant — one customer and a confusing complaint
   * about a duplicate the person did not knowingly make.
   */
  it('does not create two customers from two quick clicks', async () => {
    const create = vi.spyOn(customers, 'createCustomer').mockImplementation(
      () => new Promise((resolve) => setTimeout(() => resolve({ id: 'c1' } as never), 50)),
    )
    newCustomerForm()

    await fillInTheRequiredFields()

    const button = saveButton()
    button.click()
    button.click()

    await waitFor(() => expect(create).toHaveBeenCalled())
    expect(create).toHaveBeenCalledOnce()
  })
})
