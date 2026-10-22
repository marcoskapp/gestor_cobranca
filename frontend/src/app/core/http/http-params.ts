import { HttpParams } from '@angular/common/http';

export type QueryValue = string | number | boolean | null | undefined;

/** Monta HttpParams ignorando valores vazios (filtros não preenchidos). */
export function toHttpParams(query: Record<string, QueryValue>): HttpParams {
  let params = new HttpParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === null || value === undefined || value === '') {
      continue;
    }
    params = params.set(key, String(value));
  }
  return params;
}
