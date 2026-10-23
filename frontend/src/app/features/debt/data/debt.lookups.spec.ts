import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { DEBTOR_NAMES_BATCH, DebtorNames } from './debt.lookups';

describe('DebtorNames', () => {
  it('resolve nomes em lote pelo endpoint leve (sem documento) e declara o tamanho do lote', async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    const names = TestBed.inject(DebtorNames);
    const http = TestBed.inject(HttpTestingController);
    expect(names.batchSize).toBe(DEBTOR_NAMES_BATCH);
    expect(DEBTOR_NAMES_BATCH).toBeLessThanOrEqual(200);
    const result = firstValueFrom(names.resolve(['d-1', 'd-2']));
    const req = http.expectOne((r) => r.url === '/api/v1/debtors/names');
    expect(req.request.params.get('ids')).toBe('d-1,d-2');
    req.flush([{ id: 'd-1', name: 'Ana Paula Ribeiro', status: 'ACTIVE' }]);
    expect(await result).toEqual([{ value: 'd-1', label: 'Ana Paula Ribeiro' }]);
    http.verify();
  });
});
