import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { NameSource } from '../../../shared/data/name-map';
import { Option } from '../../../shared/models/lookup';
import { isValidDocument, onlyDigits } from '../../../shared/validation/document';
import { DebtorsApi } from './debt.api';
import { DebtorView } from './debt.models';

/** Só dígitos e pontuação de CPF/CNPJ: é um documento (completo ou não), nunca um trecho de nome. */
const DOCUMENT_LIKE = /^[\d.\-/\s]+$/;

/**
 * Fonte de devedores para seletores. Texto com cara de documento só consulta quando é um CPF/CNPJ
 * completo e válido, por `POST /debtors/lookup` (documento fora da URL); parcial ou com dígito
 * verificador errado não consulta nada (nem parcial vai para a URL, que fica em logs de proxy e no
 * histórico). Qualquer outro texto é buscado por trecho do nome.
 */
@Injectable({ providedIn: 'root' })
export class DebtorLookup implements EntitySource {
  private readonly api = inject(DebtorsApi);

  search(q: string): Observable<Option[]> {
    const term = q.trim();
    if (term && DOCUMENT_LIKE.test(term)) {
      const digits = onlyDigits(term);
      return isValidDocument(digits)
        ? this.api.lookup(digits).pipe(
            map((debtor) => [debtorOption(debtor)]),
            catchError(() => of<Option[]>([])),
          )
        : of<Option[]>([]);
    }
    return this.api.search({ q: term || null }, { page: 0, size: 20 }).pipe(map((page) => page.content.map(debtorOption)));
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
