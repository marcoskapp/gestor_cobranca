import { Routes } from '@angular/router';
import { permissionGuard } from '../../core/auth/auth.guards';
import { Permission } from '../../core/auth/permission';

/**
 * Credores e carteiras abrem com a permissão de área (menu por tarefa, D6) E a de consulta dos dados
 * (`CREDITOR_VIEW`/`PORTFOLIO_VIEW`, exigidas também pelo servidor), que outras telas usam para mostrar nomes sem abrir a área.
 */
export const ORGANIZATION_ROUTES: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'credores' },
  {
    path: 'configuracoes',
    title: 'Configurações da assessoria',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.ORG_SETTINGS_MANAGE] },
    loadComponent: () => import('./settings/tenant-settings').then((m) => m.TenantSettings),
  },
  {
    path: 'credores',
    title: 'Credores',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.ORGANIZATION_AREA_VIEW], allPermissions: [Permission.CREDITOR_VIEW] },
    loadComponent: () => import('./creditors/creditor-list/creditor-list').then((m) => m.CreditorList),
  },
  {
    path: 'credores/:id',
    title: 'Credor',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.ORGANIZATION_AREA_VIEW], allPermissions: [Permission.CREDITOR_VIEW] },
    loadComponent: () => import('./creditors/creditor-detail/creditor-detail').then((m) => m.CreditorDetail),
  },
  {
    path: 'carteiras',
    title: 'Carteiras',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.ORGANIZATION_AREA_VIEW], allPermissions: [Permission.PORTFOLIO_VIEW] },
    loadComponent: () => import('./portfolios/portfolio-list/portfolio-list').then((m) => m.PortfolioList),
  },
  {
    // custos operacionais (módulo costing): faixas de pessoas (COST_RATE_MANAGE) e tarifas, câmbio e fixos (COST_MANAGE)
    path: 'custos',
    title: 'Custos operacionais',
    canActivate: [permissionGuard],
    data: { permissions: [Permission.COST_MANAGE, Permission.COST_RATE_MANAGE] },
    loadComponent: () => import('../costing/settings/cost-settings').then((m) => m.CostSettings),
  },
];
