import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/** Permissões de cada tela (espelham o `@PreAuthorize` dos controllers internos do módulo publicapi). */
export const PUBLIC_API_PERMISSIONS = {
  tokens: [Permission.PUBLIC_API_TOKEN_VIEW],
  usage: [Permission.PUBLIC_API_USAGE_VIEW],
  billing: [Permission.PUBLIC_API_USAGE_VIEW],
} as const;

/** `/integracoes`: API pública de integração dos credores. */
export const PUBLIC_API_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'tokens' },
  {
    path: 'tokens',
    title: 'Tokens de integração',
    canActivate: [permissionGuard],
    data: { permissions: PUBLIC_API_PERMISSIONS.tokens },
    loadComponent: () => import('./tokens/token-list').then((m) => m.TokenList),
  },
  {
    path: 'tokens/:id',
    title: 'Token de integração',
    canActivate: [permissionGuard],
    data: { permissions: PUBLIC_API_PERMISSIONS.tokens },
    loadComponent: () => import('./tokens/token-detail').then((m) => m.TokenDetail),
  },
  {
    path: 'consumo',
    title: 'Consumo da API pública',
    canActivate: [permissionGuard],
    data: { permissions: PUBLIC_API_PERMISSIONS.usage },
    loadComponent: () => import('./usage/usage-page').then((m) => m.UsagePage),
  },
  {
    path: 'extratos',
    title: 'Extratos da API pública',
    canActivate: [permissionGuard],
    data: { permissions: PUBLIC_API_PERMISSIONS.billing },
    loadComponent: () => import('./usage/billing-page').then((m) => m.BillingPage),
  },
];
