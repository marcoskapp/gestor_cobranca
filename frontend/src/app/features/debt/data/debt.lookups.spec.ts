import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { DEBTOR_NAMES_BATCH, DEBTOR_SEARCH_PLACEHOLDER, DebtorLookup, DebtorNames } from './debt.lookups';

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

  it('dica do seletor: documento completo ou nome', () => {
    expect(lookup.placeholder).toBe(DEBTOR_SEARCH_PLACEHOLDER);
    expect(DEBTOR_SEARCH_PLACEHOLDER).toBe('Informe o CPF/CNPJ completo ou o nome');
  });

  it('explica por que documento parcial ou inválido não traz resultados', () => {
    expect(lookup.emptyReason('529.982.24')).toContain('CPF/CNPJ incompleto');
    expect(lookup.emptyReason('529.982.247-24')).toContain('CPF inválido');
    expect(lookup.emptyReason('11.222.333/0001')).toContain('CNPJ incompleto');
    expect(lookup.emptyReason('11.222.333/0001-82')).toContain('CNPJ inválido');
    expect(lookup.emptyReason('112223330001819')).toContain('dígitos a mais');
  });

  it('documento válido ou nome sem resultados ficam com a mensagem padrão', () => {
    expect(lookup.emptyReason('529.982.247-25')).toBeNull();
    expect(lookup.emptyReason('11.222.333/0001-81')).toBeNull();
    expect(lookup.emptyReason('Ana Paula')).toBeNull();
    expect(lookup.emptyReason('')).toBeNull();
  });
});
