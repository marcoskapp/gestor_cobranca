import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { Observable } from 'rxjs';
import { AuthService } from '../../auth/auth.service';
import { API_V1 } from '../../config/api';
import { SKIP_ERROR_NOTIFICATION } from '../../http/http-context';

/** `QuickSearchQueries.QuickSearchView` (collection): devedores e casos encontrados, só o mínimo para reconhecer. */
export interface QuickSearchView {
  /** Critérios aplicados (`NAME`, `DOCUMENT`, `PHONE`, `TITLE_CODE`); vazio = termo curto ou incompleto. */
  criteria: string[];
  debtors: QuickSearchDebtor[];
  cases: QuickSearchCase[];
  creditorNames: Record<string, string>;
  portfolioNames: Record<string, string>;
  truncated: boolean;
}

export interface QuickSearchDebtor {
  id: string;
  name: string;
  /** Mascarado (nulo após a anonimização). */
  maskedDocument: string | null;
  personType: string;
  status: string;
  matchedBy: string;
  creditorIds: string[];
}

export interface QuickSearchCase {
  id: string;
  debtorId: string;
  creditorId: string;
  portfolioId: string;
  status: string;
}

/**
 * Busca rápida no servidor. POST: o termo (pode ser CPF/CNPJ ou telefone) nunca vai para a URL (ux.md §16); falha
 * vira aviso dentro da própria paleta, sem notificação global.
 */
@Injectable({ providedIn: 'root' })
export class QuickSearchApi {
  private readonly http = inject(HttpClient);

  search(term: string): Observable<QuickSearchView> {
    return this.http.post<QuickSearchView>(`${API_V1}/collection/quick-search`, { term }, {
      context: new HttpContext().set(SKIP_ERROR_NOTIFICATION, true),
    });
  }
}

/** Mínimo de letras ou dígitos para buscar no servidor (o servidor aplica as regras de cada critério). */
export const QUICK_SEARCH_MIN_LENGTH = 3;

/** O termo merece ida ao servidor? (3+ letras ou dígitos; documento e telefone completos o servidor reconhece). */
export function isQuickSearchable(term: string): boolean {
  const plain = term.normalize('NFD').replace(/[̀-ͯ]/g, '');
  return plain.replace(/[^\p{L}\d]/gu, '').length >= QUICK_SEARCH_MIN_LENGTH && term.trim().length <= 80;
}

/** Registro aberto pela busca rápida (devedor ou caso), exibido em "Recentes". */
export interface RecentRecord {
  kind: 'debtor' | 'case';
  id: string;
  label: string;
  hint?: string;
}

export const MAX_RECENTS = 10;

/**
 * "Recentes" da busca rápida: os últimos {@link MAX_RECENTS} devedores e casos abertos. Só em memória da sessão — nunca
 * `localStorage`, `sessionStorage` ou servidor, porque rótulo e dica são dado pessoal (nome, documento mascarado) —
 * e esvaziados na saída ou troca de usuário.
 */
@Injectable({ providedIn: 'root' })
export class RecentRecords {
  private readonly auth = inject(AuthService);
  private readonly list = signal<readonly RecentRecord[]>([]);

  readonly items = this.list.asReadonly();

  constructor() {
    let user = untracked(() => this.auth.user()?.id ?? null);
    effect(() => {
      const id = this.auth.user()?.id ?? null;
      if (id !== user) {
        untracked(() => this.clear());
      }
      user = id;
    });
  }

  /** Coloca no topo (sem repetir) e mantém os últimos {@link MAX_RECENTS}. */
  add(record: RecentRecord): void {
    this.list.update((list) =>
      [record, ...list.filter((r) => !(r.kind === record.kind && r.id === record.id))].slice(0, MAX_RECENTS),
    );
  }

  clear(): void {
    this.list.set([]);
  }
}
