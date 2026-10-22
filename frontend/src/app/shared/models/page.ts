/** Envelope `shared.paging.PageResponse` do backend. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** Limites de `shared.paging.PageQuery`. */
export const DEFAULT_PAGE_SIZE = 20;
export const MAX_PAGE_SIZE = 200;
export const PAGE_SIZE_OPTIONS: readonly number[] = [20, 50, 100, 200];

export interface PageRequest {
  page: number;
  size: number;
}

export function emptyPage<T>(size = DEFAULT_PAGE_SIZE): PageResponse<T> {
  return { content: [], page: 0, size, totalElements: 0, totalPages: 0 };
}

export function clampPageSize(size: number): number {
  return Math.min(Math.max(Math.trunc(size) || DEFAULT_PAGE_SIZE, 1), MAX_PAGE_SIZE);
}
