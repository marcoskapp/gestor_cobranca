import { Injectable, inject } from '@angular/core';
import { forkJoin, map, of } from 'rxjs';
import { EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { Option } from '../../../shared/models/lookup';
import { CreditorsApi, PortfoliosApi } from './organization.api';
import { CreditorView, PortfolioView } from './organization.models';

/** Fonte de credores para seletores (usada também pelo módulo de administração). */
@Injectable({ providedIn: 'root' })
export class CreditorLookup implements EntitySource {
  private readonly api = inject(CreditorsApi);

  search(q: string) {
    return this.api.search({ q }, { page: 0, size: 20 }).pipe(map((page) => page.content.map(creditorOption)));
  }

  resolve(ids: string[]) {
    return ids.length ? forkJoin(ids.map((id) => this.api.get(id).pipe(map(creditorOption)))) : of<Option[]>([]);
  }
}

export function creditorOption(c: CreditorView): Option {
  return { value: c.id, label: c.tradeName ? `${c.tradeName} — ${c.legalName}` : c.legalName };
}

/** Fonte de carteiras para seletores, opcionalmente restrita ao credor escolhido. */
@Injectable({ providedIn: 'root' })
export class PortfolioLookup {
  private readonly api = inject(PortfoliosApi);

  /** Fonte para `app-entity-picker`; `creditorId` é lido a cada busca. */
  source(creditorId: () => string | null = () => null): EntitySource {
    return {
      search: (q: string) =>
        this.api
          .search({ q, creditorId: creditorId() }, { page: 0, size: 20 })
          .pipe(map((page) => page.content.map(portfolioOption))),
      resolve: (ids: string[]) =>
        ids.length ? forkJoin(ids.map((id) => this.api.get(id).pipe(map(portfolioOption)))) : of<Option[]>([]),
    };
  }
}

export function portfolioOption(p: PortfolioView): Option {
  return { value: p.id, label: `${p.code} — ${p.name}` };
}
