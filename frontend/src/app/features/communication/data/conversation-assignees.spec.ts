import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { ConversationAssignees } from './conversation-assignees';

describe('ConversationAssignees', () => {
  let http: HttpTestingController;
  let assignees: ConversationAssignees;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
    assignees = TestBed.inject(ConversationAssignees);
  });

  afterEach(() => http.verify());

  it('busca os operadores atribuíveis da conversa (não o cadastro de usuários)', async () => {
    const result = firstValueFrom(assignees.source('cv-1').search('ana'));
    const req = http.expectOne((r) => r.url === '/api/v1/communication/conversations/cv-1/assignable-users');
    expect(req.request.params.get('q')).toBe('ana');
    req.flush({ content: [{ id: 'u-1', name: 'Ana' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await result).toEqual([{ value: 'u-1', label: 'Ana' }]);
  });

  it('resolve os ids escolhidos, sem consulta quando não há nenhum', async () => {
    const source = assignees.source('cv-1');
    expect(await firstValueFrom(source.resolve([]))).toEqual([]);
    const result = firstValueFrom(source.resolve(['u-1']));
    const req = http.expectOne((r) => r.url === '/api/v1/communication/conversations/cv-1/assignable-users');
    expect(req.request.params.get('ids')).toBe('u-1');
    req.flush({ content: [{ id: 'u-1', name: 'Ana' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await result).toEqual([{ value: 'u-1', label: 'Ana' }]);
  });
});
