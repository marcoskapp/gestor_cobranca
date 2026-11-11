import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { BehaviorSubject, firstValueFrom, of } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { UserNames } from '../../admin/data/user-names';
import { UserLookup } from '../../admin/data/user.lookup';
import { EvaluableUserSource } from './evaluable-user-source';
import { EvaluationLauncher } from './evaluation-launcher';

describe('EvaluableUserSource (negociadores que posso avaliar)', () => {
  let permissions: string[];
  const scope$ = new BehaviorSubject<{ all: boolean; ids: ReadonlySet<string> } | null>(null);
  const userSearch = vi.fn();
  const namesResolve = vi.fn();

  beforeEach(() => {
    permissions = ['QUALITY_EVALUATE'];
    scope$.next(null);
    userSearch.mockReset().mockReturnValue(of([{ value: 'u-1', label: 'Eu' }, { value: 'u-2', label: 'Carla (carla@x)' }]));
    namesResolve.mockReset().mockReturnValue(of([{ value: 'u-3', label: 'José Araújo' }, { value: 'u-2', label: 'Carla Souza' }]));
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: { user: signal(null), hasPermission: (p: string) => permissions.includes(p) } },
        { provide: UserLookup, useValue: { search: userSearch, resolve: () => of([]) } },
        { provide: UserNames, useValue: { resolve: namesResolve } },
        {
          provide: EvaluationLauncher,
          useValue: { scope$, scopeSnapshot: () => scope$.value, canEvaluate: (id: string) => id !== 'u-1' },
        },
      ],
    });
  });

  it('visão total: busca usuários ativos, sem a própria pessoa', async () => {
    scope$.next({ all: true, ids: new Set() });
    const options = await firstValueFrom(TestBed.inject(EvaluableUserSource).search('a'));
    expect(options.map((o) => o.value)).toEqual(['u-2']);
    expect(TestBed.inject(EvaluableUserSource).emptyReason()).toBeNull();
  });

  it('equipe: parte dos membros (só o nome) e filtra sem acento nem caixa', async () => {
    scope$.next({ all: false, ids: new Set(['u-2', 'u-3']) });
    const options = await firstValueFrom(TestBed.inject(EvaluableUserSource).search('jose ARA'));
    expect(options).toEqual([{ value: 'u-3', label: 'José Araújo' }]);
    expect(namesResolve).toHaveBeenCalledWith(['u-2', 'u-3']);
    expect(userSearch).not.toHaveBeenCalled();
    expect(TestBed.inject(EvaluableUserSource).emptyReason()).toContain('sua equipe');
  });

  it('espera a resposta do servidor antes de buscar', async () => {
    namesResolve.mockReturnValue(of([{ value: 'u-2', label: 'Carla Souza' }]));
    const result = firstValueFrom(TestBed.inject(EvaluableUserSource).search(''));
    expect(namesResolve).not.toHaveBeenCalled();
    scope$.next({ all: false, ids: new Set(['u-2']) });
    expect((await result).map((o) => o.label)).toEqual(['Carla Souza']);
  });

  it('sem permissão de avaliar: nenhum resultado', async () => {
    permissions = [];
    expect(await firstValueFrom(TestBed.inject(EvaluableUserSource).search('a'))).toEqual([]);
  });
});
