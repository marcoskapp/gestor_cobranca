import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { EvaluableUserSource } from './evaluable-user-source';
import { EvaluationLauncher } from './evaluation-launcher';

describe('EvaluableUserSource (negociadores que posso avaliar)', () => {
  const SEARCH = '/api/v1/incentive/quality/evaluable-users/search';
  let permissions: string[];
  let scope: { all: boolean; ids: ReadonlySet<string> } | null;

  beforeEach(() => {
    permissions = ['QUALITY_EVALUATE'];
    scope = null;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: { user: signal(null), hasPermission: (p: string) => permissions.includes(p) } },
        { provide: EvaluationLauncher, useValue: { scopeSnapshot: () => scope } },
      ],
    });
  });

  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('busca no servidor a lista da mesma regra da gravação: só id e nome, sem e-mail', async () => {
    const result = firstValueFrom(TestBed.inject(EvaluableUserSource).search('jose'));
    const req = TestBed.inject(HttpTestingController).expectOne((r) => r.url === SEARCH);
    expect(req.request.params.get('q')).toBe('jose');
    req.flush({ content: [{ id: 'u-3', name: 'José Araújo' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect(await result).toEqual([{ value: 'u-3', label: 'José Araújo' }]);
  });

  it('resolve o nome do que já está escolhido pelos ids', async () => {
    const result = firstValueFrom(TestBed.inject(EvaluableUserSource).resolve(['u-2', 'u-3']));
    const req = TestBed.inject(HttpTestingController).expectOne((r) => r.url === SEARCH);
    expect(req.request.params.get('ids')).toBe('u-2,u-3');
    req.flush({ content: [{ id: 'u-2', name: 'Carla Souza' }], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    expect((await result).map((o) => o.label)).toEqual(['Carla Souza']);
  });

  it('explica a lista vazia pelo escopo de quem avalia', () => {
    scope = { all: false, ids: new Set(['u-2']) };
    expect(TestBed.inject(EvaluableUserSource).emptyReason()).toContain('sua equipe');
    scope = { all: true, ids: new Set() };
    expect(TestBed.inject(EvaluableUserSource).emptyReason()).not.toContain('sua equipe');
  });

  it('sem permissão de avaliar: nenhum resultado e nenhuma chamada', async () => {
    permissions = [];
    expect(await firstValueFrom(TestBed.inject(EvaluableUserSource).search('a'))).toEqual([]);
  });
});
