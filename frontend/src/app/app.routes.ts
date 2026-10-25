import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth/auth.guards';
import { LOGIN_ROUTES, PUBLIC_AUTH_ROUTES } from './features/auth/auth.routes';

const authLayout = () => import('./core/layout/auth-layout/auth-layout').then((m) => m.AuthLayout);

/**
 * Telas públicas usam a moldura de autenticação; a área autenticada usa o shell (menu e barra
 * superior). Cada rota pública declara o próprio caminho para que `/` sempre chegue ao shell.
 */
export const routes: Routes = [
  // portal do devedor: público, sem o shell e sem a sessão interna (carregado sob demanda)
  {
    path: 'portal',
    loadChildren: () => import('./portal/portal.routes').then((m) => m.PORTAL_ROUTES),
  },
  { path: 'login', loadComponent: authLayout, canActivate: [guestGuard], children: LOGIN_ROUTES },
  ...PUBLIC_AUTH_ROUTES.map((route) => ({
    path: route.path,
    loadComponent: authLayout,
    children: [{ ...route, path: '' }],
  })),
  {
    // área autenticada: shell e telas carregados sob demanda (ver core/layout/shell/shell.routes.ts)
    path: '',
    canActivate: [authGuard],
    canActivateChild: [authGuard],
    loadChildren: () => import('./core/layout/shell/shell.routes').then((m) => m.SHELL_ROUTES),
  },
];
