import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { RequestAssignees } from './request-assignees';

const URL = '/api/v1/compliance/data-subject-requests/assignable-users';

describe('RequestAssignees', () => {
  let http: HttpTestingController;
  let assignees: RequestAssignees;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    assignees = TestBed.inject(RequestAssignees);
  });

  afterEach(() => http.verify());

  it('busca na lista restrita de responsáveis (não no cadastro de usuários)', async () => {
    const result = firstValueFrom(assignees.search('joana'));
    const req = http.expectOne((r) => r.url === URL);
    expect(req.request.params.get('q')).toBe('joana');
    req.flush({ content: [{ id: 'u-1', name: 'Joana Encarregada' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await result).toEqual([{ value: 'u-1', label: 'Joana Encarregada' }]);
    http.expectNone((r) => r.url.includes('/users'));
  });

  it('nome do que já está escolhido pelos ids, sem consulta quando não há nenhum', async () => {
    expect(await firstValueFrom(assignees.resolve([]))).toEqual([]);
    const result = firstValueFrom(assignees.resolve(['u-1', 'u-2']));
    const req = http.expectOne((r) => r.url === URL);
    expect(req.request.params.get('ids')).toBe('u-1,u-2');
    req.flush({ content: [{ id: 'u-1', name: 'Joana' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await result).toEqual([{ value: 'u-1', label: 'Joana' }]);
  });
});
