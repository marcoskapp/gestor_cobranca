import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Route, Routes, provideRouter } from '@angular/router';
import { fakeUser } from '../../../../testing/auth-fixtures';
import { AuthService } from '../../../core/auth/auth.service';
import { SHELL_ROUTES } from '../../../core/layout/shell/shell.routes';
import { RelatedRecords } from './related-records';
import { RELATED_TARGETS, RelatedRecord, RelatedTarget } from './related-targets';

const permissions = signal<string[]>([]);

@Component({
  imports: [RelatedRecords],
  template: `<app-related-records [records]="records()" />`,
})
class Host {
  readonly records = signal<RelatedRecord[]>([]);
}

function render(records: RelatedRecord[]): HTMLElement {
  const fixture = TestBed.createComponent(Host);
  fixture.componentInstance.records.set(records);
  fixture.detectChanges();
  return fixture.nativeElement as HTMLElement;
}

describe('RelatedRecords', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Host],
      providers: [
        provideRouter([]),
        {
          provide: AuthService,
          useValue: { user: signal(fakeUser()), hasPermission: (p: string) => permissions().includes(p) },
        },
      ],
    });
  });

  it('agrupa por tipo, liga cada registro à ficha e a lista filtrada fica no mesmo grupo', () => {
    permissions.set(['CASE_VIEW', 'PAYMENT_VIEW']);
    const el = render([
      { kind: 'case', id: 'c-1', label: 'Banco Horizonte · Cartões', hint: 'Em negociação' },
      { kind: 'payment', id: 'p-1', label: 'R$ 100,00 em 01/11/2026' },
      { kind: 'paymentList', label: 'Ver todos os pagamentos', queryParams: { devedor: 'd-1' } },
    ]);
    expect(el.querySelector('h2')?.textContent).toContain('Relacionados');
    const groups = [...el.querySelectorAll('.related__group-label')].map((g) => g.textContent?.trim());
    expect(groups).toEqual(['Casos de cobrança', 'Pagamentos']);
    const links = [...el.querySelectorAll('a')].map((a) => a.getAttribute('href'));
    expect(links).toEqual(['/cobranca/casos/c-1', '/pagamentos/p-1', '/pagamentos?devedor=d-1']);
    expect(el.textContent).toContain('Em negociação');
  });

  it('esconde o que o perfil não abre e não renderiza nada sem vínculos visíveis', () => {
    permissions.set(['CASE_VIEW']);
    const el = render([
      { kind: 'case', id: 'c-1', label: 'Caso' },
      { kind: 'legalMatter', id: 'm-1', label: 'Caso jurídico' },
      { kind: 'dispute', id: 'x-1', label: 'Contestação' },
    ]);
    expect([...el.querySelectorAll('a')].map((a) => a.textContent?.trim())).toEqual(['Caso']);

    permissions.set([]);
    expect(render([{ kind: 'case', id: 'c-1', label: 'Caso' }]).querySelector('app-related-records h2')).toBeNull();
    permissions.set(['CASE_VIEW']);
    expect(render([]).querySelector('app-related-records h2')).toBeNull();
    // ficha sem id não vira link
    expect(render([{ kind: 'case', label: 'Sem id' }]).querySelector('app-related-records h2')).toBeNull();
  });
});

// ------------------------------------------------------------------ destinos × rotas

interface ResolvedRoute {
  path: string;
  levels: (readonly string[])[];
  all: readonly string[];
}

async function childrenOf(route: Route): Promise<Routes> {
  if (route.children) {
    return route.children;
  }
  if (route.loadChildren) {
    const loaded = await (route.loadChildren as () => Promise<Routes | { default: Routes }>)();
    return Array.isArray(loaded) ? loaded : loaded.default;
  }
  return [];
}

async function resolve(routes: Routes, prefix: string, levels: (readonly string[])[], out: ResolvedRoute[]): Promise<void> {
  for (const route of routes) {
    if (route.redirectTo !== undefined || route.path === '**') {
      continue;
    }
    const path = [prefix, route.path ?? ''].filter((p) => p !== '').join('/');
    const permissions = route.data?.['permissions'] as readonly string[] | undefined;
    const here = permissions ? [...levels, permissions] : levels;
    const children = await childrenOf(route);
    if (children.length) {
      await resolve(children, path, here, out);
    }
    if (route.component || route.loadComponent) {
      out.push({ path: '/' + path, levels: here, all: (route.data?.['allPermissions'] as readonly string[] | undefined) ?? [] });
    }
  }
}

describe('Relacionados × rotas', () => {
  let routes: ResolvedRoute[];

  beforeAll(async () => {
    routes = [];
    await resolve(SHELL_ROUTES, '', [], routes);
  });

  it('cada destino existe e exige as mesmas permissões que o permissionGuard da rota (nem mais, nem menos)', () => {
    const divergent: string[] = [];
    for (const [kind, target] of Object.entries(RELATED_TARGETS) as [string, RelatedTarget][]) {
      const path = target.detail ? `${target.path}/:id` : target.path;
      const route = routes.find((r) => r.path === path);
      if (!route) {
        divergent.push(`${kind}: rota ${path} não existe`);
        continue;
      }
      const mine = [...target.permissions].sort().join(',');
      const theirs = [...(route.levels.at(-1) ?? [])].sort().join(',');
      if (mine !== theirs) {
        divergent.push(`${kind}: [${mine}] ≠ rota [${theirs}]`);
      }
      if ([...(target.allPermissions ?? [])].sort().join(',') !== [...route.all].sort().join(',')) {
        divergent.push(`${kind}: allPermissions ≠ rota [${route.all}]`);
      }
    }
    expect(divergent).toEqual([]);
  });
});
