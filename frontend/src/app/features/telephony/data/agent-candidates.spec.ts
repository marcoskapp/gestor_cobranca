import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AgentCandidates } from './agent-candidates';

const URL = '/api/v1/telephony/agents/assignable-users';

describe('AgentCandidates', () => {
  let http: HttpTestingController;
  let candidates: AgentCandidates;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    candidates = TestBed.inject(AgentCandidates);
  });

  afterEach(() => http.verify());

  it('busca na lista restrita de usuários habilitáveis (não no cadastro de usuários)', async () => {
    const result = firstValueFrom(candidates.search('ana'));
    const req = http.expectOne((r) => r.url === URL);
    expect(req.request.params.get('q')).toBe('ana');
    req.flush({ content: [{ id: 'u-1', name: 'Ana Operadora' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await result).toEqual([{ value: 'u-1', label: 'Ana Operadora' }]);
  });

  it('nome do que já está escolhido pelos ids, sem consulta quando não há nenhum', async () => {
    expect(await firstValueFrom(candidates.resolve([]))).toEqual([]);
    const result = firstValueFrom(candidates.resolve(['u-1']));
    const req = http.expectOne((r) => r.url === URL);
    expect(req.request.params.get('ids')).toBe('u-1');
    req.flush({ content: [{ id: 'u-1', name: 'Ana' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await result).toEqual([{ value: 'u-1', label: 'Ana' }]);
  });
});
