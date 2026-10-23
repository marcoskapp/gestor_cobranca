import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { NameSource } from '../../../shared/data/name-map';
import { Option } from '../../../shared/models/lookup';
import { isValidDocument } from '../../../shared/validation/document';
import { DebtorsApi } from './debt.api';
import { DebtorView } from './debt.models';

/**
 * Fonte de devedores para seletores. Texto que é um CPF/CNPJ válido é buscado por
 * `POST /debtors/lookup` (documento fora da URL); qualquer outro texto, por trecho do nome.
 */
@Injectable({ providedIn: 'root' })
export class DebtorLookup implements EntitySource {
  private readonly api = inject(DebtorsApi);

  search(q: string): Observable<Option[]> {
    if (isValidDocument(q)) {
      return this.api.lookup(q).pipe(
        map((debtor) => [debtorOption(debtor)]),
        catchError(() => of<Option[]>([])),
      );
    }
    return this.api.search({ q: q || null }, { page: 0, size: 20 }).pipe(map((page) => page.content.map(debtorOption)));
  }

  resolve(ids: string[]): Observable<Option[]> {
    return ids.length
      ? forkJoin(
          ids.map((id) =>
            this.api.get(id).pipe(
              map(debtorOption),
              catchError(() => of<Option>({ value: id, label: 'Devedor indisponível' })),
            ),
          ),
        )
      : of<Option[]>([]);
  }
}

export function debtorOption(d: DebtorView): Option {
  return { value: d.id, label: d.maskedDocument ? `${d.name} (${d.maskedDocument})` : d.name };
}

/** Ids por chamada de `GET /debtors/names` (o servidor aceita até 200). */
export const DEBTOR_NAMES_BATCH = 100;

/**
 * Nomes de devedores para tabelas e telas operacionais (sem documento: a coluna de documento,
 * quando existe, é separada). Uma chamada em lote por página (`GET /debtors/names`), aceita também
 * para quem trabalha casos ou a caixa de entrada sem consultar o cadastro de devedores.
 */
@Injectable({ providedIn: 'root' })
export class DebtorNames implements NameSource {
  private readonly api = inject(DebtorsApi);
  readonly batchSize = DEBTOR_NAMES_BATCH;

  resolve(ids: string[]): Observable<Option[]> {
    return ids.length ? this.api.names(ids).pipe(map((list) => list.map((d): Option => ({ value: d.id, label: d.name })))) : of<Option[]>([]);
  }
}
