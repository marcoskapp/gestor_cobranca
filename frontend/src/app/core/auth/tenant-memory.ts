import { Injectable } from '@angular/core';

const KEY = 'gc.tenant';
const SLUG = /^[a-z0-9][a-z0-9-]{1,48}[a-z0-9]$/;

/**
 * Lembra o identificador (slug) da assessoria usado no último login. É o ÚNICO dado mantido em
 * armazenamento do navegador: nenhum token, e-mail ou dado pessoal é persistido.
 */
@Injectable({ providedIn: 'root' })
export class TenantMemory {
  read(): string {
    try {
      const value = globalThis.localStorage?.getItem(KEY) ?? '';
      return SLUG.test(value) ? value : '';
    } catch {
      return '';
    }
  }

  remember(slug: string): void {
    const normalized = slug.trim().toLowerCase();
    if (!SLUG.test(normalized)) {
      return;
    }
    try {
      globalThis.localStorage?.setItem(KEY, normalized);
    } catch {
      // armazenamento indisponível (modo privado, política do navegador): apenas não lembra
    }
  }
}
