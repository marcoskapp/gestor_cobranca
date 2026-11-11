import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { IncentiveAssignees } from './incentive-assignees';

describe('IncentiveAssignees (negociadores atribuíveis do incentivo)', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] }));
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('meta: busca na lista restrita de metas (não no cadastro de usuários) e mostra só o nome', async () => {
    const result = firstValueFrom(TestBed.inject(IncentiveAssignees).source('GOAL').search('ana'));
    const req = TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/v1/incentive/goals/assignable-users');
    expect(req.request.params.get('q')).toBe('ana');
    expect(req.request.params.get('size')).toBe('20');
    req.flush({ content: [{ id: 'u-1', name: 'Ana Lima' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await result).toEqual([{ value: 'u-1', label: 'Ana Lima' }]);
  });

  it('monitoria: busca na lista de avaliáveis; resolução sem ids não chama o servidor', async () => {
    const source = TestBed.inject(IncentiveAssignees).source('EVALUATION');
    expect(await firstValueFrom(source.resolve([]))).toEqual([]);
    const result = firstValueFrom(source.search(''));
    const req = TestBed.inject(HttpTestingController).expectOne((r) => r.url === '/api/v1/incentive/quality/evaluable-users/search');
    expect(req.request.params.has('q')).toBe(false);
    req.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    expect(await result).toEqual([]);
  });
});
