import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { DEBTOR_NAMES_BATCH, DebtorLookup, DebtorNames } from './debt.lookups';

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

describe('DebtorLookup', () => {
  let http: HttpTestingController;
  let lookup: DebtorLookup;
  const debtor = { id: 'd-1', name: 'Ana Paula Ribeiro', maskedDocument: '***.982.247-**' };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    lookup = TestBed.inject(DebtorLookup);
  });

  afterEach(() => http.verify());

  it('documento parcial não consulta nada (nem vai para a URL)', async () => {
    expect(await firstValueFrom(lookup.search('529.982.24'))).toEqual([]);
    expect(await firstValueFrom(lookup.search('5299822'))).toEqual([]);
    http.expectNone(() => true);
  });

  it('documento completo com dígito verificador errado não consulta nada', async () => {
    expect(await firstValueFrom(lookup.search('529.982.247-24'))).toEqual([]);
    http.expectNone(() => true);
  });

  it('documento válido vai no corpo do POST, só com dígitos', async () => {
    const result = firstValueFrom(lookup.search(' 529.982.247-25 '));
    const req = http.expectOne('/api/v1/debtors/lookup');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ document: '52998224725' });
    expect(req.request.urlWithParams).not.toContain('5299822');
    req.flush(debtor);
    expect(await result).toEqual([{ value: 'd-1', label: 'Ana Paula Ribeiro (***.982.247-**)' }]);
  });

  it('nome é buscado por trecho na URL', async () => {
    const result = firstValueFrom(lookup.search('Ana Paula'));
    const req = http.expectOne((r) => r.url === '/api/v1/debtors');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('q')).toBe('Ana Paula');
    req.flush({ content: [debtor], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await result).toEqual([{ value: 'd-1', label: 'Ana Paula Ribeiro (***.982.247-**)' }]);
  });
});
