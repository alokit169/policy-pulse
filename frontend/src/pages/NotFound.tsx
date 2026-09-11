import { Link } from 'react-router-dom'

export default function NotFound() {
  return (
    <div>
      <h1 className="text-xl font-semibold">Page not found</h1>
      <Link to="/" className="mt-2 inline-block text-sm text-blue-600 hover:underline">
        Back to dashboard
      </Link>
    </div>
  )
}
