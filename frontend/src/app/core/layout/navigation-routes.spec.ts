import { Route, Routes } from '@angular/router';
import { NAVIGATION, routePermissionsOf, routeRequiredPermissionsOf } from './navigation';
import { SHELL_ROUTES } from './shell/shell.routes';

/**
 * Menu e rotas não podem divergir: um item do menu só aparece para quem passa no `permissionGuard` da rota (senão o
 * usuário vê a opção e cai em "Acesso negado"), e a rota aceita exatamente as permissões da tarefa (menu) mais as de
 * quem abre a tela por outro caminho (`openedFrom`, menu por tarefa — D6), que precisa ser um item do menu visível
 * para essas permissões. A árvore real de rotas é resolvida, inclusive os módulos carregados sob demanda.
 */

interface ResolvedRoute {
  path: string;
  /** Permissões exigidas em cada nível (do mais externo ao mais interno) que declara `data.permissions`. */
  levels: (readonly string[])[];
  /** `data.allPermissions` da rota mais interna (todas obrigatórias). */
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

describe('menu × rotas', () => {
  let routes: ResolvedRoute[];

  beforeAll(async () => {
    routes = [];
    await resolve(SHELL_ROUTES, '', [], routes);
  });

  const items = NAVIGATION.flatMap((s) => s.items);

  it('todo item do menu tem rota', () => {
    const missing = items.filter((i) => !routes.some((r) => r.path === i.route));
    expect(missing.map((i) => i.route)).toEqual([]);
  });

  it('cada item do menu exige as mesmas permissões que a rota (nem mais, nem menos), contando quem abre por outra tela', () => {
    const divergent: string[] = [];
    for (const item of items) {
      const route = routes.find((r) => r.path === item.route);
      if (!route) {
        continue;
      }
      const menu = [...routePermissionsOf(item)].sort();
      const innermost = [...(route.levels.at(-1) ?? [])].sort();
      if (menu.join(',') !== innermost.join(',')) {
        divergent.push(`${item.route}: menu [${menu}] ≠ rota [${innermost}]`);
      }
      const required = [...routeRequiredPermissionsOf(item)].sort();
      if (required.join(',') !== [...route.all].sort().join(',')) {
        divergent.push(`${item.route}: menu exige todas [${required}] ≠ rota allPermissions [${route.all}]`);
      }
      // níveis externos (rota pai com guarda) também precisam aceitar todas as permissões do item
      for (const outer of route.levels.slice(0, -1)) {
        const blocked = menu.filter((p) => !outer.includes(p));
        if (blocked.length) {
          divergent.push(`${item.route}: rota pai não aceita [${blocked}]`);
        }
      }
    }
    expect(divergent).toEqual([]);
  });

  it('quem abre a tela por outro caminho chega por um item do menu que vê', () => {
    const problems: string[] = [];
    for (const item of items.filter((i) => i.openedFrom)) {
      const via = items.find((i) => i.route === item.openedFrom!.via);
      if (!via) {
        problems.push(`${item.route}: via ${item.openedFrom!.via} não está no menu`);
        continue;
      }
      const blocked = item.openedFrom!.permissions.filter((p) => via.permissions.length > 0 && !via.permissions.includes(p));
      if (blocked.length) {
        problems.push(`${item.route}: [${blocked}] não vê ${via.route}`);
      }
      const overlap = item.openedFrom!.permissions.filter((p) => item.permissions.includes(p));
      if (overlap.length) {
        problems.push(`${item.route}: [${overlap}] já está no menu`);
      }
    }
    expect(problems).toEqual([]);
  });
});
