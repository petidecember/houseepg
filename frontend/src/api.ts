import type {
  BulkAcceptResult,
  Health,
  Match,
  PrioritySource,
  Registry,
  ReloadResult,
  Review,
  ReorderResult,
  ReseedPreview,
  Timeline,
} from './types';

/** A failed call carries the backend's own message, so the UI never invents a reason. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...(init?.headers ?? {}) },
  });
  if (response.status === 204) {
    return undefined as T;
  }
  const body = await response.text();
  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`;
    try {
      const problem = JSON.parse(body) as { message?: string };
      if (problem.message) {
        message = problem.message;
      }
    } catch {
      // a non-JSON error body keeps the status line
    }
    throw new ApiError(message, response.status);
  }
  return body === '' ? (undefined as T) : (JSON.parse(body) as T);
}

export const api = {
  priority: () => request<{ sources: PrioritySource[] }>('/api/priority'),
  reorder: (ids: string[]) =>
    request<ReorderResult>('/api/priority', { method: 'PUT', body: JSON.stringify({ ids }) }),

  reload: () => request<ReloadResult>('/api/config/reload', { method: 'POST' }),
  dump: () => request<void>('/api/config/dump', { method: 'POST' }),

  reseedPreview: () => request<ReseedPreview>('/api/registry/reseed'),
  reseed: () => request<ReseedPreview>('/api/registry/reseed', { method: 'POST' }),

  health: () => request<Health[]>('/api/sources/status'),
  refresh: (id: string) =>
    request<Health>(`/api/sources/${encodeURIComponent(id)}/refresh`, { method: 'POST' }),

  registry: () => request<Registry>('/api/channels'),
  review: (fuzzy: boolean, filter: string) =>
    request<Review>(`/api/review?fuzzy=${fuzzy}&filter=${encodeURIComponent(filter)}`),
  bulkAccept: (dryRun: boolean) =>
    request<BulkAcceptResult>(`/api/matches/bulk-accept?dryRun=${dryRun}`, { method: 'POST' }),
  preview: (id: string) => request<Timeline>(`/api/preview/${encodeURIComponent(id)}`),
  decide: (body: { source: string; sourceChannelId: string; canonicalId: string }) =>
    request<Match>('/api/matches', { method: 'PUT', body: JSON.stringify(body) }),
};
