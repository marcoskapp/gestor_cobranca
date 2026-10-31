import { Route, Routes } from '@angular/router';
import { ANALYTICS_PANELS } from '../../features/analytics/analytics.panels';
import { NAVIGATION } from './navigation';
import { SHELL_ROUTES } from './shell/shell.routes';

/**
 * Menu, abas e rotas não podem divergir: um item do menu (ou aba dos Painéis) só aparece para quem passa no
 * `permissionGuard` da rota (senão o usuário vê a opção e cai em "Acesso negado"), e a rota não pode exigir menos que
 * o menu (a opção sumiria para quem tem acesso). A árvore real de rotas é resolvida, inclusive os módulos carregados sob demanda.
 */

interface ResolvedRoute {
  path: string;
  /** Permissões exigidas em cada nível (do mais externo ao mais interno) que declara `data.permissions`. */
  levels: (readonly string[])[];
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
      out.push({ path: '/' + path, levels: here });
    }
  }
}

describe('menu × rotas', () => {
  let routes: ResolvedRoute[];

  beforeAll(async () => {
    routes = [];
    await resolve(SHELL_ROUTES, '', [], routes);
  });

  // menu lateral e abas internas com permissões próprias (abas dos Painéis)
  const items = [...NAVIGATION.flatMap((s) => s.items), ...ANALYTICS_PANELS];

  it('todo item do menu e das abas tem rota', () => {
    const missing = items.filter((i) => !routes.some((r) => r.path === i.route));
    expect(missing.map((i) => i.route)).toEqual([]);
  });

  it('cada item do menu e das abas exige as mesmas permissões que a rota (nem mais, nem menos)', () => {
    const divergent: string[] = [];
    for (const item of items) {
      const route = routes.find((r) => r.path === item.route);
      if (!route) {
        continue;
      }
      const menu = [...item.permissions].sort();
      const innermost = [...(route.levels.at(-1) ?? [])].sort();
      if (menu.join(',') !== innermost.join(',')) {
        divergent.push(`${item.route}: menu [${menu}] ≠ rota [${innermost}]`);
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
});
