import type { ProblemDetail } from './types';

/**
 * Ошибка API с машинным кодом (`D-081`): клиент показывает текст по коду, а `detail`
 * остаётся диагностикой и не является контрактом. Сессия живёт в `HttpOnly` cookie,
 * поэтому `credentials: 'include'` обязателен и на запросах, и после перезагрузки.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string | null;

  constructor(status: number, code: string | null, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }

  get isUnauthorized(): boolean {
    return this.status === 401;
  }
}

async function readProblem(response: Response): Promise<ProblemDetail | null> {
  const type = response.headers.get('content-type') ?? '';
  if (!type.includes('json')) {
    return null;
  }
  try {
    return (await response.json()) as ProblemDetail;
  } catch {
    return null;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body !== undefined) {
    headers.set('Content-Type', 'application/json');
  }

  let response: Response;
  try {
    response = await fetch(path, {
      ...init,
      credentials: 'include',
      headers,
    });
  } catch {
    throw new ApiError(0, 'network', 'network');
  }

  if (!response.ok) {
    const problem = await readProblem(response);
    throw new ApiError(response.status, problem?.code ?? null, problem?.detail ?? problem?.title ?? '');
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

export const api = {
  get: <T>(path: string): Promise<T> => request<T>(path),
  post: <T>(path: string, body?: unknown): Promise<T> =>
    request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  patch: <T>(path: string, body: unknown): Promise<T> =>
    request<T>(path, { method: 'PATCH', body: JSON.stringify(body) }),
  put: <T>(path: string, body?: unknown): Promise<T> =>
    request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) }),
};

/** Query-часть собирается здесь: пустой необязательный параметр не должен попасть в URL. */
export function query(params: Readonly<Record<string, string | number | boolean | null | undefined>>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined) {
      search.set(key, String(value));
    }
  }
  const encoded = search.toString();
  return encoded ? `?${encoded}` : '';
}
