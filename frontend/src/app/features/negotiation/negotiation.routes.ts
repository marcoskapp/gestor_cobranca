import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/** Permissões de cada tela (espelham o `@PreAuthorize` dos controllers do módulo negotiation). */
export const NEGOTIATION_PERMISSIONS = {
  simulator: [Permission.NEGOTIATION_SIMULATE],
  policies: [Permission.POLICY_VIEW],
  indexes: [Permission.POLICY_VIEW],
  authority: [Permission.AUTHORITY_MANAGE],
} as const;

/** `/negociacao`: simulador, políticas versionadas, índices de correção e alçadas (parte 1). */
export const NEGOTIATION_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'simulador' },
  {
    path: 'simulador',
    title: 'Simulador de negociação',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.simulator },
    loadComponent: () => import('./simulator/simulator').then((m) => m.Simulator),
  },
  {
    path: 'politicas',
    title: 'Políticas de cobrança',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.policies },
    loadComponent: () => import('./policies/policy-list').then((m) => m.PolicyList),
  },
  {
    path: 'politicas/:id',
    title: 'Política de cobrança',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.policies },
    loadComponent: () => import('./policies/policy-detail').then((m) => m.PolicyDetail),
  },
  {
    path: 'indices',
    title: 'Índices de correção',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.indexes },
    loadComponent: () => import('./indexes/index-list').then((m) => m.IndexList),
  },
  {
    path: 'alcadas',
    title: 'Alçadas de negociação',
    canActivate: [permissionGuard],
    data: { permissions: NEGOTIATION_PERMISSIONS.authority },
    loadComponent: () => import('./authority/authority-page').then((m) => m.AuthorityPage),
  },
];
