// The shared React Query setup. Production defaults: short stale window (the desk is an operator console —
// data should feel live but not refetch on every focus), no retry on 4xx (auth/forbidden/not-found are
// terminal), bounded retry on 5xx. Plus thin typed hooks so views never touch fetch directly.

import { QueryClient, useQuery, type UseQueryResult } from '@tanstack/react-query';
import { ApiError, request } from './client';

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      refetchOnWindowFocus: false,
      retry: (failureCount, error) => {
        if (error instanceof ApiError && error.status < 500) return false; // 4xx is terminal
        return failureCount < 2;
      },
    },
  },
});

/**
 * Typed GET hook. `key` is the React Query cache key (include every param that scopes the data — entity,
 * market, period, scenario — so a context switch refetches). `path` is the resolved API path. `enabled`
 * gates the call (e.g. until a valid market id is known).
 */
export function useApi<T = unknown>(
  key: readonly unknown[],
  path: string,
  opts: { enabled?: boolean } = {},
): UseQueryResult<T, ApiError> {
  return useQuery<T, ApiError>({
    queryKey: key,
    queryFn: () => request<T>(path),
    enabled: opts.enabled ?? true,
  });
}

export { ApiError, request };
