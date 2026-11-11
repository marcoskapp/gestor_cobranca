import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { CollectionAssignees } from './collection-assignees';

describe('CollectionAssignees', () => {
  let http: HttpTestingController;
  let assignees: CollectionAssignees;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    assignees = TestBed.inject(CollectionAssignees);
  });

  afterEach(() => http.verify());

  it('busca na lista restrita da cobrança (não no cadastro de usuários), com o campo e o caso', async () => {
    const result = firstValueFrom(assignees.source('CASE_WORKER', { caseId: 'k-1' }).search('ana'));
    const req = http.expectOne((r) => r.url === '/api/v1/collection/assignable-users');
    expect(req.request.params.get('purpose')).toBe('CASE_WORKER');
    expect(req.request.params.get('caseId')).toBe('k-1');
    expect(req.request.params.get('q')).toBe('ana');
    expect(req.request.params.has('fromUserId')).toBe(false);
    req.flush({ content: [{ id: 'u-1', name: 'Ana Operadora', active: true }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await result).toEqual([{ value: 'u-1', label: 'Ana Operadora' }]);
  });

  it('origem da redistribuição marca quem está inativo; destino leva a origem escolhida', async () => {
    const sources = firstValueFrom(assignees.source('CASE_REDISTRIBUTION_SOURCE').search(''));
    const req = http.expectOne((r) => r.url === '/api/v1/collection/assignable-users');
    expect(req.request.params.has('q')).toBe(false);
    req.flush({ content: [{ id: 'u-2', name: 'Bruno', active: false }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await sources).toEqual([{ value: 'u-2', label: 'Bruno (inativo)' }]);

    const targets = firstValueFrom(assignees.source('DISPUTE_REDISTRIBUTION_TARGET', { fromUserId: 'u-2' }).search(''));
    const target = http.expectOne((r) => r.url === '/api/v1/collection/assignable-users');
    expect(target.request.params.get('purpose')).toBe('DISPUTE_REDISTRIBUTION_TARGET');
    expect(target.request.params.get('fromUserId')).toBe('u-2');
    target.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    expect(await targets).toEqual([]);
  });

  it('nome do que já está escolhido pelos ids, sem consulta quando não há nenhum', async () => {
    const source = assignees.source('DISPUTE_POOL_MEMBER', { creditorId: 'c-1' });
    expect(await firstValueFrom(source.resolve([]))).toEqual([]);
    const result = firstValueFrom(source.resolve(['u-1', 'u-2']));
    const req = http.expectOne((r) => r.url === '/api/v1/collection/assignable-users');
    expect(req.request.params.get('ids')).toBe('u-1,u-2');
    expect(req.request.params.get('creditorId')).toBe('c-1');
    req.flush({ content: [{ id: 'u-1', name: 'Ana', active: true }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await result).toEqual([{ value: 'u-1', label: 'Ana' }]);
  });
});
