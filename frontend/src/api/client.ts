/**
 * API client.
 *
 * Deliberately thin: it attaches the token, refreshes it once on 401, and turns
 * RFC 9457 problem documents into an error carrying a stable code. No business
 * logic lives here, because business logic in the client is what would stop a
 * future mobile app from reusing this contract.
 */

/**
 * Where the API lives.
 *
 * Empty by default, which means same-origin: the Vite dev server proxies /api to
 * the backend, so local development needs no configuration at all. Set
 * VITE_API_BASE_URL to an origin only when the API is somewhere else.
 *
 * Request paths already start with /api/v1, so a base URL that also ends in
 * /api/v1 is trimmed rather than doubled - that mistake produces a 404 whose
 * cause is invisible in the browser's network tab.
 */
const API_BASE = (import.meta.env.VITE_API_BASE_URL ?? '')
  .replace(/\/+$/, '')
  .replace(/\/api\/v1$/, '')

const ACCESS_TOKEN_KEY = 'certcopilot.accessToken'
const REFRESH_TOKEN_KEY = 'certcopilot.refreshToken'

export class ApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly status: number,
    readonly body?: unknown,
  ) {
    super(message)
  }
}

export const tokenStore = {
  access: () => localStorage.getItem(ACCESS_TOKEN_KEY),
  refresh: () => localStorage.getItem(REFRESH_TOKEN_KEY),
  set(access: string, refresh: string) {
    localStorage.setItem(ACCESS_TOKEN_KEY, access)
    localStorage.setItem(REFRESH_TOKEN_KEY, refresh)
  },
  clear() {
    localStorage.removeItem(ACCESS_TOKEN_KEY)
    localStorage.removeItem(REFRESH_TOKEN_KEY)
  },
}

async function toError(response: Response): Promise<ApiError> {
  let code = `HTTP_${response.status}`
  let message = response.statusText || 'Đã có lỗi xảy ra'
  let body: unknown
  try {
    body = await response.json()
    const problem = body as Record<string, unknown>
    if (typeof problem.code === 'string') code = problem.code
    if (typeof problem.detail === 'string') message = problem.detail
    else if (typeof problem.title === 'string') message = problem.title
  } catch {
    // Non-JSON body; the status line is all we have.
  }
  return new ApiError(code, message, response.status, body)
}

let refreshInFlight: Promise<boolean> | null = null

/** Refreshes once and shares the result, so a burst of 401s makes one call. */
async function refreshTokens(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight
  const refreshToken = tokenStore.refresh()
  if (!refreshToken) return false

  refreshInFlight = (async () => {
    try {
      const response = await fetch(API_BASE + '/api/v1/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      })
      if (!response.ok) {
        tokenStore.clear()
        return false
      }
      const tokens = await response.json()
      tokenStore.set(tokens.accessToken, tokens.refreshToken)
      return true
    } catch {
      return false
    } finally {
      refreshInFlight = null
    }
  })()
  return refreshInFlight
}

type RequestOptions = {
  method?: string
  body?: unknown
  formData?: FormData
  retryOn401?: boolean
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', body, formData, retryOn401 = true } = options
  const headers: Record<string, string> = {}
  const token = tokenStore.access()
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'

  const response = await fetch(API_BASE + path, {
    method,
    headers,
    body: formData ?? (body !== undefined ? JSON.stringify(body) : undefined),
  })

  if (response.status === 401 && retryOn401 && (await refreshTokens())) {
    return request<T>(path, { ...options, retryOn401: false })
  }
  if (!response.ok) throw await toError(response)
  if (response.status === 204) return undefined as T

  const text = await response.text()
  return text ? (JSON.parse(text) as T) : (undefined as T)
}

export const api = {
  get: <T>(path: string) => request<T>(path),
  post: <T>(path: string, body?: unknown) => request<T>(path, { method: 'POST', body }),
  patch: <T>(path: string, body?: unknown) => request<T>(path, { method: 'PATCH', body }),
  upload: <T>(path: string, file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return request<T>(path, { method: 'POST', formData })
  },
}
