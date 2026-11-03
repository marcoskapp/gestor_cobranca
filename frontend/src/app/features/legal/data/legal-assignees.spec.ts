import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { LegalAssignees } from './legal-assignees';

describe('LegalAssignees', () => {
  let http: HttpTestingController;
  let assignees: LegalAssignees;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    assignees = TestBed.inject(LegalAssignees);
  });

  afterEach(() => http.verify());

  it('busca na lista restrita do jurídico (não no cadastro de usuários), com o campo e o caso', async () => {
    const result = firstValueFrom(assignees.source('MATTER_RESPONSIBLE', 'm-1').search('ana'));
    const req = http.expectOne((r) => r.url === '/api/v1/legal/assignable-users');
    expect(req.request.params.get('purpose')).toBe('MATTER_RESPONSIBLE');
    expect(req.request.params.get('matterId')).toBe('m-1');
    expect(req.request.params.get('q')).toBe('ana');
    req.flush({ content: [{ id: 'u-1', name: 'Ana Jurídica' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await result).toEqual([{ value: 'u-1', label: 'Ana Jurídica' }]);
  });

  it('nome do que já está escolhido pelos ids, sem consulta quando não há nenhum', async () => {
    const source = assignees.source('FIRM_MEMBER');
    expect(await firstValueFrom(source.resolve([]))).toEqual([]);
    const result = firstValueFrom(source.resolve(['u-1', 'u-2']));
    const req = http.expectOne((r) => r.url === '/api/v1/legal/assignable-users');
    expect(req.request.params.get('ids')).toBe('u-1,u-2');
    expect(req.request.params.has('matterId')).toBe(false);
    req.flush({ content: [{ id: 'u-1', name: 'Ana' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await result).toEqual([{ value: 'u-1', label: 'Ana' }]);
  });
});
