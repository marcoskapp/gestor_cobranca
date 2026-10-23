import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/auth.guards';
import { LOGIN_ROUTES, PUBLIC_AUTH_ROUTES } from './features/auth/auth.routes';

const authLayout = () => import('./core/layout/auth-layout/auth-layout').then((m) => m.AuthLayout);

/**
 * Telas públicas usam a moldura de autenticação; a área autenticada usa o shell (menu e barra
 * superior). Cada rota pública declara o próprio caminho para que `/` sempre chegue ao shell.
 */
export const routes: Routes = [
  { path: 'login', loadComponent: authLayout, canActivate: [guestGuard], children: LOGIN_ROUTES },
  ...PUBLIC_AUTH_ROUTES.map((route) => ({
    path: route.path,
    loadComponent: authLayout,
    children: [{ ...route, path: '' }],
  })),
  {
    path: '',
    loadComponent: () => import('./core/layout/shell/shell').then((m) => m.Shell),
    canActivate: [authGuard],
    canActivateChild: [authGuard],
    children: [
      {
        path: '',
        pathMatch: 'full',
        title: 'Início',
        loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
      },
      { path: 'conta', loadChildren: () => import('./features/account/account.routes').then((m) => m.ACCOUNT_ROUTES) },
      { path: 'admin', loadChildren: () => import('./features/admin/admin.routes').then((m) => m.ADMIN_ROUTES) },
      {
        path: 'organizacao',
        loadChildren: () => import('./features/organization/organization.routes').then((m) => m.ORGANIZATION_ROUTES),
      },
      { path: 'devedores', loadChildren: () => import('./features/debt/debt.routes').then((m) => m.DEBTOR_ROUTES) },
      { path: 'titulos', loadChildren: () => import('./features/debt/debt.routes').then((m) => m.TITLE_ROUTES) },
      {
        path: 'compliance',
        loadChildren: () => import('./features/compliance/compliance.routes').then((m) => m.COMPLIANCE_ROUTES),
      },
      {
        path: 'governanca',
        loadChildren: () => import('./features/governance/governance.routes').then((m) => m.GOVERNANCE_ROUTES),
      },
      {
        path: 'plataforma',
        loadChildren: () => import('./features/platform/platform.routes').then((m) => m.PLATFORM_ROUTES),
      },
      {
        path: 'acesso-negado',
        title: 'Acesso negado',
        loadComponent: () => import('./core/layout/pages/forbidden').then((m) => m.Forbidden),
      },
      {
        path: '**',
        title: 'Página não encontrada',
        loadComponent: () => import('./core/layout/pages/not-found').then((m) => m.NotFound),
      },
    ],
  },
];
